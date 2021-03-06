import pyarrow.dataset as ds
import time

def bench_definition(output_prefix_path):
    rel = ds.dataset(output_prefix_path + '/data/relation-no-nulls-100m.parquet', format='parquet').to_table()
    def after():
        nonlocal rel
        del rel
    def do():
        df = rel.to_pandas()
        df = df['adjective_id'] * 643 + df['animal_id']
        df = df.to_frame()
        return len(df.index)
    bench_name = 'pyarrow-view-bench-no-nulls-100m'
    return (bench_name, do, after)
