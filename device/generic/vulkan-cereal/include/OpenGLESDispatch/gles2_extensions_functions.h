// Auto-generated with: android/scripts/gen-entries.py --mode=funcargs stream-servers/gl/OpenGLESDispatch/gles2_extensions.entries --output=include/OpenGLESDispatch/gles2_extensions_functions.h
// DO NOT EDIT THIS FILE

#ifndef GLES2_EXTENSIONS_FUNCTIONS_H
#define GLES2_EXTENSIONS_FUNCTIONS_H

#include <GLES/gl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#define LIST_GLES2_EXTENSIONS_FUNCTIONS(X) \
  X(void, glGetShaderPrecisionFormat, (GLenum shadertype, GLenum precisiontype, GLint* range, GLint* precision), (shadertype, precisiontype, range, precision)) \
  X(void, glReleaseShaderCompiler, (), ()) \
  X(void, glShaderBinary, (GLsizei n, const GLuint* shaders, GLenum binaryformat, const GLvoid* binary, GLsizei length), (n, shaders, binaryformat, binary, length)) \
  X(void, glVertexAttribPointerWithDataSize, (GLuint indx, GLint size, GLenum type, GLboolean normalized, GLsizei stride, const GLvoid* ptr, GLsizei dataSize), (indx, size, type, normalized, stride, ptr, dataSize)) \
  X(void, glFramebufferTexture3DOES, (GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level, GLint zoffset), (target, attachment, textarget, texture, level, zoffset)) \
  X(void, glTestHostDriverPerformance, (GLuint count, uint64_t* duration_us, uint64_t* duration_cpu_us), (count, duration_us, duration_cpu_us)) \
  X(void, glBindVertexArrayOES, (GLuint array), (array)) \
  X(void, glDeleteVertexArraysOES, (GLsizei n, const GLuint * arrays), (n, arrays)) \
  X(void, glGenVertexArraysOES, (GLsizei n, GLuint * arrays), (n, arrays)) \
  X(GLboolean, glIsVertexArrayOES, (GLuint array), (array)) \
  X(void, glDebugMessageControlKHR, (GLenum source, GLenum type, GLenum severity, GLsizei count, const GLuint * ids, GLboolean enabled), (source, type, severity, count, ids, enabled)) \
  X(void, glDebugMessageInsertKHR, (GLenum source, GLenum type, GLuint id, GLenum severity, GLsizei length, const GLchar * buf), (source, type, id, severity, length, buf)) \
  X(void, glDebugMessageCallbackKHR, (GLDEBUGPROCKHR callback, const void * userParam), (callback, userParam)) \
  X(GLuint, glGetDebugMessageLogKHR, (GLuint count, GLsizei bufSize, GLenum * sources, GLenum * types, GLuint * ids, GLenum * severities, GLsizei * lengths, GLchar * messageLog), (count, bufSize, sources, types, ids, severities, lengths, messageLog)) \
  X(void, glPushDebugGroupKHR, (GLenum source, GLuint id, GLsizei length, const GLchar* message), (source, id, length, message)) \
  X(void, glPopDebugGroupKHR, (), ()) \


#endif  // GLES2_EXTENSIONS_FUNCTIONS_H
