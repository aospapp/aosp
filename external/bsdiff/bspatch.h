// Copyright 2015 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef _BSDIFF_BSPATCH_H_
#define _BSDIFF_BSPATCH_H_

#include <memory>
#include <vector>

#include "extents_file.h"

namespace bsdiff {

int bspatch(const char* old_filename,
            const char* new_filename,
            const char* patch_filename,
            const char* old_extents,
            const char* new_extents);

bool WriteAll(const std::unique_ptr<FileInterface>& file,
              const uint8_t* data,
              size_t size);

bool IsOverlapping(const char* old_filename,
                   const char* new_filename,
                   const std::vector<ex_t>& old_extents,
                   const std::vector<ex_t>& new_extents);

}  // namespace bsdiff

#endif  // _BSDIFF_BSPATCH_H_
