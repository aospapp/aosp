// Copyright (c) 2018 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "arraysize.h"
#include "main.h"
#include "testbase.h"
#include "utils.h"

namespace glbench {

class BufferUploadSubTest : public TestBase {
  public:
    BufferUploadSubTest()
      : buffer_size_(1572864),
      target_(GL_ARRAY_BUFFER),
      size_(0)
  {
    memset(data_, 0, sizeof(data_));
  }
    virtual ~BufferUploadSubTest() {}
    virtual bool TestFunc(uint64_t iterations);
    virtual bool Run();
    virtual const char* Name() const { return "buffer_upload_sub"; }
    virtual bool IsDrawTest() const { return false; }
    virtual const char* Unit() const { return "mbytes_sec"; }

  private:
    GLsizeiptr buffer_size_;
    GLenum target_;
    GLsizeiptr size_;
    GLbyte data_[256 * 1024];
    DISALLOW_COPY_AND_ASSIGN(BufferUploadSubTest);
};

bool BufferUploadSubTest::TestFunc(uint64_t iterations) {
  GLintptr offset = 0;
  for (uint64_t i = 0; i < iterations - 1; ++i) {
    if (offset + size_ > buffer_size_) {
      offset = 0;
    }
    glBufferSubData(target_, offset, size_, data_);
    offset += size_;
  }
  return true;
}

bool BufferUploadSubTest::Run() {
  const GLenum usages[] = {GL_DYNAMIC_DRAW, GL_STATIC_DRAW};
  const char * usage_names[] = {"dynamic", "static"};
  const GLenum targets[] = {GL_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER};
  const char * target_names[] = {"array", "element_array"};
  const int sizes[] = {8, 12, 16, 32, 64, 128, 192, 256, 512, 1024, 2048,
                        4096, 8192, 16384, 32768, 65536, 131072};

  for (unsigned int uidx = 0; uidx < arraysize(usages); uidx++) {
    GLenum usage = usages[uidx];

    for (unsigned int tidx = 0; tidx < arraysize(targets); tidx++) {
      target_ = targets[tidx];
      GLuint buf = ~0;
      glGenBuffers(1, &buf);
      glBindBuffer(target_, buf);

      for (unsigned int sidx = 0; sidx < arraysize(sizes); sidx++) {
        size_ = sizes[sidx];
        glBufferData(target_, buffer_size_, NULL, usage);

        std::string name = std::string(Name()) + "_" + usage_names[uidx] + "_" +
                            target_names[tidx] + "_" + IntToString(size_);
        RunTest(this, name.c_str(), sizes[sidx], g_width, g_height, true);
        CHECK(!glGetError());
      }

      glDeleteBuffers(1, &buf);
    }
  }

  return true;
}

TestBase* GetBufferUploadSubTest() {
  return new BufferUploadSubTest;
}

} // namespace glbench
