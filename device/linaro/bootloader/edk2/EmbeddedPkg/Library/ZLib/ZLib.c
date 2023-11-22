/** @file

  Copyright (c) 2017, Linaro. All rights reserved.

  This program and the accompanying materials
  are licensed and made available under the terms and conditions of the BSD License
  which accompanies this distribution.  The full text of the license may be found at
  http://opensource.org/licenses/bsd-license.php

  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/

#include <Library/BaseLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/ZLib.h>

#include "zlib.h"

#define CHUNK               32768

EFI_STATUS
GzipDecompress (
  IN     VOID              *CompressData,
  IN     UINTN             *CompressDataLen,
  IN     VOID              *UncompressData,
  IN OUT UINTN             *UncompressDataLen
  )
{
  INTN           ret;
  INTN           have;
  unsigned char  in[CHUNK];
  unsigned char  out[CHUNK];
  UINTN          comp_offset;
  UINTN          uncomp_offset;
  UINTN          left;
  z_stream       stream = { 0 };

  // Speed up the decompress
  comp_offset = 0;
  uncomp_offset = 0;
  left = *CompressDataLen;

  stream.zalloc = NULL;
  stream.zfree = NULL;
  stream.opaque = NULL;
  stream.avail_in = CHUNK;

  if (inflateInit2(&stream, MAX_WBITS + 16) != Z_OK)
  {
    return EFI_ABORTED;
  }
  do
  {
    /* decompress until deflate stream ends */
    if (left > CHUNK)
    {
      stream.avail_in = CHUNK;
      left = left - CHUNK;
    }
    else
    {
      stream.avail_in = left;
      left = 0;
    }
    if (stream.avail_in == 0) {
      break;
    }
    stream.next_in = in;
    CopyMem (in, CompressData + comp_offset, stream.avail_in);
    comp_offset += stream.avail_in;
    /* run inflate() on input until output buffer not full */
    do
    {
      stream.avail_out = CHUNK;
      stream.next_out = out;
      ret = inflate(&stream, Z_NO_FLUSH);
      switch (ret)
      {
        case Z_NEED_DICT:
          ret = Z_DATA_ERROR;        /* and fall through */
        case Z_DATA_ERROR:
        case Z_MEM_ERROR:
          inflateEnd(&stream);
          return ret;
      }
      have = CHUNK - stream.avail_out;
      CopyMem (UncompressData + uncomp_offset, out, have);
      uncomp_offset += have;
    } while (stream.avail_out == 0);
    /* done when inflate() says it's done */
  } while (ret != Z_STREAM_END);
  inflateEnd(&stream);
  *(unsigned char *)(UncompressData + uncomp_offset) = '\0';
  if (*CompressDataLen > stream.total_in)
  {
    *CompressDataLen = stream.total_in;
  }
  *UncompressDataLen = stream.total_out;
  return EFI_SUCCESS;
}
