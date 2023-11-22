#!/usr/bin/env python

import argparse
from IPython.nbformat import v3, v4

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("input", help="input python file")
    parser.add_argument("output", help="output notebook file")
    args = parser.parse_args()

with open(args.input) as fpin:
    text = fpin.read()

nbook = v3.reads_py(text)
nbook = v4.upgrade(nbook)  # Upgrade v3 to v4

jsonform = v4.writes(nbook) + "\n"
with open(args.output, "w") as fpout:
    fpout.write(jsonform)


