// Copyright 2022 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#pragma once

#include "base/time/time.h"

namespace base {

/**
 * Workaround for the error in unit tests: ISO C++20 considers use of overloaded
 * operator '==' (with operand types 'const base::TimeTicks'
 * and 'const base::TimeTicks') to be ambiguous despite there being a unique
 * best viable function [-Werror,-Wambiguous-reversed-operator]
 */
bool operator==(const TimeTicks& t1, const TimeTicks& t2);

}  // namespace base
