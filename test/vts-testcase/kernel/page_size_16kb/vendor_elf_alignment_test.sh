#!/bin/bash

# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Check that vendor ELF files have the required 16KB [or 64KB] load segment
# alignment on devices.

# Requirement added in U (34)
MIN_VENDOR_API_LEVEL=34

DEFAULT_VENDOR_API_LEVEL=0

# Default to 64KB max page size unless otherwise specified.
DEFAULT_MAX_PAGE_SIZE=65536

# Device is only low RAM if explicitly stated
DEFAULT_CONFIG_LOW_RAM=false

fail() { #msg
    echo "FAILED: $1"
    exit 1
}

pass() { #msg
    echo "PASSED: $1"
    exit 0
}

skip() { #msg
    echo "SKIPPED: $1"
    exit 0
}

# Skip test if vendor API level < U (34)
vendor_api_level="$(adb shell getprop ro.vendor.api_level $DEFAULT_VENDOR_API_LEVEL)"
if [ "$vendor_api_level" -lt "$MIN_VENDOR_API_LEVEL" ]; then
    skip "Vendor API level ($vendor_api_level) < Min vendor API level ($MIN_VENDOR_API_LEVEL)"
fi

# Android Go and other low RAM devices do not support larger than 4KB page size
config_low_ram="$(adb shell getprop ro.config.low_ram $DEFAULT_CONFIG_LOW_RAM)"
if [ "$config_low_ram" != "$DEFAULT_CONFIG_LOW_RAM" ]; then
    skip "Low RAM devices only support 4096 max page size"
fi

# Some devices may choose to opt out of 64KB max page size support
max_page_size="$(adb shell getprop ro.product.cpu.pagesize.max $DEFAULT_MAX_PAGE_SIZE)"
if [ $max_page_size -lt $DEFAULT_MAX_PAGE_SIZE ]; then
    skip "Device only supports $max_page_size max page size"
fi


unaligned_elfs=()

get_unaligned_elfs() {
    adb shell '
        # Find all vendor ELF files
        paths=()
        for i in `find /vendor -type f -exec file {} \; | grep ELF | awk -F: "{ print \\$1 }"`; do
            paths+=( $i )
        done

        unaligned=()
        for path in "${paths[@]}"; do
            load_alignment=$( readelf -l $path | grep LOAD | head -n1 | awk "{ print \$NF }" )

            # Require 64KB alignment for future proofing. Android uses sparse files so
            # the real disk space impact is not significant.
            if [ "$load_alignment" != "0x10000" ]; then
                unaligned+=( $path )
            fi
        done

        echo "${unaligned[@]}"'
}

print_unaligned_elfs() { # arr_unaligned_elfs
    elfs=("$@")

    echo ""
    echo "=== Unaligned vendor ELF files found ==="
    echo ""
    for elf in ${elfs[@]}; do
        echo "    $elf"
    done
    echo ""
    echo "Please rebuild the above artifacts with 64KB aligned load segments."
    echo ""
    echo "    This can be done by specifying the following linker flag:"
    echo "        -Wl,-z,max-page-size=65536"
    echo ""
    echo "This is required in devices with Vendor API Level >= $MIN_VENDOR_API_LEVEL"
    echo ""
}

# @VsrTest = 3.3-005
vendor_elf_alignment_test() {
    unaligned_elfs+=( $(get_unaligned_elfs) )
    nr_unaligned="${#unaligned_elfs[@]}"

    if [ "$nr_unaligned" == "0" ]; then
        pass "All vendor ELF files have the required load segment alignment"
    else
        print_unaligned_elfs "${unaligned_elfs[@]}"
        fail "Vendor ELF files with unaligned load segments found"
    fi
}

vendor_elf_alignment_test
