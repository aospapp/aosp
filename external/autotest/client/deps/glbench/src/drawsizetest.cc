// Copyright 2018 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <stdlib.h>

#include "arraysize.h"
#include "main.h"
#include "testbase.h"
#include "utils.h"

namespace glbench {

class DrawSizeTest : public DrawElementsTestFunc {
 public:
  DrawSizeTest() {}
  virtual ~DrawSizeTest() {}
  virtual bool Run();
  virtual const char* Name() const { return "draw_size"; }

 private:
  DISALLOW_COPY_AND_ASSIGN(DrawSizeTest);
};

const char* kDrawSizeVS =
    "attribute vec4 pos;"
    "void main() {"
    "  gl_Position = pos;"
    "}";

const char* kDrawSizeFS =
    "uniform vec4 color;"
    "void main() {"
    "  gl_FragColor = color;"
    "}";

bool DrawSizeTest::Run() {
  GLuint program = InitShaderProgram(kDrawSizeVS, kDrawSizeFS);
  const int sizes[] = {4, 8, 16, 32, 64, 128};

  glViewport(0, 0, g_width, g_height);

  for (unsigned int j = 0; j < arraysize(sizes); j++) {
    // This specifies a square mesh in the middle of the viewport.
    // Larger meshes make this test too slow for devices that do 1 mtri/sec.
    // Also note that GLES 2.0 uses 16 bit indices.
    GLint width = sizes[j];
    GLint height = sizes[j];

    GLfloat* vertices = NULL;
    GLsizeiptr vertex_buffer_size = 0;
    CreateLattice(&vertices, &vertex_buffer_size, 1.f / g_width, 1.f / g_height,
                  width, height);
    GLuint vertex_buffer =
        SetupVBO(GL_ARRAY_BUFFER, vertex_buffer_size, vertices);

    GLint attribute_index = glGetAttribLocation(program, "pos");
    glVertexAttribPointer(attribute_index, 2, GL_FLOAT, GL_FALSE, 0, NULL);
    glEnableVertexAttribArray(attribute_index);

    // Use orange for drawing.
    const GLfloat orange[4] = {1.0f, 0.5f, 0.0f, 1.0f};
    GLint color_uniform = glGetUniformLocation(program, "color");
    glUniform4fv(color_uniform, 1, orange);

    GLushort* indices = NULL;
    GLuint index_buffer = 0;
    GLsizeiptr index_buffer_size = 0;

    count_ = CreateMesh(&indices, &index_buffer_size, width, height, 0);

    index_buffer =
        SetupVBO(GL_ELEMENT_ARRAY_BUFFER, index_buffer_size, indices);

    std::string name = "draw_size_" + IntToString(width * height);

    RunTest(this, name.c_str(), count_ / 3, g_width, g_height, true);

    glDeleteBuffers(1, &index_buffer);
    delete[] indices;
    glDeleteBuffers(1, &vertex_buffer);
    delete[] vertices;
  }

  glDeleteProgram(program);
  return true;
}

TestBase* GetDrawSizeTest() {
  return new DrawSizeTest;
}

}  // namespace glbench
