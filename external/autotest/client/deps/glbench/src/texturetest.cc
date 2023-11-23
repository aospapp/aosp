// Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "texturetest.h"
#include "arraysize.h"
#include <assert.h>

namespace glbench {

namespace {

// Vertex and fragment shader code.
const char* kVertexShader =
    "attribute vec4 c1;"
    "attribute vec4 c2;"
    "varying vec4 v1;"
    "void main() {"
    "  gl_Position = c1;"
    "  v1 = c2;"
    "}";

const char* kFragmentShader =
    "varying vec4 v1;"
    "uniform sampler2D texture;"
    "void main() {"
    "  gl_FragColor = texture2D(texture, v1.xy);"
    "}";

}  // namespace

bool TextureTest::TextureMetaDataInit() {
  // TODO(djkurtz): Other formats such as GL_BGRA, GL_RGB, GL_BGR, ... ?
  // , GL_BGRA, GL_RGB, GL_BGR };
  kTexelFormats.push_back(GL_LUMINANCE);
  kTexelFormats.push_back(GL_RGBA);

  kTexelFormatNames[GL_LUMINANCE] = "luminance";
  kTexelFormatNames[GL_RGBA] = "rgba";

  kTexelFormatSizes[GL_LUMINANCE] = 1;
  kTexelFormatSizes[GL_RGBA] = 4;

  kFlavors[TEX_IMAGE] = "teximage2d";
  kFlavors[TEX_SUBIMAGE] = "texsubimage2d";
  return true;
}

bool TextureTest::Run() {
  TextureMetaDataInit();
  // Two triangles that form one pixel at 0, 0.
  const GLfloat kVertices[8] = {
      0.f,           0.f,
      2.f / g_width, 0.f,
      0.f,           2.f / g_height,
      2.f / g_width, 2.f / g_height,
  };
  const GLfloat kTexCoords[8] = {
      0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f,
  };

  program_ = InitShaderProgram(kVertexShader, kFragmentShader);

  int attr1 = glGetAttribLocation(program_, "c1");
  glVertexAttribPointer(attr1, 2, GL_FLOAT, GL_FALSE, 0, kVertices);
  glEnableVertexAttribArray(attr1);

  int attr2 = glGetAttribLocation(program_, "c2");
  glVertexAttribPointer(attr2, 2, GL_FLOAT, GL_FALSE, 0, kTexCoords);
  glEnableVertexAttribArray(attr2);

  int texture_sampler = glGetUniformLocation(program_, "texture");
  glUniform1i(texture_sampler, 0);
  glActiveTexture(GL_TEXTURE0);

  glGenTextures(kNumberOfTextures, textures_);
  for (int i = 0; i < kNumberOfTextures; ++i) {
    glBindTexture(GL_TEXTURE_2D, textures_[i]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  }

  for (auto fmt : kTexelFormats) {
    texel_gl_format_ = fmt;
    std::string texel_gl_format_name = kTexelFormatNames.at(texel_gl_format_);
    unsigned int texel_size = kTexelFormatSizes[texel_gl_format_];
    for (auto flv : kFlavors){
      flavor_ = flv.first;
      std::string flavor_name = flv.second;

      const int sizes[] = {32, 128, 256, 512, 768, 1024, 1536, 2048};
      for (unsigned int j = 0; j < arraysize(sizes); j++) {
        // In hasty mode only do at most 512x512 sized problems.
        if (g_hasty && sizes[j] > 512)
          continue;

        std::string name = std::string(Name()) + "_" + texel_gl_format_name +
                           "_" + flavor_name + "_" +
                           IntToString(sizes[j]);

        width_ = height_ = sizes[j];
        const unsigned int buffer_size = width_ * height_ * texel_size;
        for (int i = 0; i < kNumberOfTextures; ++i) {
          pixels_[i].reset(new char[buffer_size]);
          memset(pixels_[i].get(), 255, buffer_size);

          // For NPOT texture we must set GL_TEXTURE_WRAP as GL_CLAMP_TO_EDGE
          glBindTexture(GL_TEXTURE_2D, textures_[i]);
          glTexImage2D(GL_TEXTURE_2D, 0, texel_gl_format_, width_, height_, 0,
                       texel_gl_format_, GL_UNSIGNED_BYTE, NULL);
          if (glGetError() != 0) {
            printf("# Error: Failed to allocate %dx%d %u-byte texel texture.\n",
                   width_, height_, texel_size);
          }
          if (IS_NOT_POWER_OF_2(width_) || IS_NOT_POWER_OF_2(height_)) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
          }

          // Only upload the texture here if the test is not intended to test
          // the texture upload speed.
          if (!this->IsTextureUploadTest()) {
            glBindTexture(GL_TEXTURE_2D, textures_[i]);
            switch (flavor_) {
              case TEX_IMAGE:
                glTexImage2D(GL_TEXTURE_2D, 0, texel_gl_format_,
                             width_, height_, 0,
                             texel_gl_format_, GL_UNSIGNED_BYTE,
                             pixels_[i % kNumberOfTextures].get());
                break;
              case TEX_SUBIMAGE:
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_,
                                texel_gl_format_, GL_UNSIGNED_BYTE,
                                pixels_[i % kNumberOfTextures].get());
                break;
            }
          }
        }
        RunTest(this, name.c_str(), buffer_size, g_width, g_height, true);
        GLenum error = glGetError();
        if (error != GL_NO_ERROR) {
          printf(
              "# GL error code %d after RunTest() with %dx%d %d-byte "
              "texture.\n",
              error, width_, height_, texel_size);
        }
      }
    }
  }
  for (int i = 0; i < kNumberOfTextures; ++i)
    pixels_[i].reset();

  glDeleteTextures(kNumberOfTextures, textures_);
  glDeleteProgram(program_);
  return true;
}

}  // namespace glbench
