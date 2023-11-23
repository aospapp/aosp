// Copyright (c) 2018 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "arraysize.h"
#include "main.h"
#include "testbase.h"
#include "utils.h"

namespace glbench {

namespace {

const int kNumberOfBuffers = 1;

} // namespace

class BufferUploadTest : public TestBase {
 public:
  BufferUploadTest()
      : target_(GL_ARRAY_BUFFER),
        size_(0),
        usage_(GL_DYNAMIC_DRAW)
  {
    memset(data_, 0, sizeof(data_));
  }
  virtual ~BufferUploadTest() {}
  virtual bool TestFunc(uint64_t iterations);
  virtual bool Run();
  virtual const char* Name() const { return "buffer_upload"; }
  virtual bool IsDrawTest() const { return false; }
  virtual const char* Unit() const { return "mbytes_sec"; }

 private:
  GLenum target_;
  GLsizeiptr size_;
  GLenum usage_;
  GLbyte data_[256 * 1024];
  GLuint buffers_[kNumberOfBuffers];
  DISALLOW_COPY_AND_ASSIGN(BufferUploadTest);
};

bool BufferUploadTest::TestFunc(uint64_t iterations) {
  for (uint64_t i = 0; i < iterations - 1; ++i) {
    if (kNumberOfBuffers > 1) {
      glBindBuffer(target_, buffers_[i % kNumberOfBuffers]);
    }
    glBufferData(target_, size_, data_, usage_);
  }
  return true;
}

bool BufferUploadTest::Run() {
  const GLenum usages[] = {GL_DYNAMIC_DRAW, GL_STATIC_DRAW};
  const char * usage_names[] = {"dynamic", "static"};
  const GLenum targets[] = {GL_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER};
  const char * target_names[] = {"array", "element_array"};
  const int sizes[] = {8, 12, 16, 32, 64, 128, 192, 256, 512, 1024, 2048,
                       4096, 8192, 16384, 32768, 65536, 131072};

  for (unsigned int uidx = 0; uidx < arraysize(usages); uidx++) {
    usage_ = usages[uidx];

    for (unsigned int tidx = 0; tidx < arraysize(targets); tidx++) {
      target_ = targets[tidx];
      glGenBuffers(kNumberOfBuffers, buffers_);
      if (kNumberOfBuffers == 1) {
        glBindBuffer(target_, buffers_[0]);
      }

      for (unsigned int sidx = 0; sidx < arraysize(sizes); sidx++) {
        size_ = sizes[sidx];

        std::string name = std::string(Name()) + "_" + usage_names[uidx] + "_" +
                           target_names[tidx] + "_" + IntToString(size_);
        RunTest(this, name.c_str(), sizes[sidx], g_width, g_height, true);
        CHECK(!glGetError());
      }

      glDeleteBuffers(kNumberOfBuffers, buffers_);
    }
  }

  return true;
}

TestBase* GetBufferUploadTest() {
  return new BufferUploadTest;
}

} // namespace glbench
