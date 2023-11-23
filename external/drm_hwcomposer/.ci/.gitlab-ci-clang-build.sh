#!/bin/bash

. ./.ci/.common.sh

set -xe

for source in "${BUILD_FILES[@]}"
do
    filename=$(basename -- "$source")
    $CLANG $source $INCLUDE_DIRS $CXXARGS -c -o /tmp/"${filename%.*}.o"
done
