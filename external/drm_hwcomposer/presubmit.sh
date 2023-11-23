#!/bin/bash

set -e

echo "Run native build:"

./.ci/.gitlab-ci-clang-build.sh

echo "Run style check:"

./.ci/.gitlab-ci-checkcommit.sh

echo "Run coarse clang-tidy check:"

./.ci/.gitlab-ci-clang-tidy-coarse.sh

echo "Run fine clang-tidy check:"

./.ci/.gitlab-ci-clang-tidy-fine.sh

echo -e "\n\e[32m --- SUCCESS ---"
