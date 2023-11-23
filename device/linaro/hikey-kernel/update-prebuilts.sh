#!/bin/bash -ex

function wget_wrapper(){
    local build_num="${1}" && shift
    local board="${1}" && shift
    local target_name="${1}" && shift
    local local_name="${1}" && shift
    wget https://ci.android.com/builds/submitted/${build_num}/kernel_${board}/latest/raw/${target_name} -O ${local_name}
}

function update_kernel_dtb_hikey960(){
    local build_num="${1}" && shift
    local kernel_version="${1}" && shift
    local board="hikey960"
    mkdir -p ${board}/${kernel_version}
    wget_wrapper ${build_num} ${board} Image.gz-dtb ${board}/${kernel_version}/Image.gz-dtb
    wget_wrapper ${build_num} ${board} hi3660-hikey960.dtb ${board}/${kernel_version}/hi3660-hikey960.dtb
    wget_wrapper ${build_num} ${board} manifest_${build_num}.xml ${board}/${kernel_version}/manifest_${build_num}-${board}.xml
}


function update_kernel_dtb_hikey(){
    local build_num="${1}" && shift
    local kernel_version="${1}" && shift
    local board="hikey"
    rm -fr ${board}/${kernel_version}
    mkdir -p ${board}/${kernel_version}
    wget_wrapper ${build_num} ${board} Image.gz-dtb ${board}/${kernel_version}/Image.gz-dtb
    wget_wrapper ${build_num} ${board} hi6220-hikey.dtb ${board}/${kernel_version}/hi6220-hikey.dtb
    wget_wrapper ${build_num} ${board} manifest_${build_num}.xml ${board}/${kernel_version}/manifest_${build_num}-${board}.xml
}

function update_kernel_dtb(){
    local build_num="${1}" && shift
    local kernel_version="${1}" && shift

#    update_kernel_dtb_hikey960 ${build_num} ${kernel_version}
    update_kernel_dtb_hikey ${build_num} ${kernel_version}
}

# https://ci.android.com/builds/branches/aosp_kernel-hikey-linaro-android-4.19/grid?
# https://ci.android.com/builds/submitted/6423191/kernel_hikey960/latest
# https://ci.android.com/builds/submitted/6423191/kernel_hikey/latest
update_kernel_dtb 6971857 4.14
update_kernel_dtb 6972652 4.19
