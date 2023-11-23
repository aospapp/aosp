// Copyright 2018 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef BENCH_GL_FILES_PATH_H_
#define BENCH_GL_FILES_PATH_H_

#include <iostream>

class FilePath {
 public:
  FilePath() { this->path_ = std::string(""); }
  FilePath(const FilePath& that) { this->path_ = that.path_; }
  FilePath(std::string path) { this->path_ = path; }
  FilePath(const char* path) { this->path_ = path; }
  ~FilePath() = default;

  FilePath DirName();
  const std::string& value() const;
  bool IsSeparator(char character);
  FilePath Append(const FilePath& path);
  void StripTrailingSeparatorsInternal();

 private:
  std::string path_;
  char kStringTerminator = '\0';
  char kSeparators[2] = "/";
  char kCurrentDirectory[2] = ".";
};
bool CreateDirectory(FilePath&);
std::string::size_type FindDriveLetter(std::string path);

#endif  // BENCH_GL_FILES_PATH_H_