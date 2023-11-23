// Copyright (c) 2010 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef BENCH_GL_UTILS_H_
#define BENCH_GL_UTILS_H_

#if defined(USE_OPENGLES)
#include "GLES2/gl2.h"
#elif defined(USE_OPENGL)
#include "GL/gl.h"
#endif

#include <signal.h>
#include <algorithm>
#include <functional>
#include <limits>
#include <map>
#include <string>
#include <vector>

extern double g_initial_temperature;

void SetBasePathFromArgv0(const char* argv0, const char* relative);
void* MmapFile(const char* name, size_t* length);

// Returns temperature of system before testing started. It is used as a
// reference for keeping the machine cool.
const double GetInitialMachineTemperature();
// For thermal monitoring of system.
double GetMachineTemperature();
// Wait for machine to cool with temperature in Celsius and timeout in seconds.
// Returns the time spent waiting and sets the last observed temperature.
double WaitForCoolMachine(double cold_temperature,
                          double timeout,
                          double* temperature);
bool check_dir_existence(const char* file_path);
bool check_file_existence(const char* file_path, struct stat* buffer);
// SplitString by delimiter.
std::vector<std::string> SplitString(std::string& input,
                                     std::string delimiter,
                                     bool trim_space);
template <typename INT>
std::string IntToString(INT value) {
  // log10(2) ~= 0.3 bytes needed per bit or per byte log10(2**8) ~= 2.4.
  // So round up to allocate 3 output characters per byte, plus 1 for '-'.
  const size_t kOutputBufSize =
      3 * sizeof(INT) + std::numeric_limits<INT>::is_signed;

  // Create the string in a temporary buffer, write it back to front, and
  // then return the substr of what we ended up using.
  using CHR = typename std::string::value_type;
  CHR outbuf[kOutputBufSize];

  // The ValueOrDie call below can never fail, because UnsignedAbs is valid
  // for all valid inputs.
  typename std::make_unsigned<INT>::type res = value < 0 ? -value : value;

  CHR* end = outbuf + kOutputBufSize;
  CHR* i = end;
  do {
    --i;
    // DCHECK(i != outbuf);
    *i = static_cast<CHR>((res % 10) + '0');
    res /= 10;
  } while (res != 0);
  if (value < 0) {
    --i;
    // DCHECK(i != outbuf);
    *i = static_cast<CHR>('-');
  }
  return std::string(i, end);
}

namespace glbench {

GLuint SetupTexture(GLsizei size_log2);
GLuint SetupVBO(GLenum target, GLsizeiptr size, const GLvoid* data);
void CreateLattice(GLfloat** vertices,
                   GLsizeiptr* size,
                   GLfloat size_x,
                   GLfloat size_y,
                   int width,
                   int height);
int CreateMesh(GLushort** indices,
               GLsizeiptr* size,
               int width,
               int height,
               int culled_ratio);
GLuint InitShaderProgram(const char* vertex_src, const char* fragment_src);
GLuint InitShaderProgramWithHeader(const char* header,
                                   const char* vertex_src,
                                   const char* fragment_src);
GLuint InitShaderProgramWithHeaders(const char** headers,
                                    int count,
                                    const char* vertex_src,
                                    const char* fragment_src);
void ClearBuffers();

}  // namespace glbench

class Callback {
 public:
  void Reset() { bind_state_ = NULL; }
  bool is_null() const { return !bind_state_; }
  void Run() { bind_state_(); }
  void Set(std::function<void()> func) { bind_state_ = func; }

 private:
  std::function<void()> bind_state_;
};

#define IMMEDIATE_CRASH() raise(SIGTRAP)

// Macro for hinting that an expression is likely to be false.
#if !defined(UNLIKELY)
#if defined(COMPILER_GCC) || defined(__clang__)
#define UNLIKELY(x) __builtin_expect(!!(x), 0)
#else
#define UNLIKELY(x) (x)
#endif  // defined(COMPILER_GCC)
#endif  // !defined(UNLIKELY)

#define CHECK(condition) UNLIKELY(!(condition)) ? IMMEDIATE_CRASH() : (0)

// trim from start (in place)
static inline void ltrim(std::string& s) {
  s.erase(s.begin(), std::find_if(s.begin(), s.end(),
                                  [](int ch) { return !std::isspace(ch); }));
}

// trim from end (in place)
static inline void rtrim(std::string& s) {
  s.erase(std::find_if(s.rbegin(), s.rend(),
                       [](int ch) { return !std::isspace(ch); })
              .base(),
          s.end());
}

// trim from both ends (in place)
static inline void trim(std::string& s) {
  ltrim(s);
  rtrim(s);
}

// Put this in the declarations for a class to be uncopyable.
#define DISALLOW_COPY(TypeName) TypeName(const TypeName&) = delete

// Put this in the declarations for a class to be unassignable.
#define DISALLOW_ASSIGN(TypeName) TypeName& operator=(const TypeName&) = delete

// Put this in the declarations for a class to be uncopyable and unassignable.
#define DISALLOW_COPY_AND_ASSIGN(TypeName) \
  DISALLOW_COPY(TypeName);                 \
  DISALLOW_ASSIGN(TypeName)

#endif  // BENCH_GL_UTILS_H_
