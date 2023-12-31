#
# Copyright (C) 2020 The Android Open Source Project
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
#

import itertools
from typing import List

def DeduplicateKeepOrder(*lists: List) -> List:
    '''Merge two list, remove duplicate items, and order.

    Args:
        lists: any number of lists

    Returns:
        A merged list where items are unique and original order is kept.
    '''
    seen = set()
    return [
        x for x in itertools.chain(*lists) if not (x in seen or seen.add(x))
    ]
