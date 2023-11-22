#!/usr/bin/env bash

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color
[ "$(uname -s)" == "Darwin" ] && { realpath(){ echo "$(cd $(dirname $1);pwd -P)/$(basename $1)"; }; }
AIDEGEN_DIR=$(dirname $(realpath $0))
ASUITE_DIR="$(dirname $AIDEGEN_DIR)"
CORE_DIR="$(dirname $ASUITE_DIR)/tradefederation/core"
ATEST_DIR="$CORE_DIR/atest"
rc_file=${AIDEGEN_DIR}/.coveragerc

function get_python_path() {
    echo "$PYTHONPATH:$CORE_DIR:$ATEST_DIR:$ASUITE_DIR"
}

function print_summary() {
    local test_results=$1
    local tmp_dir=$(mktemp -d)
    PYTHONPATH=$(get_python_path) python3 -m coverage report
    PYTHONPATH=$(get_python_path) python3 -m coverage html -d $tmp_dir --rcfile=$rc_file
    echo "coverage report available at file://${tmp_dir}/index.html"

    if [[ $test_results -eq 0 ]]; then
        echo -e "${GREEN}All unittests pass${NC}!"
    else
        echo -e "${RED}Unittest failure found${NC}!"
    fi
}

function run_unittests() {
    local specified_tests=$@
    local rc=0

    # Get all unit tests under tools/acloud.
    local all_tests=$(find $AIDEGEN_DIR -type f -name "*_unittest.py");
    local tests_to_run=$all_tests

    PYTHONPATH=$(get_python_path) python3 -m coverage erase
    for t in $tests_to_run;
    do
        if ! PYTHONPATH=$(get_python_path) python3 -m coverage run --append --rcfile=$rc_file $t; then
            rc=1
            echo -e "${RED}$t failed${NC}"
        fi
    done

    print_summary $rc
    cleanup
}

function check_env() {
    if [ -z "$ANDROID_BUILD_TOP" ]; then
        echo "Missing ANDROID_BUILD_TOP env variable. Run 'lunch' first."
        exit 1
    fi
}

function cleanup() {
    # Search for *.pyc and delete them.
    find $AIDEGEN_DIR -name "*.pyc" -exec rm -f {} \;

    # Delete the generated .coverage files too.
    find $ASUITE_DIR -name "*.coverage" -exec rm -f {} \;
}

check_env
cleanup
run_unittests "$@"
