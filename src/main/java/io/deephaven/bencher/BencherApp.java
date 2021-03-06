package io.deephaven.bencher;

import com.google.common.base.Stopwatch;
import io.deephaven.client.impl.ConsoleSession;
import io.deephaven.client.impl.Session;
import io.deephaven.client.impl.SessionImplConfig;
import io.deephaven.client.impl.script.Changes;
import io.deephaven.client.impl.FieldInfo;

import io.deephaven.datagen.DataGen;

import io.deephaven.datagen.Utils;
import io.deephaven.proto.DeephavenChannel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.apache.commons.text.StringEscapeUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BencherApp {

    private static final boolean TERSE = Boolean.parseBoolean(System.getProperty("terse", "False"));;
    private static final boolean SKIP_CLEANUP = Boolean.parseBoolean(System.getProperty("skip.cleanup", "False"));
    private static final Duration SESSION_TIMEOUT =
            Duration.ofMillis(Long.parseLong(System.getProperty("session.timeout.millis", "300000")));
    private static final String DH_ENDPOINT = System.getProperty("dh.endpoint", "localhost:10000");
    private static final String JOBS_PREFIX_PATH = System.getProperty("jobs.prefix.path", "jobs");
    private static final boolean GENERATE_ONLY = Boolean.parseBoolean(System.getProperty("generate.only", "False"));

    private static void append(final StringBuilder sb, final List<FieldInfo> vars) {
        boolean first = true;
        for (FieldInfo variableDefinition : vars) {
            if (!first) {
                sb.append(",");
            }
            sb.append(variableDefinition.name());
            first = false;
        }
    }

    public static String toPrettyString(Changes changes) {
        final StringBuilder sb = new StringBuilder();
        if (changes.errorMessage().isPresent()) {
            sb.append("Error: ").append(changes.errorMessage().get()).append(System.lineSeparator());
        }
        if (changes.isEmpty()) {
            sb.append("No displayable variable changes").append(System.lineSeparator());
        } else {
            sb.append("created={");
            append(sb, changes.changes().created());
            sb.append("}, updated={");
            append(sb, changes.changes().updated());
            sb.append("}, removed={");
            append(sb, changes.changes().removed());
            sb.append("}").append(System.lineSeparator());
        }
        return sb.toString();
    }

    public static final class LiveVariablesTracker extends HashSet<String> {
        public void update(final Changes changes) {
            for (final FieldInfo variableDefinition : changes.changes().created()) {
                super.add(variableDefinition.name());
            }
            for (final FieldInfo variableDefinition : changes.changes().removed()) {
                super.remove(variableDefinition.name());
            }
        }

        public String generateCleanupPythonStatement() {
            final StringBuilder builder = new StringBuilder();
            boolean first = true;
            for (final String varName : this) {
                if (!first) {
                    builder.append("; ");
                }
                builder.append(varName).append("=None");
                first = false;
            }
            return builder.toString();
        }
    }

    static Session getSession(final ScheduledExecutorService scheduler, final ManagedChannel managedChannel) {
        SessionImplConfig cfg = SessionImplConfig.builder()
                .executor(scheduler)
                .closeTimeout(SESSION_TIMEOUT)
                .executeTimeout(Duration.ofMillis(600000))
                .channel(new DeephavenChannel(managedChannel))
                .build();

        return cfg.createSession();
    }

    private static void shutdown(
            final ScheduledExecutorService scheduler,
            final ManagedChannel managedChannel) {
        if (!scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (!managedChannel.isShutdown()) {
            managedChannel.shutdownNow();
        }
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Scheduler not shutdown after 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            final long waitSeconds = 10;
            if (!managedChannel.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                throw new RuntimeException("Channel not shutdown after " + waitSeconds + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static JSONObject getBenchmarkObject(final File dir, String inputFilename) throws IOException, ParseException {
        if (!inputFilename.startsWith(File.separator)) {
            inputFilename = dir.getAbsolutePath() + File.separator + inputFilename;
        }

        // get the JSON file root object as a map of column names to JSON objects
        final JSONObject jsonMap = (JSONObject) new JSONParser().parse(new FileReader(inputFilename));
        return jsonMap;
    }

    static void runBenchmark(
            final int iter, final int nIter, final String benchTitle,
            final String extraDescription,
            final Supplier<ConsoleSession> console, final File baseDir, final JSONObject jsonMap) {
        final Map<String, Object> documentDictionary = (Map<String, Object>) jsonMap;
        final ArrayList<Object> statements = (ArrayList<Object>) documentDictionary.get("statements");

        // get our console and a session within it
        final LiveVariablesTracker varTracker = new LiveVariablesTracker();
        // for each statement ...
        int statementNo = 0;
        System.out.printf("Starting iteration %d of %d for \"%s\"\n", iter + 1, nIter, benchTitle);

        try {
            // really we'd want escape python, but this should do
            final String titleSet = "title_string = \"" + StringEscapeUtils.escapeJava(benchTitle) + "\"\n";
            if (extraDescription == null) {
                console.get().executeCode("extra_description = None\n" + titleSet);
            } else {
                console.get().executeCode("extra_description = \"" + StringEscapeUtils.escapeJava(extraDescription) + "\"\n" + titleSet);
            }
        } catch (Exception ex) {
            System.err.printf("Execution of description setup failed: %s\n", ex.getMessage());
            System.exit(1);
            // keep the compiler happy.
            throw new IllegalStateException();
        }

        for (final Object statementObject : statements) {
            ++statementNo;
            final Map<String, Object> statementDefinitionDictionary = (Map<String, Object>) statementObject;

            final String title = (String) statementDefinitionDictionary.get("title");
            if (title == null) {
                throw new IllegalArgumentException(
                        "statement number " + statementNo + " should include a \"title\" element.");
            }
            final Object text = statementDefinitionDictionary.get("text");
            final String statement;
            if (text == null) {
                final String statementFilename = (String) statementDefinitionDictionary.get("file");
                if (statementFilename == null) {
                    throw new IllegalArgumentException(
                            "statement number " + statementNo + " should include either \"text\" or \"file\" element.");
                }
                final File statementFile = Utils.locateFile(baseDir, statementFilename);
                try {
                    statement = Files.lines(statementFile.toPath()).collect(Collectors.joining("\n")) + "\n";
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Error while trying to read statement file \"" +
                                    statementFile.getAbsolutePath() +
                                    "\" for statement number " + statementNo, e);
                }
            } else {
                if (text instanceof String) {
                    statement = (String) text;
                } else if (text instanceof ArrayList){
                    final ArrayList<Object> lines = (ArrayList<Object>) text;
                    final StringBuilder sb = new StringBuilder();
                    for (Object line : lines) {
                        sb.append(line.toString()).append('\n');
                    }
                    statement = sb.toString();
                } else {
                    throw new IllegalArgumentException(
                            "element \"text\" has the wrong type, it should be either string or array of strings");
                }
            }
            boolean isTimed = ((Long) statementDefinitionDictionary.get("timed")) != 0;

            Stopwatch sw = isTimed ? Stopwatch.createStarted() : null;

            // actually execute
            final Changes changes;
            try {
                changes = console.get().executeCode(statement);
            } catch (Exception ex) {
                System.err.printf("Execution of \"%s\" failed: %s\n", title, ex);
                System.exit(1);
                // keep the compiler happy.
                throw new IllegalStateException();
            }
            varTracker.update(changes);

            final Optional<String> errorMessageOptional = changes.errorMessage();
            errorMessageOptional.ifPresent(s -> {
                System.err.printf("Execution of \"%s\" failed with error:\n%s\n", title, s);
                System.exit(1);
            });

            // optionally time ...
            if (sw != null) {
                sw.stop();
                System.out.printf("\"%s\": Execution as seen from client side took %d milliseconds\n",
                        title,
                        sw.elapsed(TimeUnit.MILLISECONDS));
            }

            if (!TERSE) {
                System.out.printf("\"%s\": Executed, changes: %s", title, toPrettyString(changes));
            }
            System.out.flush();
        }
        if (!SKIP_CLEANUP) {
            final Changes changes;
            try {
                changes = console.get().executeCode(varTracker.generateCleanupPythonStatement());
            } catch (Exception ex) {
                System.err.printf("Execution of final clean up variables phase failed: %s\n", ex);
                System.exit(1);
                // keep the compiler happy.
                throw new IllegalStateException();
            }
            varTracker.update(changes);
            if (!varTracker.isEmpty()) {
                System.err.printf("Cleanup failed to remove all variables: %s\n", varTracker);
                System.exit(1);
            }
            System.out.printf("Executed cleanup\n");
            System.out.printf("Finished iteration %d of %d for \"%s\"\n", iter + 1, nIter, benchTitle);
        }
    }

    private static ArrayList<Object> getBenchmarks(final File jobFile) throws IOException, ParseException {

        JSONObject jsonMap = (JSONObject) new JSONParser().parse(new FileReader(jobFile));
        Map<String, Object> documentDictionary = (Map<String, Object>) jsonMap;
        ArrayList<Object> benchmarks = (ArrayList<Object>) documentDictionary.get("benchmarks");

        return benchmarks;
    }

    private static String maybeMakeRelativePath(final String fn) {
        if (!fn.startsWith(File.separator) && JOBS_PREFIX_PATH != null) {
            return JOBS_PREFIX_PATH + File.separator + fn;
        }
        return fn;
    }

    private static final String me = BencherApp.class.getSimpleName();

    private static void usage() {
        System.err.println("Usage: " + me + " [-n iterations] output_prefix_path job.json [job.json...]");
        System.exit(1);
    }

    // Create a session and console on demand.
    private static class SessionAndConsoleHolder implements AutoCloseable, Supplier<ConsoleSession> {
        private ScheduledExecutorService scheduler;
        private ManagedChannel managedChannel;
        private Session session;
        private ConsoleSession console;

        @Override
        public void close() {
            console.close();
            session.close();
            shutdown(scheduler, managedChannel);
        }

        private void make() {
            scheduler = Executors.newScheduledThreadPool(4);
            final ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forTarget(DH_ENDPOINT);
            channelBuilder.usePlaintext();
            // channelBuilder.useTransportSecurity();
            channelBuilder.userAgent("DHMark");
            managedChannel = channelBuilder.build();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(scheduler, managedChannel)));
            session = getSession(scheduler, managedChannel);
            try {
                console = session.console("python").get();
            } catch (Exception ex) {
                System.err.printf(me + ": Failed to create console session for DHC: %s, aborting\n", ex);
                System.exit(1);
            }
        }

        private synchronized ConsoleSession maybeMake() {
            if (console == null) {
                make();
            }
            return console;
        }

        @Override
        public ConsoleSession get() {
            return maybeMake();
        }
    }

    public static void main(String[] args) {
        int iterations = 1;
        String extraDescription = null;
        final String outputPrefixPath;
        final File[] jobFiles;

        int argn = 0;
        while (argn < args.length && args[argn].startsWith("-")) {
            final String arg = args[argn++];
            if ("-n".equals(arg)) {
                try {
                    iterations = Integer.parseInt(args[argn++]);
                } catch (NumberFormatException ex) {
                    System.err.printf("%s: '%s' is not a valid number of iterations.\n", me, args[1]);
                    usage();
                    // keep the compiler happy
                    throw new IllegalStateException();
                }
            }
            else if ("-d".equals(arg)) {
                extraDescription = args[argn++];
            }
        }

        if (args.length - argn < 2) {
            usage();
        }
        outputPrefixPath = args[argn++];
        jobFiles = new File[args.length - argn];
        for (int ii = 0; argn < args.length; ++ii) {
            jobFiles[ii] = validate(maybeMakeRelativePath(args[argn++]));
        }

        try (SessionAndConsoleHolder consoleHolder = new SessionAndConsoleHolder()) {
            for (final File jobFile : jobFiles) {
                run(consoleHolder, outputPrefixPath, jobFile, iterations, extraDescription);
            }
        }
    }

    private static File validate(final String jobFilename) {
        final File jobFile = new File(jobFilename);
        if (!jobFile.exists()) {
            System.err.printf(me + ": job file \"%s\" doesn't exist, aborting.\n", jobFilename);
            System.exit(1);
        }
        if (!jobFile.canRead()) {
            System.err.printf(me + ": job file \"%s\" it not readable, check permissions, aborting.\n", jobFilename);
            System.exit(1);
        }
        return jobFile;
    }

    private static void run(final Supplier<ConsoleSession> console, final String outputPrefixPath, final File jobFile, final int iterations, final String extraDescription) {
        final File inputFileDir = jobFile.getParentFile();

        // open and read the definition file to an array of definition objects
        ArrayList<Object> benchmarks = null;
        try {
            benchmarks = getBenchmarks(jobFile);
        } catch (FileNotFoundException ex) {
            System.err.printf(me + ": Couldn't find file \"%s\": %s.\n", jobFile.getAbsolutePath(), ex);
            System.exit(1);
        } catch (IOException ex) {
            System.err.printf(me + ": Couldn't read file \"%s\": %s.\n", jobFile.getAbsolutePath(), ex);
            System.exit(1);
        } catch (ParseException ex) {
            System.err.printf(me + ": Couldn't parse file \"%s\": %s.\n", jobFile.getAbsolutePath(), ex);
            System.exit(1);
        }

        // for each of the definition objects, run the benchmark!
        int benchmarkNo = 0;
        for (final Object bench : benchmarks) {
            ++benchmarkNo;
            final Map<String, Object> benchmarkDefinition = (Map<String, Object>) bench;

            final String title = (String) benchmarkDefinition.get("title");
            if (title == null) {
                System.err.println(me + ": benchmark number " + benchmarkNo + " is missing a \"title\" element");
            }
            ArrayList<String> generatorFilenames = (ArrayList<String>) benchmarkDefinition.get("generator_files");
            for (int i = 0; i < generatorFilenames.size(); ++i) {
                generatorFilenames.set(i, generatorFilenames.get(i));
            }

            System.out.printf("Starting for benchmark name \"%s\" from file \"%s\"\n", title, jobFile.getAbsoluteFile());

            // generate data, then run the benchmark script
            for (final String generatorFilename : generatorFilenames) {
                try {
                    DataGen.generateData(outputPrefixPath, inputFileDir, generatorFilename);
                } catch (IOException ex) {
                    System.err.printf(me + ": Couldn't read generator file \"%s\": %s\n", generatorFilename, ex);
                    System.exit(1);
                } catch (ParseException ex) {
                    System.err.printf(me  + ": Couldn't parse generator file \"%s\": %s\n", generatorFilename, ex);
                    System.exit(1);
                }
            }

            if (GENERATE_ONLY) {
                System.out.printf("Generate only requested, not running benchmark \"%s\".\n", title);
                continue;
            }
            final JSONObject benchmarkObject;
            final String benchFilename = (String) benchmarkDefinition.get("benchmark_file");
            try {
                if (benchFilename != null) {
                    benchmarkObject = getBenchmarkObject(inputFileDir, benchFilename);
                } else {
                    benchmarkObject = (JSONObject) benchmarkDefinition.get("benchmark");
                    if (benchmarkObject == null) {
                        System.err.printf(me + ": There is no \"benchmark_file\" or \"benchmark\" definition in \"%s\".\n",
                                jobFile.getAbsolutePath());
                        System.exit(1);
                    }
                }
                for (int iteration = 0; iteration < iterations; ++iteration) {
                    runBenchmark(iteration, iterations, title, extraDescription, console, inputFileDir, benchmarkObject);
                }
            } catch (IOException ex) {
                if (benchFilename != null) {
                    System.err.printf(me + ": Couldn't read benchmark file \"%s\": %s\n", benchFilename, ex);
                } else {
                    System.err.printf(me + ": Couldnt read benchmark: %s.\n", ex);
                }
                System.exit(1);
            } catch (ParseException ex) {
                System.err.printf(me + ": Couldn't parse benchmark file \"%s\": %s\n",
                        jobFile.getAbsolutePath(), ex);
                System.exit(1);
            } catch (Exception ex) {
                System.err.printf(me + ": Execution failed for \"%s\": %s\n",
                        title, ex);
                System.exit(1);
            }

            System.out.printf("benchmark \"%s\" completed\n\n", title);
        }
    }
}
