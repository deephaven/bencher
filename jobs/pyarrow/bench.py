import csv
import datetime
import pytz
import os
import sys
import time
import uuid

process_uuid =  uuid.uuid4().hex

def run_bench(bench_name : str, bench_fun, after_fun, output_path : str):
    start_time = time.time()
    count = bench_fun()
    end_time = time.time()
    after_fun()
    print(count)
    elapsed_seconds = end_time - start_time
    print(elapsed_seconds)
    timestamp_utc = datetime.datetime.fromtimestamp(end_time, pytz.UTC)
    timestamp_nyc = datetime.datetime.fromtimestamp(end_time, pytz.timezone('America/New_York'))
    header = [ 'bench_name', 'extra_description', 'timestamp_nyc', 'timestamp_utc', 'process_unique_id', 'gc_seconds', 'elapsed_seconds' , 'rows_per_second' ]
    fields = [ bench_name, "", timestamp_nyc, timestamp_utc, process_uuid, "", elapsed_seconds, "" ]
    results_file = output_path + '/pyarrow-bench-results.csv'
    need_header = not os.path.exists(results_file)
    with open(results_file, 'a') as file:
        writer = csv.writer(file)
        if need_header:
            writer.writerow(header)
        writer.writerow(fields)
    return elapsed_seconds

def usage():
    print(f"Usage: {sys.argv[0]} [-n iterations] output_path pyarrow_benchmark.py [pyarrow-benchmark2.py ...]", file=sys.stderr)
    sys.exit(1)    

if len(sys.argv) > 1 and sys.argv[1] == '-n':
    if len(sys.argv) < 5:
        usage()
    try:
        iterations = int(sys.argv[2])
    except ValueError:
        print(f"{sys.argv[0]}: '{sys,argv[2]}' is not a valid number of iterations.")
        usage()
    args=sys.argv
    output_path = sys.argv[3]
    benchmarks = sys.argv[4:]
else:
    iterations = 1
    if len(sys.argv) < 3:
        usage()
    output_path = sys.argv[1]
    benchmarks = sys.argv[2:]

for benchmark in benchmarks:
    for iteration in range(iterations):
        exec(open(benchmark).read())
        (bench_name, bench_fun, after_fun) = bench_definition(output_path)
        print(f"Running {benchmark}...")
        elapsed_seconds = run_bench(bench_name, bench_fun, after_fun, output_path)
        print(f"Ran {benchmark} in {elapsed_seconds} seconds ({iteration + 1} of {iterations}).")
