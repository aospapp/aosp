#!/bin/bash

# Checks that, when we define a local library, we use it everywhere

pushd "$(dirname "$0")" >/dev/null 2>&1
base="$(pwd)"
popd >/dev/null 2>&1

local_libraries=( \
  $(find "${base}"/x86_64-linux-gnu/bin \
         "${base}"/aarch64-linux-gnu/bin \
         -type f -print)
)

exit_code=0

for check_links_for in "${local_libraries[@]}"; do
  library_stub="$(basename "${check_links_for}")"
  library_stub="${library_stub/.*/}"
  for library_to_check in "${local_libraries[@]}"; do
    bad_links="$(ldd "${library_to_check}" | grep "${library_stub}" | grep -v "${base}" )"
    if [[ -n "${bad_links}" ]]; then
      echo ${library_to_check} has bad link to ${library_stub}: ${bad_links}
      exit_code=2
    fi
  done
done
exit ${exit_code}
