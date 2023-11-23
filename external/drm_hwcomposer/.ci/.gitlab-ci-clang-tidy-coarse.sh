#!/bin/bash

. ./.ci/.common.sh

TIDY_COARSE_CHECKS="-*,android-*,bugprone-*,cert-*,clang-analyzer-*,"
TIDY_COARSE_CHECKS+="cppcoreguidelines-*,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-pro-bounds-array-to-pointer-decay,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-pro-bounds-constant-array-index,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-pro-bounds-pointer-arithmetic,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-pro-type-cstyle-cast,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-pro-type-union-access,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-pro-type-vararg,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-avoid-magic-numbers,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-macro-usage,"
TIDY_COARSE_CHECKS+="-cppcoreguidelines-avoid-c-arrays,"
TIDY_COARSE_CHECKS+="google-*,"
TIDY_COARSE_CHECKS+="-google-readability-braces-around-statements,"
TIDY_COARSE_CHECKS+="-google-readability-casting,"
TIDY_COARSE_CHECKS+="misc-*,"
TIDY_COARSE_CHECKS+="modernize-*,"
TIDY_COARSE_CHECKS+="-modernize-avoid-c-arrays,"
TIDY_COARSE_CHECKS+="-modernize-use-trailing-return-type,"
TIDY_COARSE_CHECKS+="performance-*,"
TIDY_COARSE_CHECKS+="portability-*,"
TIDY_COARSE_CHECKS+="readability-*,"
TIDY_COARSE_CHECKS+="-readability-braces-around-statements,"
TIDY_COARSE_CHECKS+="-readability-convert-member-functions-to-static,"
TIDY_COARSE_CHECKS+="-readability-implicit-bool-conversion,"
TIDY_COARSE_CHECKS+="-readability-magic-numbers,"
TIDY_COARSE_CHECKS+="-readability-use-anyofallof"

TIDY_FILES=( "${BUILD_FILES[@]}" )

set -xe

for source in "${TIDY_FILES[@]}"
do
    $CLANG_TIDY $source --checks=$TIDY_COARSE_CHECKS -- -x c++ $INCLUDE_DIRS $CXXARGS
done
