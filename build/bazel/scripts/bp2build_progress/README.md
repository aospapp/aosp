# bp2build progress graphs

This directory contains tools to generate reports and .png graphs of the
bp2build conversion progress, for any module.

This tool relies on `json-module-graph` and `bp2build` to be buildable targets
for this branch.

## Prerequisites

* `/usr/bin/dot`: turning dot graphviz files into .pngs

Tip: `--use_queryview=true` runs `bp2build_progress.py` with queryview.

## Instructions

# Generate the report for a module, e.g. adbd

```sh
b run //build/bazel/scripts/bp2build_progress:bp2build_progress \
  -- report -m <module-name>
```

or:

```sh
b run //build/bazel/scripts/bp2build_progress:bp2build_progress \
  -- report -m <module-name> --use-queryview
```

When running in report mode, you can also write results to a proto with the flag
`--proto-file`

# Generate the report for a module, e.g. adbd

```sh
b run //build/bazel/scripts/bp2build_progress:bp2build_progress \
  -- graph -m adbd > /tmp/graph.in && \
  dot -Tpng -o /tmp/graph.png /tmp/graph.in
```

or:

```sh
b run //build/bazel/scripts/bp2build_progress:bp2build_progress \
  -- graph -m adbd --use-queryview > /tmp/graph.in && \
  dot -Tpng -o /tmp/graph.png /tmp/graph.in
```
