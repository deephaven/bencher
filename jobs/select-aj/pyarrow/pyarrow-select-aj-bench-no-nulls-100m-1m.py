import pyarrow.dataset as ds
import pandas as pd

def bench_definition(output_prefix_path):
    workqueue = ds.dataset(output_prefix_path + '/data/workqueue-no-nulls-100m.parquet', format='parquet').to_table().to_pandas()
    auditqueue = ds.dataset(output_prefix_path + '/data/auditqueue-no-nulls-1m.parquet', format='parquet').to_table().to_pandas()
    def after():
        nonlocal workqueue, auditqueue
        del workqueue
        del auditqueue
    bench_lambda = lambda: len(pd.merge_asof(left=workqueue, right=auditqueue, on='timestamp', by='user_id').index)
    bench_name = 'pyarrow-select-aj-bench-no-nulls-100m-1m'
    return (bench_name, bench_lambda, after)
