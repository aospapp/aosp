// Copyright 2018 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// This test evaluates the speed of rebinding the texture after each draw call.

#include "main.h"
#include "texturetest.h"

namespace glbench {

class TextureRebindTest : public TextureTest {
 public:
  TextureRebindTest() {}
  virtual bool TextureMetaDataInit();
  virtual ~TextureRebindTest() {}
  virtual bool TestFunc(uint64_t iterations);
  virtual const char* Name() const { return "texture_rebind"; }
  virtual bool IsDrawTest() const { return true; }
  virtual bool IsTextureUploadTest() const { return false; }
};


bool TextureRebindTest::TextureMetaDataInit(){
    kTexelFormats.push_back(GL_RGBA);
    kTexelFormatNames[GL_RGBA] = "rgba";
    kTexelFormatSizes[GL_RGBA] = 4;
    kFlavors[TEX_IMAGE] = "teximage2d";
    return true;
}

bool TextureRebindTest::TestFunc(uint64_t iterations) {
  for (uint64_t i = 0; i < iterations; ++i) {
    for (uint64_t texture_idx = 0;
         texture_idx < kNumberOfTextures;
         texture_idx++) {
      glBindTexture(GL_TEXTURE_2D, textures_[texture_idx]);
      glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
  }

  return true;
}

TestBase* GetTextureRebindTest() {
  return new TextureRebindTest;
}

}  // namespace glbench
