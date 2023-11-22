#!/bin/bash

#
# Copyright 2015 The Android Open Source Project
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

args=( "$@" )
confs=( )

KERNEL_PATH=${args[0]}
OUTPUT=${args[1]}
TARGET_ARCH=${args[2]}
TARGET_CROSS_COMPILE=${args[3]}

unset "args[0]"
unset "args[1]"
unset "args[2]"
unset "args[3]"

curdir=$(pwd)

# Explicitly record the list of config files used to build .config, and
# canonicalize the path since we have to have our current directory in
# the kernel source tree.
for conf in ${args[*]} ; do
	fullpath=$conf
	if [ ${fullpath:0:1} != "/" ] ; then
		fullpath=$curdir/$fullpath
	fi
	confs+=($fullpath)
	echo $conf
done > $OUTPUT/config.list

cd $KERNEL_PATH

ARCH=$TARGET_ARCH CROSS_COMPILE=$TARGET_CROSS_COMPILE ./scripts/kconfig/merge_config.sh -O $OUTPUT ${confs[*]} > $OUTPUT/merge_config.log
