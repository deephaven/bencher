# Bencher #

Bencher is a tool, written in Java, to generate data files and run benchmarks on the Deephaven platofrm.

The tool consumes JSON files that define **benchmark jobs**. The **benchmark definition file** contains an array of JSON objects that reference generation files and steps files.

**Generation files** are also JSON. The generation files give a list of columns to produce sample data. **Column definitions** can generate random or sequential integers over a given range, or use an external list of strings as a source. By manipulating the properties on each column definition, it's easy to generate interesting and predictable data sets of any size.

**Steps files** are JSON, too. As the name implies, they enumerate Python commands to be sent to a Deephaven instance. The steps can be timed or not.

A typical benchmark job will run a generation step to create sample data, then run a steps file to execute Deephaven commands against those data files and time their execution. With this arrangement, a myriad of interesting benchmark definitions can be created just by writing JSON files, and without touching any of the benchmark engine code.

## Benchmark Jobs files ##

A simple benchmark job file is given here:

	{
	  "benchmarks": [
	    {
	      "title": "Join 1m animals and adjectives",
	      "generator_file": "join-1m.json",
	      "benchmark_file": "animals-joiner.json",
	    }
	  ]
	}


the `"benchmarks"` object is required; it's an array of benchmark job definitions.

Each definition includes a `title`, which names the benchmark and is used to refer to the job in progress messages generated by the tool.

The definition includes a `"generator_file"` string, which can include a relative or absolute path. The value gives the location of a generator file for this benchmark job.  When relative, the generator files are looked up in the same
directory where the benchmark job file lives, or its parent; the parent directory makes a handy way to share generator files across sets of jobs stored in separate subdirectories.  The code includes a `json` directory with jobs defined
in this way.

Finally, there's a `"benchmark_file"` string which can also include a relative or absolute path. The value indicates the location of the steps file.
Instead of providing  `"benchmark_file"`, it is possible to specify `"benchmark"` with the equivalent content inlined with the job file.

The tool reads the job file and executes each benchmark job in order. It runes the generator file, then runs the benchmark file.

## Generation Files ##

Generation files define a list of columns. One list of columns must establish itself as a "driver". The driver column will be of predictable and finite length and indicates how many rows will be generated in the sample data.

All generation files follow the same format. They have a mandatory map named `"columns"`, which maps a column name to a list of attributes describing that column.

They also include a `"format"` string, which is `"CSV"` or `"PARQUET"` to determine the output file type. The output file matches the name of the generator file, with a suffix according to format, either `.parquet` or `.csv`.

Note that output is not generated if the output file already exists and its last modification time is more recent than the last modification time of the generator file; generation can be forced to always happen by setting the java property
`force.generation` to `True`.

### From external data ###

This generation file called `animals.json` defines two columns, one of which uses an external file as a driver:

	{
	  "format": "CSV",
	  "columns" : {
	    "animal_id" :
	    {
	      "generation_type": "id",
	      "type": "INT32",
	      "increment": "INCREASING",
	      "start_id": "1",
	      "percent_null": "0"
	    },
	    "animal_name" :
	    {
	      "generation_type": "file",
	      "column_name": "animal_name",
	      "type": "STRING",
	      "source_file": "sets/animals.txt"
	    }
	  }
	}

From the `"format"` string and the generator name, we can tell that this JSON will cause the generator to produce a CSV file named `"animals.csv"`.  If the java property `ouput.prefix.path` is set, it is used as the directory where the output files will be written,
otherwise the current working directory is used.

The `"animal_id"` column object describes a column that has an `INT32` datatype. The value of the column starts at 1 and increase monotonically, and never includes a null value. Since we don't see any limit here, we can assume that this column is not a driver and will simply generate the next integer for any row that the driving column does produce.

The `"animal_name"` column has the `generation_type` of `"file"`, which means that it will be reading fro ma file. Sure enough, it produces a `STRING` type for its output, and the file it will read is at `sets/animals.txt`. This column is a driver, since the file must end (some day!).

When this generator runs, each line read from the file will be placed in the `animal_name` column, along with the next integer generated for the `animal_id` column.

If the `animals.txt` file contains a list of names of animals on each line, maybe the generated `animals.csv` file starts with a few lines like this:

	animal_id,animal_name
	1,monkey
	2,ray
	3,owl
	4,zebra
	5,dolphin
	6,walkingstick
	7,wombat
	8,dromedary

### Randomized Data ###

It's possible to generate data that's completely random, though repeatable. Let's consider this generator file called `join-10m.json`:

	{
	  "format": "CSV",
	  "columns" : {
	    "Values" :
	    {
	      "generation_type": "full_range",
	      "type": "INT32",
	      "order": "Increasing",
	      "seed": "8675309",
	      "range_start": "1",
	      "range_stop": "10000000",
	      "percent_null": "0"
	    },
	    "adjective_id" :
	    {
	      "generation_type": "random",
	      "lower_bound": "1",
	      "upper_bound": "650",
	      "type": "INT32",
	      "seed": "8675309",
	      "percent_null": "5"
	    },
	    "animal_id" :
	    {
	      "generation_type": "random",
	      "lower_bound": "1",
	      "upper_bound": "250",
	      "type": "INT32",
	      "seed": "1239015897",
	      "percent_null": "5"
	    }
	  }
	}

The `format` string indicate we'll create a CSV file, this time named "join-10m.csv". 

The `values` column has a `gernation_type` of `full_range`, which means the column generates a complete, gapless range of integers. Here, the `range_start` is `1` and the `range_stop` value is `10000000`, so this column will be a driver column that produces ten million rows.

The `values` column has a `percent_null` of 0, which means that no null values will be generated. The supplied percentage is a floating-point value of percent (that is, 0.0 to 100.0). Randomly, a generated value will be considered to be nullify by rolling for that percentage -- so the percentage is a goal and not a guarantee. If a null value is discarded, it still counts against the cardinality of the generation. A value of 50.0 for `percent_null` in this file would still generate ten million rows, but about 50 percent of them would have a null value for the `values` column.

To promote repeatability, each column has a `seed` value. The seed will be used to seed a random number generator local to the column. The seed also seeds the generator for the nullness rolls. If two runs are done with the same seed, they will generate the same sequences and nullness patterns. Note that changes to the file might influence the patterns; keeping the same seed and changing `percent_null` might generate a different ordering as well as a different nullness pattern.

The `order` for the `values` column is increasing, so we know we'll get the rows numbered 1 though 10,000,000 in the output, in order.

As the `values` column drives the generation, the `adjective_id` and `animal_id` column definitions are used to generate values for two more columns. Because the `generation_type` in these columns is `random`, each one will produce a random number in a given range: between 1 and 650 inclusive for `adjective_id`, and between 1 and 250 inclusive for `animal_id`. 

The `adjective_id` and `animal_id` also have a `percent_null` value of 5, so there's a 5% chance any value in each of the columns is null.

When the generator executes against this file, the first few rows look like this:

	Values,adjective_id,animal_id
	1,113,16
	2,350,
	3,197,70
	4,298,188
	5,447,117
	6,271,148
	7,254,203
	8,20,24
	9,74,82


### The Selection Generation Type ###

The above examples use the `id`, `file,` and `full_range` generation types. Another useful `generation_type` is `shuffled`. Here is a generation file that shuffles:

	{
	    "format": "CSV",
	    "columns" : {
	        "Values" :
	        {
	            "generation_type": "full_range",
	            "type": "INT32",
	            "order": "Shuffled",
	            "seed": "8675309",
	            "range_start": "1",
	            "range_stop": "50",
	            "percent_null": "0"
	        },
	        "States" :
	        {
	            "generation_type": "selection",
	            "type": "STRING",
	            "distribution": "Normal",
	            "seed": "8675309",
	            "source_file": "state_list.txt"
	        }
	    }
	}

Here, we see a `full_range` column named `values`. This column will generate values from 1 to 50, inclusive, with no chance of nullness. However, it has an order of `shuffled`, so those values will appear in a random order.

The `States` column takes its input from the `state_list.txt` source file. The file has one value on each line. The generator uses a `generation_type` of `selection`, which is *not* a driver. Instead, the column definition selects one of the set of values from the lines in the file. It uses the random number seed given, and uses a normal distribution over the file's entries. Thus, entries toward the middle of the file are more likely to be chosen compared to values near the beginning or the end. This arrangement is handy for files that are ordered.

Another `selection` type is `indicated`, which is randomly uniform over the set of data in the file.

The `selection` column can also be `indicated`. In this case, the file must have two values on each line, separated by a comma. The first value is the text that the column should generate. The second value is an integer. The integers on all lines are summed to form a total. The chances of a particular value from the set being generated are given by the indicated value on that line divided by the total value.

This example file:

	First Quarter,25
	Second Quarter,25
	all Half,50

has a total of 100 for the generation values. The chances of `First Quarter` or `Second Quarter` being chosen are each 25/100, and of `all Half` being chosen is 50/100.


## Benchmark Steps Files ##

An example benchmark steps file is given here:

	{
	  "statements" : [
	    {
	      "title" : "imports",
	      "text" : "from deephaven.TableTools import readCsv",
	      "timed" : 0
	    },
	
	    {
	      "title" : "load animals",
	      "text" : "animals = readCsv('/data/animals.csv')",
	      "timed" : 1
	    },
	
	    {
	      "title" : "load adjectives",
	      "text" : "adjectives = readCsv('/data/adjectives.csv')",
	      "timed" : 1
	    },
	
	    {
	      "title" : "load relation table",
	      "text" : "relation = readCsv('/data/join.csv')",
	      "timed" : 1
	    },
	
	    {
	      "title" : "perform join",
	      "text" : "result = relation.join(adjectives, 'adjective_id').join(animals, 'animal_id').view('Values', 'adjective_name', 'animal_name')",
	      "timed" : 1
	    }
	
	  ]
	}

The file contains a mandatory "statements" array. The array contains an ordered list of steps to execute in the benchmark. Each step is simply a command to send to Deephaven, in sequence.

The statement might be timed; if so, the "timed" value is 1. Otherwise, the timed value is "0". Un-timed steps are intended to allow setup and tear-down for each job such that the duration of the steps does not clutter the output of the benchmark job in total.

When a step runs, its title and the execution duration of the step are output. The title string simply identifies the step, while the text indicates the code in the step to be sent to Deephaven.

Appropriate escaping must be done for the JSON format. The above script executes an import command without timing it:

	from deephaven.TableTools import readCsv

then executes four more statements, each of which is timed:

	animals = readCsv("/data/animals.csv")
	adjectives = readCsv("/data/adjectives.csv")
	relation = readCsv("/data/join.csv")
	result = relation.join(adjectives, "adjective_id").join(animals, "animal_id").view("Values", "adjective_name", "animal_name")

The script imports three different CSV files, showing the timing for each along the way. Finally, it joins the three tables together to produce a fourth, again showing the timing of the operation.

There are three possible ways to provide the code for a statement:

- In a `"text"` element that corresponds to a single string for a single line of code to execute.
- In a `"text"` element that corresponds to an array of strins, each with a line of code to execute.
- In a `"file"` element that indicates a python source filename with code to be executed line by line.
  If the file name is relative, it is looked up in the same directory or the parent directory relative to the file where it is specified.

# About Data Types #

In CSV files, all data generated is just a string. Nulls are represented by an empty field. For example, `1,,3` has three fields: a `1`, a null, and a `3`.

Parquet files use the indicated data type in the generator file as a definition for the message data. Thus, the types supported here are limited to the types supported by Parquet. Care must be taken to get correct typing for the desired benchmark job.

# Limitations #

Software is often like a late-spring ski report: bare spots and limitations do exist.

- Data is loaded into memory from files, and large files will take more memory.
- Sequences which are shuffled will produce an ordered list in memory, then shuffle that list. For large lists, this uses a lot of memory and takes some time. The time and space complexities are linear, but are noticable after 100 million integers or so.
- Parquet output file is not named correctly.


## Missing Features ##

Several enhancements are foreseeable:

- Ability to run multiple generator files in a single benchmark job
- Avoid recreating generated files if they already exist; just get on with the benchmark
- Find a way to template files, but vary lengths or output file names
- Better output logging
- Restart (or reset) the Deephaven instance between runs
- Execute arbitrary shell commands at any point in the benchmark job; for cleanup or preparation
- Specify the address and port of the Deephaven instance
- Support groovy
- Extract stats from the Deephaven instances about the run
- Check results for correctness, even superficially
- Configure Parquet compression type
- Configure Parquet partitioning


