# Copyright 2023 The Android Open Source Project
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

#!/bin/bash
#
# Setup repo directory.
REPO=$(dirname "$0")/../../..

# Get the boolean flag from the user
while getopts "b" flag; do
    case $flag in
        b) npm_build="true";;
        *) echo "'-b' flag allows npm build to occur"; exit 1;;
    esac
done

# Checks if objs directory exists
if [ ! -d "$REPO/tools/netsim/objs" ]; then
    echo "Please run bash scripts/cmake_setup.sh && ninja -C objs first"
    exit 1
fi

# Refresh objs/netsim-ui directory
if [ -d "$REPO/tools/netsim/objs/netsim-ui" ]; then
    rm -r $REPO/tools/netsim/objs/netsim-ui
fi

# Create directory for netsim-ui
mkdir $REPO/tools/netsim/objs/netsim-ui

# If npm build flag is set, perform npm build
if [[ $npm_build == "true" ]]; then
    cd $REPO/tools/netsim/ui
    npm run tsproto
    npm run build
    cd ..
fi

# Copy files from ui/dist into objs/netsim-ui
cp -r $REPO/tools/netsim/ui/dist/* $REPO/tools/netsim/objs/netsim-ui/