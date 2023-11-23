<!--
  Copyright (C) 2021 The Android Open Source Project

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License
  -->

# How to run unit tests for ScriptExecutor

**1. Navigate to the root of the repo and do full build:**

`m -j`

**2. Flash the device with this build:**

`aae flash`

**3. Run the tests. For example**

`atest ScriptExecutorUnitTest:ScriptExecutorTest`


## How to rerun the tests after changes
Sometimes a test needs to be modified. These are the steps to do incremental update instead of full
device flash.

**1. Navigate to ScriptExecutor unit test location and build its targets:**
`cd packages/services/Car/packages/ScriptExecutor/tests/unit`

`mm -j`

**2. Sync the device with all the files that need to be updated:**

`adb root`

`adb remount`

`adb sync && adb shell stop && adb shell start`

**3. At this point we are ready to run the tests again. For example:**

`atest ScriptExecutorUnitTest:ScriptExecutorTest`

