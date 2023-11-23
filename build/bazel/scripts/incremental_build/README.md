# How to Use

The most basic invocation, e.g. `incremental_build.sh -- libc`, is logically
equivalent to

1. running `m --skip-soong-tests libc` and then
2. parsing `$OUTDIR/soong_metrics` and `$OUTDIR/bp2build_metrics.pb` files
3. Adding timing-related metrics from those files
   into `out/timing_logs/metrics.csv`
4. repeat 1-3 for each CUJ

CUJs are defined in `cuj_catalog.py`
Each row in `metrics.csv` has the timings of various "phases" of a build.

Try `incremental_build.sh --help` and `canoncial_perf.sh --help` for help on
usage.
