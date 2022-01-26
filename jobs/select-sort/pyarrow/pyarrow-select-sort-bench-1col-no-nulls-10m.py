import pyarrow.dataset as ds

def bench_definition(output_prefix_path):
    df = ds.dataset(output_prefix_path + '/data/relation-no-nulls-100m.parquet', format='parquet').to_table().to_pandas().head(10000000)
    def after():
        nonlocal df
        del df
    bench_lambda = lambda: len(df.sort_values(by=['animal_id']).index)
    bench_name = 'pyarrow-select-sort-bench-1col-no-nulls-10m'
    return (bench_name, bench_lambda, after)
