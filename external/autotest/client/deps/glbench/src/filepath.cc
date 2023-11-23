// Copyright 2018 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "filepath.h"
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <vector>
#include "utils.h"

inline void AppendToString(std::string* target, std::string& source) {
  target->append(source);
}

FilePath FilePath::DirName() {
  FilePath new_path(path_);

  std::string::size_type last_separator =
      new_path.path_.find_last_of(kSeparators, std::string::npos);

  unsigned int letter = 0;
  if (last_separator == std::string::npos) {
    // path_ is in the current directory.
    new_path.path_.resize(letter + 1);
  } else if (last_separator == letter + 1) {
    // path_ is in the root directory.
    new_path.path_.resize(letter + 2);
  } else if (last_separator == letter + 2 &&
             IsSeparator(new_path.path_[letter + 1])) {
    // path_ is in "//" (possibly with a drive letter); leave the double
    // separator intact indicating alternate root.
    new_path.path_.resize(letter + 3);
  } else if (last_separator != 0) {
    // path_ is somewhere else, trim the basename.
    new_path.path_.resize(last_separator);
  }
  new_path.StripTrailingSeparatorsInternal();
  if (!new_path.path_.length())
    new_path.path_ = kCurrentDirectory;

  return new_path;
}

const std::string& FilePath::value() const {
  return this->path_;
}

bool FilePath::IsSeparator(char character) {
  for (size_t i = 0; i < strlen(kSeparators) - 1; ++i) {
    if (character == kSeparators[i]) {
      return true;
    }
  }
  return false;
}

FilePath FilePath::Append(const FilePath& path) {
  // return FilePath(this->path_ + path.path_);
  std::string component = path.value();
  std::string appended = component;
  std::string without_nuls;

  std::string::size_type nul_pos = component.find(kStringTerminator);
  if (nul_pos != std::string::npos) {
    // without_nuls = component.substr(0, nul_pos);
    appended = std::string(without_nuls);
  }

  // DCHECK(appended.length() <= 0 || appended[0] !=
  // this->separator.c_str()[0]);
  if (path_.compare(kCurrentDirectory) == 0 && !appended.empty()) {
    // Append normally doesn't do any normalization, but as a special case,
    // when appending to kCurrentDirectory, just return a new path for the
    // component argument.  Appending component to kCurrentDirectory would
    // serve no purpose other than needlessly lengthening the path, and
    // it's likely in practice to wind up with FilePath objects containing
    // only kCurrentDirectory when calling DirName on a single relative path
    // component.
    return FilePath(appended);
  }
  FilePath new_path(path_);
  new_path.StripTrailingSeparatorsInternal();

  // Don't append a separator if the path is empty (indicating the current
  // directory) or if the path component is empty (indicating nothing to
  // append).
  if (!appended.empty() && !new_path.path_.empty()) {
    // Don't append a separator if the path still ends with a trailing
    // separator after stripping (indicating the root directory).
    char tmp = new_path.path_.back();
    if (!IsSeparator(tmp)) {
      // Don't append a separator if the path is just a drive letter.
      if (FindDriveLetter(new_path.path_) + 1 != new_path.path_.length()) {
        new_path.path_.append(1, kSeparators[0]);
      }
    }
  }
  AppendToString(&new_path.path_, appended);
  return new_path;
}

void FilePath::StripTrailingSeparatorsInternal() {
  // If there is no drive letter, start will be 1, which will prevent
  // stripping the leading separator if there is only one separator.  If there
  // is a drive letter, start will be set appropriately to prevent stripping
  // the first separator following the drive letter, if a separator
  // immediately follows the drive letter.
  std::string::size_type start = FindDriveLetter(path_) + 2;

  std::string::size_type last_stripped = std::string::npos;
  for (std::string::size_type pos = path_.length();
       pos > start && IsSeparator(path_[pos - 1]); --pos) {
    // If the string only has two separators and they're at the beginning,
    // don't strip them, unless the string began with more than two
    // separators.
    if (pos != start + 1 || last_stripped == start + 2 ||
        !IsSeparator(path_[start - 1])) {
      path_.resize(pos - 1);
      last_stripped = pos;
    }
  }
}

std::string::size_type FindDriveLetter(std::string path) {
#if defined(FILE_PATH_USES_DRIVE_LETTERS)
  // This is dependent on an ASCII-based character set, but that's a
  // reasonable assumption.  iswalpha can be too inclusive here.
  if (path.length() >= 2 && path[1] == L':' &&
      ((path[0] >= L'A' && path[0] <= L'Z') ||
       (path[0] >= L'a' && path[0] <= L'z'))) {
    return 1;
  }
#endif  // FILE_PATH_USES_DRIVE_LETTERS
  return std::string::npos;
}

bool CreateDirectory(FilePath& full_path) {
  std::vector<FilePath> subpaths;

  // Collect a list of all parent directories.
  FilePath last_path = full_path;
  subpaths.push_back(full_path);
  for (FilePath path = full_path.DirName(); path.value() != last_path.value();
       path = path.DirName()) {
    subpaths.push_back(path);
    last_path = path;
  }

  // Iterate through the parents and create the missing ones.
  for (auto i = subpaths.rbegin(); i != subpaths.rend(); ++i) {
    if (check_dir_existence(i->value().c_str()))
      continue;
    if (mkdir(i->value().c_str(), 0700) == 0)
      continue;
    // Mkdir failed, but it might have failed with EEXIST, or some other error
    // due to the the directory appearing out of thin air. This can occur if
    // two processes are trying to create the same file system tree at the same
    // time. Check to see if it exists and make sure it is a directory.
    if (!check_dir_existence(i->value().c_str())) {
      return false;
    }
  }
  return true;
}
