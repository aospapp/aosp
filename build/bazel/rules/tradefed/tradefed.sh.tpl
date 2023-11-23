#!/bin/bash
set -e

TEST_PATH="${TEST_SRCDIR}"
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
PATH_ADDITIONS="{PATH_ADDITIONS}"

export PATH="$SCRIPT_DIR:${PATH}"
# Prepend the REMOTE_JAVA_HOME environment variable to the path to ensure
# that all Java invocations throughout the test execution flow use the same
# version.
if [ ! -z "${REMOTE_JAVA_HOME}" ]; then
  export PATH="${REMOTE_JAVA_HOME}/bin:${PATH}"
fi

exit_code_file="$(mktemp /tmp/tf-exec-XXXXXXXXXX)"

atest_tradefed.sh template/atest_local_min \
    --template:map test=atest \
    --template:map reporters="${SCRIPT_DIR}/result-reporters.xml" \
    --tests-dir "$TEST_PATH" \
    --logcat-on-failure \
    --no-enable-granular-attempts \
    --no-early-device-release \
    --skip-host-arch-check \
    --include-filter "{MODULE}" \
    --skip-loading-config-jar \
    "${ADDITIONAL_TRADEFED_OPTIONS[@]}" \
    --bazel-exit-code-result-reporter:file=${exit_code_file} \
    --bazel-xml-result-reporter:file=${XML_OUTPUT_FILE} \
    --proto-output-file="${TEST_UNDECLARED_OUTPUTS_DIR}/proto-results" \
    --log-file-path="${TEST_UNDECLARED_OUTPUTS_DIR}" \
    "$@"

# Use the TF exit code if it terminates abnormally.
tf_exit=$?
if [ ${tf_exit} -ne 0 ]
then
     echo "Tradefed command failed with exit code ${tf_exit}"
     exit ${tf_exit}
fi

# Set the exit code based on the exit code in the reporter-generated file.
exit_code=$(<${exit_code_file})
if [ $? -ne 0 ]
then
  echo "Could not read exit code file: ${exit_code_file}"
  exit 36
fi

if [ ${exit_code} -ne 0 ]
then
  echo "Test failed with exit code ${exit_code}"
  exit ${exit_code}
fi
