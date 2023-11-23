#!/bin/bash

# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

set -e

readonly _FLASHPROTECT_OUTPUT_HW_AND_SW_WRITE_PROTECT_ENABLED="$(cat <<SETVAR
Flash protect flags: 0x0000000b wp_gpio_asserted ro_at_boot ro_now
Valid flags:         0x0000003f wp_gpio_asserted ro_at_boot ro_now all_now STUCK INCONSISTENT
Writable flags:      0x00000004 all_now
SETVAR
)"

readonly _FW_NAMES="rb0 rb1 rb9 dev"
readonly _FW_TYPES="ro rw"

flash_rw_firmware() {
  local fw_file="${1}"
  check_file_exists "${fw_file}"
  flashrom --fast-verify -V -p ec:type=fp -i EC_RW -w "${fw_file}"
}

get_ectool_output_val() {
  local key="${1}"
  local ectool_output="${2}"
  echo "${ectool_output}" | grep "${key}" | sed "s#${key}:[[:space:]]*\.*##"
}

run_ectool_cmd() {
  local ectool_output
  ectool_output="$(ectool --name=cros_fp "${@}")"
  if [[ $? -ne 0 ]]; then
    echo "Failed to run ectool cmd: ${@}"
    exit 1
  fi
  echo "${ectool_output}"
}

run_ectool_cmd_ignoring_error() {
  ectool --name=cros_fp "${@}" || true
}

add_entropy() {
  run_ectool_cmd "addentropy" "${@}"
}

reboot_ec() {
  # TODO(b/116396469): The reboot_ec command returns an error even on success.
  run_ectool_cmd_ignoring_error "reboot_ec"
  sleep 2
}

reboot_ec_to_ro() {
  # TODO(b/116396469): The reboot_ec command returns an error even on success.
  run_ectool_cmd_ignoring_error "reboot_ec"
  sleep 0.5
  run_ectool_cmd "rwsigaction" "abort"
  sleep 2
}

read_from_flash() {
  local output_file="${1}"
  run_ectool_cmd "flashread" "0xe0000" "0x1000" "${output_file}"
}

get_running_firmware_copy() {
  local ectool_output
  ectool_output="$(run_ectool_cmd "version")"
  get_ectool_output_val "Firmware copy" "${ectool_output}"
}

_get_firmware_version() {
  local fw_type="${1}"
  local ectool_output
  ectool_output="$(run_ectool_cmd "version")"
  get_ectool_output_val "${fw_type} version" "${ectool_output}"
}

get_rw_firmware_version() {
  _get_firmware_version "RW"
}

get_ro_firmware_version() {
  _get_firmware_version "RO"
}

_get_rollback_info() {
  # TODO(crbug.com/924283): rollbackinfo command always returns exit code 1.
  run_ectool_cmd_ignoring_error "rollbackinfo"
}

get_rollback_block_id() {
  get_ectool_output_val "Rollback block id" "$(_get_rollback_info)"
}

get_rollback_min_version() {
  get_ectool_output_val "Rollback min version" "$(_get_rollback_info)"
}

get_rollback_rw_version() {
  get_ectool_output_val "RW rollback version" "$(_get_rollback_info)"
}

_check_rollback_matches() {
  local rb_type="${1}"
  local expected="${2}"
  local rb_info
  rb_info="$(get_rollback_${rb_type})"
  if [[ "${rb_info}" != "${expected}" ]]; then
    echo "Rollback ${rb_type} does not match, expected: ${expected}, actual: ${rb_info}"
    exit 1
  fi
}

check_rollback_block_id_matches() {
  _check_rollback_matches "block_id" "${1}"
}

check_rollback_min_version_matches() {
  _check_rollback_matches "min_version" "${1}"
}

check_rollback_rw_version_matches() {
  _check_rollback_matches "rw_version" "${1}"
}

check_is_rollback_set_to_initial_val() {
  check_rollback_block_id_matches "1"
  check_rollback_min_version_matches "0"
  check_rollback_rw_version_matches "0"
}

check_file_exists() {
  if [[ ! -f "${1}" ]]; then
    echo "Cannot find file: ${1}"
    exit 1
  fi
}

check_running_rw_firmware() {
  local fw_copy
  fw_copy="$(get_running_firmware_copy)"
  if [[ "${fw_copy}" != "RW" ]]; then
    echo "Not running RW copy of firmware"
    exit 1
  fi
}

check_running_ro_firmware() {
  local fw_copy
  fw_copy="$(get_running_firmware_copy)"
  if [[ "${fw_copy}" != "RO" ]]; then
    echo "Not running RO copy of firmware"
    exit 1
  fi
}

_check_has_mp_firmware_type() {
  local fw_type="${1}"
  local fw_version
  fw_version="$(get_${fw_type}_firmware_version)"

  # The MP version string is not "special", so we compare against all the
  # "special" version strings and only succeed if it doesn't match any of them.
  for fw_name in ${_FW_NAMES}; do
    if [[ "${fw_version}" == *.${fw_name} ]]; then
      echo "Not running MP ${fw_type} firmware: ${fw_version}"
      exit 1
    fi
  done
}

check_has_mp_ro_firmware() {
  _check_has_mp_firmware_type "ro"
}

check_has_mp_rw_firmware() {
  _check_has_mp_firmware_type "rw"
}

# generate check_has_dev_rw_firmware/check_has_dev_ro_firmware, etc
for fw_name in ${_FW_NAMES}; do
  for fw_type in ${_FW_TYPES}; do
    eval "
      check_has_${fw_name}_${fw_type}_firmware() {
        local fw_version
        fw_version=\"\$(get_${fw_type}_firmware_version)\"
        if [[ \"\${fw_version}\" != *.${fw_name} ]]; then
          echo \"Not running ${fw_name} ${fw_type} firmware: \${fw_version}\"
          exit 1
        fi
      }
    "
  done
done

get_flashprotect_status() {
  run_ectool_cmd "flashprotect"
}

enable_sw_write_protect() {
  # TODO(b/116396469): The reboot_ec command returns an error even on success.
  run_ectool_cmd_ignoring_error "flashprotect" "enable"

  # TODO(b/116396469): "flashprotect enable" command is slow, so wait for
  # it to complete before attempting to reboot.
  sleep 2

  reboot_ec
}

disable_sw_write_protect() {
  run_ectool_cmd "flashprotect" "disable"
}

check_hw_and_sw_write_protect_enabled() {
  local output
  output="$(get_flashprotect_status)"

  if [[ "${output}" != "${_FLASHPROTECT_OUTPUT_HW_AND_SW_WRITE_PROTECT_ENABLED}" ]]; then
    echo "Incorrect flashprotect state: ${output}"
    echo "Make sure HW write protect is enabled (wp_gpio_asserted)"
    exit 1
  fi
}

check_fingerprint_task_is_running() {
  run_ectool_cmd "fpinfo"
}

check_fingerprint_task_is_not_running() {
  if (check_fingerprint_task_is_running) ; then
    echo "Fingerprint task should not be running"
    exit 1
  fi
}