// Copyright 2016 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef _BSDIFF_MEMORY_FILE_H_
#define _BSDIFF_MEMORY_FILE_H_

#include <memory>
#include <vector>

#include "file_interface.h"

namespace bsdiff {

class MemoryFile : public FileInterface {
 public:
  // Creates a MemoryFile based on the underlying |file| passed. The MemoryFile
  // will cache all the write in memory and write it to to |file| when it's
  // closed. MemoryFile does not support read and seek.
  // |size| should be the estimated total file size, it is used to reserve
  // buffer space.
  MemoryFile(std::unique_ptr<FileInterface> file, size_t size);

  ~MemoryFile() override;

  // FileInterface overrides.
  bool Read(void* buf, size_t count, size_t* bytes_read) override;
  bool Write(const void* buf, size_t count, size_t* bytes_written) override;
  bool Seek(off_t pos) override;
  bool Close() override;
  bool GetSize(uint64_t* size) override;

 private:
  // The underlying FileInterace instance.
  std::unique_ptr<FileInterface> file_;

  std::vector<uint8_t> buffer_;
};

}  // namespace bsdiff

#endif  // _BSDIFF_MEMORY_FILE_H_
