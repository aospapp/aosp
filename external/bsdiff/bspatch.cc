/*-
 * Copyright 2003-2005 Colin Percival
 * All rights reserved
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted providing that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

#if 0
__FBSDID("$FreeBSD: src/usr.bin/bsdiff/bspatch/bspatch.c,v 1.1 2005/08/06 01:59:06 cperciva Exp $");
#endif

#include "bspatch.h"

#include <bzlib.h>
#include <err.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <algorithm>
#include <memory>
#include <limits>
#include <vector>

#include "extents.h"
#include "extents_file.h"
#include "file.h"
#include "file_interface.h"
#include "memory_file.h"

namespace {

int64_t ParseInt64(u_char* buf) {
  int64_t y;

  y = buf[7] & 0x7F;
  y = y * 256;
  y += buf[6];
  y = y * 256;
  y += buf[5];
  y = y * 256;
  y += buf[4];
  y = y * 256;
  y += buf[3];
  y = y * 256;
  y += buf[2];
  y = y * 256;
  y += buf[1];
  y = y * 256;
  y += buf[0];

  if (buf[7] & 0x80)
    y = -y;

  return y;
}

bool ReadBZ2(BZFILE* pfbz2, uint8_t* data, size_t size) {
  int bz2err;
  size_t lenread = BZ2_bzRead(&bz2err, pfbz2, data, size);
  if (lenread < size || (bz2err != BZ_OK && bz2err != BZ_STREAM_END))
    return false;
  return true;
}

bool ReadBZ2AndWriteAll(const std::unique_ptr<bsdiff::FileInterface>& file,
                        BZFILE* pfbz2,
                        size_t size,
                        uint8_t* buf,
                        size_t buf_size) {
  while (size > 0) {
    size_t bytes_to_read = std::min(size, buf_size);
    if (!ReadBZ2(pfbz2, buf, bytes_to_read))
      return false;
    if (!WriteAll(file, buf, bytes_to_read))
      return false;
    size -= bytes_to_read;
  }
  return true;
}

}  // namespace

namespace bsdiff {

bool WriteAll(const std::unique_ptr<FileInterface>& file,
              const uint8_t* data,
              size_t size) {
  size_t offset = 0, written;
  while (offset < size) {
    if (!file->Write(data + offset, size - offset, &written) || written == 0)
      return false;
    offset += written;
  }
  return true;
}

bool IsOverlapping(const char* old_filename,
                   const char* new_filename,
                   const std::vector<ex_t>& old_extents,
                   const std::vector<ex_t>& new_extents) {
  struct stat old_stat, new_stat;
  if (stat(new_filename, &new_stat) == -1) {
    if (errno == ENOENT)
      return false;
    err(1, "Error stat the new filename %s", new_filename);
  }
  if (stat(old_filename, &old_stat) == -1)
    err(1, "Error stat the old filename %s", old_filename);

  if (old_stat.st_dev != new_stat.st_dev || old_stat.st_ino != new_stat.st_ino)
    return false;

  if (old_extents.empty() && new_extents.empty())
    return true;

  for (ex_t old_ex : old_extents)
    for (ex_t new_ex : new_extents)
      if (static_cast<uint64_t>(old_ex.off) < new_ex.off + new_ex.len &&
          static_cast<uint64_t>(new_ex.off) < old_ex.off + old_ex.len)
        return true;

  return false;
}

int bspatch(
    const char* old_filename, const char* new_filename,
    const char* patch_filename,
    const char* old_extents, const char* new_extents) {
  FILE* f, *cpf, *dpf, *epf;
  BZFILE* cpfbz2, *dpfbz2, *epfbz2;
  int bz2err;
  ssize_t bzctrllen, bzdatalen;
  u_char header[32], buf[8];
  off_t ctrl[3];

  int using_extents = (old_extents != NULL || new_extents != NULL);

  // Open patch file.
  if ((f = fopen(patch_filename, "r")) == NULL)
    err(1, "fopen(%s)", patch_filename);

  // File format:
  //   0       8    "BSDIFF40"
  //   8       8    X
  //   16      8    Y
  //   24      8    sizeof(new_filename)
  //   32      X    bzip2(control block)
  //   32+X    Y    bzip2(diff block)
  //   32+X+Y  ???  bzip2(extra block)
  // with control block a set of triples (x,y,z) meaning "add x bytes
  // from oldfile to x bytes from the diff block; copy y bytes from the
  // extra block; seek forwards in oldfile by z bytes".

  // Read header.
  if (fread(header, 1, 32, f) < 32) {
    if (feof(f))
      errx(1, "Corrupt patch\n");
    err(1, "fread(%s)", patch_filename);
  }

  // Check for appropriate magic.
  if (memcmp(header, "BSDIFF40", 8) != 0)
    errx(1, "Corrupt patch\n");

  // Read lengths from header.
  uint64_t oldsize, newsize;
  bzctrllen = ParseInt64(header + 8);
  bzdatalen = ParseInt64(header + 16);
  int64_t signed_newsize = ParseInt64(header + 24);
  newsize = signed_newsize;
  if ((bzctrllen < 0) || (bzdatalen < 0) || (signed_newsize < 0))
    errx(1, "Corrupt patch\n");

  // Close patch file and re-open it via libbzip2 at the right places.
  if (fclose(f))
    err(1, "fclose(%s)", patch_filename);
  if ((cpf = fopen(patch_filename, "r")) == NULL)
    err(1, "fopen(%s)", patch_filename);
  if (fseek(cpf, 32, SEEK_SET))
    err(1, "fseeko(%s, %lld)", patch_filename, (long long)32);
  if ((cpfbz2 = BZ2_bzReadOpen(&bz2err, cpf, 0, 0, NULL, 0)) == NULL)
    errx(1, "BZ2_bzReadOpen, bz2err = %d", bz2err);
  if ((dpf = fopen(patch_filename, "r")) == NULL)
    err(1, "fopen(%s)", patch_filename);
  if (fseek(dpf, 32 + bzctrllen, SEEK_SET))
    err(1, "fseeko(%s, %lld)", patch_filename, (long long)(32 + bzctrllen));
  if ((dpfbz2 = BZ2_bzReadOpen(&bz2err, dpf, 0, 0, NULL, 0)) == NULL)
    errx(1, "BZ2_bzReadOpen, bz2err = %d", bz2err);
  if ((epf = fopen(patch_filename, "r")) == NULL)
    err(1, "fopen(%s)", patch_filename);
  if (fseek(epf, 32 + bzctrllen + bzdatalen, SEEK_SET))
    err(1, "fseeko(%s, %lld)", patch_filename,
        (long long)(32 + bzctrllen + bzdatalen));
  if ((epfbz2 = BZ2_bzReadOpen(&bz2err, epf, 0, 0, NULL, 0)) == NULL)
    errx(1, "BZ2_bzReadOpen, bz2err = %d", bz2err);

  // Open input file for reading.
  std::unique_ptr<FileInterface> old_file = File::FOpen(old_filename, O_RDONLY);
  if (!old_file)
    err(1, "Error opening the old filename");

  std::vector<ex_t> parsed_old_extents;
  if (using_extents) {
    if (!ParseExtentStr(old_extents, &parsed_old_extents))
      errx(1, "Error parsing the old extents");
    old_file.reset(new ExtentsFile(std::move(old_file), parsed_old_extents));
  }

  if (!old_file->GetSize(&oldsize))
    err(1, "cannot obtain the size of %s", old_filename);
  uint64_t old_file_pos = 0;

  // Open output file for writing.
  std::unique_ptr<FileInterface> new_file =
      File::FOpen(new_filename, O_CREAT | O_WRONLY);
  if (!new_file)
    err(1, "Error opening the new filename %s", new_filename);

  std::vector<ex_t> parsed_new_extents;
  if (using_extents) {
    if (!ParseExtentStr(new_extents, &parsed_new_extents))
      errx(1, "Error parsing the new extents");
    new_file.reset(new ExtentsFile(std::move(new_file), parsed_new_extents));
  }

  if (IsOverlapping(old_filename, new_filename, parsed_old_extents,
                    parsed_new_extents)) {
    // New and old file is overlapping, we can not stream output to new file,
    // cache it in the memory and write to the file at the end.
    new_file.reset(new MemoryFile(std::move(new_file), newsize));
  }

  // The oldpos can be negative, but the new pos is only incremented linearly.
  int64_t oldpos = 0;
  uint64_t newpos = 0;
  std::vector<uint8_t> old_buf(1024 * 1024), new_buf(1024 * 1024);
  while (newpos < newsize) {
    int64_t i;
    // Read control data.
    for (i = 0; i <= 2; i++) {
      if (!ReadBZ2(cpfbz2, buf, 8))
        errx(1, "Corrupt patch\n");
      ctrl[i] = ParseInt64(buf);
    }

    // Sanity-check.
    if (ctrl[0] < 0 || ctrl[1] < 0)
      errx(1, "Corrupt patch\n");

    // Sanity-check.
    if (newpos + ctrl[0] > newsize)
      errx(1, "Corrupt patch\n");

    // Add old data to diff string. It is enough to fseek once, at
    // the beginning of the sequence, to avoid unnecessary overhead.
    if ((i = oldpos) < 0) {
      // Write diff block directly to new file without adding old data,
      // because we will skip part where |oldpos| < 0.
      if (!ReadBZ2AndWriteAll(new_file, dpfbz2, -i, new_buf.data(),
                              new_buf.size()))
        errx(1, "Error during ReadBZ2AndWriteAll()");

      i = 0;
    }

    // We just checked that |i| is not negative.
    if (static_cast<uint64_t>(i) != old_file_pos && !old_file->Seek(i))
      err(1, "error seeking input file to offset %" PRId64, i);
    if ((old_file_pos = oldpos + ctrl[0]) > oldsize)
      old_file_pos = oldsize;

    size_t chunk_size = old_file_pos - i;
    while (chunk_size > 0) {
      size_t read_bytes;
      size_t bytes_to_read = std::min(chunk_size, old_buf.size());
      if (!old_file->Read(old_buf.data(), bytes_to_read, &read_bytes))
        err(1, "error reading from input file");
      if (!read_bytes)
        errx(1, "EOF reached while reading from input file");
      // Read same amount of bytes from diff block
      if (!ReadBZ2(dpfbz2, new_buf.data(), read_bytes))
        errx(1, "Corrupt patch\n");
      // new_buf already has data from diff block, adds old data to it.
      for (size_t k = 0; k < read_bytes; k++)
        new_buf[k] += old_buf[k];
      if (!WriteAll(new_file, new_buf.data(), read_bytes))
        err(1, "Error writing new file.");
      chunk_size -= read_bytes;
    }

    // Adjust pointers.
    newpos += ctrl[0];
    oldpos += ctrl[0];

    if (oldpos > static_cast<int64_t>(oldsize)) {
      // Write diff block directly to new file without adding old data,
      // because we skipped part where |oldpos| > oldsize.
      if (!ReadBZ2AndWriteAll(new_file, dpfbz2, oldpos - oldsize,
                              new_buf.data(), new_buf.size()))
        errx(1, "Error during ReadBZ2AndWriteAll()");
    }

    // Sanity-check.
    if (newpos + ctrl[1] > newsize)
      errx(1, "Corrupt patch\n");

    // Read extra block.
    if (!ReadBZ2AndWriteAll(new_file, epfbz2, ctrl[1], new_buf.data(),
                            new_buf.size()))
      errx(1, "Error during ReadBZ2AndWriteAll()");

    // Adjust pointers.
    newpos += ctrl[1];
    oldpos += ctrl[2];
  }

  // Close input file.
  old_file->Close();

  // Clean up the bzip2 reads.
  BZ2_bzReadClose(&bz2err, cpfbz2);
  BZ2_bzReadClose(&bz2err, dpfbz2);
  BZ2_bzReadClose(&bz2err, epfbz2);
  if (fclose(cpf) || fclose(dpf) || fclose(epf))
    err(1, "fclose(%s)", patch_filename);

  if (!new_file->Close())
    err(1, "Error closing new file %s", new_filename);

  return 0;
}

}  // namespace bsdiff
