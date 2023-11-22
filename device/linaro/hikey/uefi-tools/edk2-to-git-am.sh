#!/bin/sh
#
# Convert one or more git patches that have had it's CR:s stripped out by SMTP
# into something th

if [ $# -lt 1 ]; then
  echo "usage: `basename $0` <filename>" >&2
  exit 1
fi

convert_file()
{
  sed -i "s/$/\r/g" "$1"
  sed -i "s:^\(---\|+++ \)\(.*\)\r$:\1\2:g" "$1"
}

while [ $# -gt 0 ]; do
  convert_file "$1"
  shift
done
