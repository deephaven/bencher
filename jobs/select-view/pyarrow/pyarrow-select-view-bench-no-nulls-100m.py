import pyarrow.dataset as ds

def bench_definition(output_prefix_path):
    df = ds.dataset(output_prefix_path + '/data/relation-no-nulls-100m.parquet', format='parquet').to_table().to_pandas()
    def after():
        nonlocal df
        del df
    bench_lambda = lambda: len((df['adjective_id'] * 643 + df['animal_id']).to_frame().index)
    bench_name = 'pyarrow-select-view-bench-no-nulls-100m'
    return (bench_name, bench_lambda, after)
