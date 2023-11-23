<!--
  Copyright (C) 2022 The Android Open Source Project

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

# Android Automotive OS API Generator Tool

* Tool will generate a file `complete_api_list.txt`. This file contains
  all public API including the hidden API. It also contains three type
  of annotations - `@hide` (for hidden calls), `@SystemAPI` (for System
  calls), and `@AddedIn`/`@AddedInOrBefore` Annotation. This file can be a
  better guide for future API changes as it can keep track of hidden
  APIs too.
* tool will also generate a file `un_annotated_api_list.txt`. This file
  contains the API which are not annotated with
  `@AddedIn`/`@AddedInOrBefore annotation`. This file should be empty before
  the release.
* To Build `m -j GenericCarApiBuilder`
* To Run `GenericCarApiBuilder`

