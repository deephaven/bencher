package io.deephaven.datagen;

import org.json.simple.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;

public class SelectionGenerator extends DataGenerator {

    DistributionType distribution;
    PercentNullManager percent_null;
    String fileName;
    ArrayList<String> strings;
    Random pnrg;
    GeneratorStringIterator stringIterator;
    GeneratorObjectIterator objectIterator;

    enum DistributionType {
        NORMAL,
        UNIFORM,
        INDICATED,
    }

    private SelectionGenerator(ColumnType columnType, String fileName, DistributionType distribution, long seed, double percent_null) {

        this.distribution = distribution;
        this.columnType = columnType;

        this.fileName = fileName;

        this.percent_null = PercentNullManager.fromPercentage(percent_null, seed);

        pnrg = new Random(seed);

        initialize();

        stringIterator = new GeneratorStringIterator();
        objectIterator = new GeneratorObjectIterator();
    }

    private void initialize() {

        strings = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            while (br.ready()) {
                strings.add(br.readLine());
            }
        } catch (FileNotFoundException e) {
            System.err.printf("File %s not found", fileName);
            throw new IllegalStateException();
        } catch (IOException e) {
            System.err.printf("IOException while reading %s: %s", fileName, e.getMessage());
            throw new IllegalStateException();
        }
    }

    @Override
    int getCapacity() {
        return -1;
    }

    static DataGenerator fromJson(String fieldName, JSONObject jo) {

        ColumnType columnType = DataGenerator.columnTypeFromJson(jo);

        SelectionGenerator.DistributionType distributionType = null;

        String distribution = (String) jo.get("distribution");
        if (distribution == null) {
            distributionType = SelectionGenerator.DistributionType.UNIFORM;
        } else {
            switch (distribution.toUpperCase(Locale.ROOT)) {
                case "UNIFORM":
                    distributionType = SelectionGenerator.DistributionType.UNIFORM;
                    break;

                case "NORMAL":
                    distributionType = SelectionGenerator.DistributionType.NORMAL;
                    break;

                case "INDICATED":
                    distributionType = SelectionGenerator.DistributionType.INDICATED;
                    break;

                default:
                    throw new IllegalArgumentException(String.format("Distribution must be one of Normal, Uniform, or Indicated; found \"%s\"", distribution));
            }
        }

        String seedValue = (String) jo.get("seed");
        if (seedValue == null) {
            throw new IllegalArgumentException(String.format("%s: Seed must be provided", fieldName));
        }
        long seed = Long.parseLong(seedValue);

        String fileName = (String) jo.get("source_file");
        if (fileName == null) {
            throw new IllegalArgumentException(String.format("%s: source_file must be specified", fieldName));
        }

        double percent_null = PercentNullManager.parseJson(fieldName, jo);

        SelectionGenerator sg = new SelectionGenerator(columnType, fileName, distributionType, seed, percent_null);
        return sg;
    }

    @Override
    public Iterator<String> getStringIterator() {
        return stringIterator;
    }

    @Override
    public Iterator<Object> getObjectIterator() {
        return objectIterator;
    }

    private int getNextNormalIndex() {

        double dev = strings.size() / 0.15;
        double mean = strings.size() / 2.0;

        int idx;
        do {
            double val = pnrg.nextGaussian() * dev + mean;
            idx = (int) Math.round(val);
        } while (idx < 0 || idx >= strings.size() );

        return idx;

    }

    private int getNextIndex() {

        int result = 0;

        switch (distribution) {
            case NORMAL:
                result = getNextNormalIndex();
                break;

            case UNIFORM:
                result = pnrg.nextInt(strings.size());
                break;

            case INDICATED:
                throw new UnsupportedOperationException("Indicated distribution is not yet supported");

            default:

                throw new UnsupportedOperationException("Internal error: unknown distribution type");
        }

        return result;
    }

    private class GeneratorStringIterator implements Iterator<String> {

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public String next() {
            // generate something
            int idx = getNextIndex();

            // but possibly discard it
            if (percent_null != null && percent_null.test()) {
                return "";
            }

            return strings.get(idx);
        }
    }


    private class GeneratorObjectIterator implements Iterator<Object> {

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public String next() {
            // generate something
            int idx = getNextIndex();

            // but possibly discard it
            if (percent_null != null && percent_null.test()) {
                return "";
            }

            return strings.get(idx);
        }
    }
}
