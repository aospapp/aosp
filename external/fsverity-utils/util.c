// SPDX-License-Identifier: GPL-2.0+
/*
 * Utility functions for the 'fsverity' program
 *
 * Copyright (C) 2018 Google LLC
 *
 * Written by Eric Biggers.
 */

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "util.h"

/* ========== Memory allocation ========== */

void *xmalloc(size_t size)
{
	void *p = malloc(size);

	if (!p)
		fatal_error("out of memory");
	return p;
}

void *xzalloc(size_t size)
{
	return memset(xmalloc(size), 0, size);
}

void *xmemdup(const void *mem, size_t size)
{
	return memcpy(xmalloc(size), mem, size);
}

char *xstrdup(const char *s)
{
	return xmemdup(s, strlen(s) + 1);
}

char *xasprintf(const char *format, ...)
{
	va_list va1, va2;
	int size;
	char *s;

	va_start(va1, format);

	va_copy(va2, va1);
	size = vsnprintf(NULL, 0, format, va2);
	va_end(va2);

	ASSERT(size >= 0);
	s = xmalloc(size + 1);
	vsprintf(s, format, va1);

	va_end(va1);
	return s;
}

/* ========== Error messages and assertions ========== */

void do_error_msg(const char *format, va_list va, int err)
{
	fputs("ERROR: ", stderr);
	vfprintf(stderr, format, va);
	if (err)
		fprintf(stderr, ": %s", strerror(err));
	putc('\n', stderr);
}

void error_msg(const char *format, ...)
{
	va_list va;

	va_start(va, format);
	do_error_msg(format, va, 0);
	va_end(va);
}

void error_msg_errno(const char *format, ...)
{
	va_list va;

	va_start(va, format);
	do_error_msg(format, va, errno);
	va_end(va);
}

__noreturn void fatal_error(const char *format, ...)
{
	va_list va;

	va_start(va, format);
	do_error_msg(format, va, 0);
	va_end(va);
	abort();
}

__noreturn void assertion_failed(const char *expr, const char *file, int line)
{
	fatal_error("Assertion failed: %s at %s:%d", expr, file, line);
}

/* ========== File utilities ========== */

bool open_file(struct filedes *file, const char *filename, int flags, int mode)
{
	file->fd = open(filename, flags, mode);
	if (file->fd < 0) {
		error_msg_errno("can't open '%s' for %s", filename,
				(flags & O_ACCMODE) == O_RDONLY ? "reading" :
				(flags & O_ACCMODE) == O_WRONLY ? "writing" :
				"reading and writing");
		return false;
	}
	file->autodelete = false;
	file->name = xstrdup(filename);
	file->pos = 0;
	return true;
}

bool open_tempfile(struct filedes *file)
{
	const char *tmpdir = getenv("TMPDIR") ?: P_tmpdir;
	char *name = xasprintf("%s/fsverity-XXXXXX", tmpdir);

	file->fd = mkstemp(name);
	if (file->fd < 0) {
		error_msg_errno("can't create temporary file");
		free(name);
		return false;
	}
	file->autodelete = true;
	file->name = name;
	file->pos = 0;
	return true;
}

bool get_file_size(struct filedes *file, u64 *size_ret)
{
	struct stat stbuf;

	if (fstat(file->fd, &stbuf) != 0) {
		error_msg_errno("can't stat file '%s'", file->name);
		return false;
	}
	*size_ret = stbuf.st_size;
	return true;
}

bool filedes_seek(struct filedes *file, u64 pos, int whence)
{
	off_t res;

	res = lseek(file->fd, pos, whence);
	if (res < 0) {
		error_msg_errno("seek error on '%s'", file->name);
		return false;
	}
	file->pos = res;
	return true;
}

bool full_read(struct filedes *file, void *buf, size_t count)
{
	while (count) {
		int n = read(file->fd, buf, min(count, INT_MAX));

		if (n < 0) {
			error_msg_errno("reading from '%s'", file->name);
			return false;
		}
		if (n == 0) {
			error_msg("unexpected end-of-file on '%s'", file->name);
			return false;
		}
		buf += n;
		count -= n;
		file->pos += n;
	}
	return true;
}

bool full_pread(struct filedes *file, void *buf, size_t count, u64 offset)
{
	while (count) {
		int n = pread(file->fd, buf, min(count, INT_MAX), offset);

		if (n < 0) {
			error_msg_errno("reading from '%s'", file->name);
			return false;
		}
		if (n == 0) {
			error_msg("unexpected end-of-file on '%s'", file->name);
			return false;
		}
		buf += n;
		count -= n;
		offset += n;
	}
	return true;
}

bool full_write(struct filedes *file, const void *buf, size_t count)
{
	while (count) {
		int n = write(file->fd, buf, min(count, INT_MAX));

		if (n < 0) {
			error_msg_errno("writing to '%s'", file->name);
			return false;
		}
		buf += n;
		count -= n;
		file->pos += n;
	}
	return true;
}

bool full_pwrite(struct filedes *file, const void *buf, size_t count,
		 u64 offset)
{
	while (count) {
		int n = pwrite(file->fd, buf, min(count, INT_MAX), offset);

		if (n < 0) {
			error_msg_errno("writing to '%s'", file->name);
			return false;
		}
		buf += n;
		count -= n;
		offset += n;
	}
	return true;
}

/* Copy 'count' bytes of data from 'src' to 'dst' */
bool copy_file_data(struct filedes *src, struct filedes *dst, u64 count)
{
	char buf[4096];

	while (count) {
		size_t n = min(count, sizeof(buf));

		if (!full_read(src, buf, n))
			return false;
		if (!full_write(dst, buf, n))
			return false;
		count -= n;
	}
	return true;
}

/* Write 'count' bytes of zeroes to the file */
bool write_zeroes(struct filedes *file, u64 count)
{
	char buf[4096];

	memset(buf, 0, min(count, sizeof(buf)));

	while (count) {
		size_t n = min(count, sizeof(buf));

		if (!full_write(file, buf, n))
			return false;
		count -= n;
	}
	return true;
}

bool filedes_close(struct filedes *file)
{
	int res;

	if (file->fd < 0)
		return true;
	res = close(file->fd);
	if (res != 0)
		error_msg_errno("closing '%s'", file->name);
	if (file->autodelete)
		(void)unlink(file->name);
	file->fd = -1;
	free(file->name);
	file->name = NULL;
	return res == 0;
}

/* ========== String utilities ========== */

static int hex2bin_char(char c)
{
	if (c >= '0' && c <= '9')
		return c - '0';
	if (c >= 'a' && c <= 'f')
		return 10 + (c - 'a');
	if (c >= 'A' && c <= 'F')
		return 10 + (c - 'A');
	return -1;
}

bool hex2bin(const char *hex, u8 *bin, size_t bin_len)
{
	if (strlen(hex) != 2 * bin_len)
		return false;

	while (bin_len--) {
		int hi = hex2bin_char(*hex++);
		int lo = hex2bin_char(*hex++);

		if (hi < 0 || lo < 0)
			return false;
		*bin++ = (hi << 4) | lo;
	}
	return true;
}

static char bin2hex_char(u8 nibble)
{
	ASSERT(nibble <= 0xf);

	if (nibble < 10)
		return '0' + nibble;
	return 'a' + (nibble - 10);
}

void bin2hex(const u8 *bin, size_t bin_len, char *hex)
{
	while (bin_len--) {
		*hex++ = bin2hex_char(*bin >> 4);
		*hex++ = bin2hex_char(*bin & 0xf);
		bin++;
	}
	*hex = '\0';
}

void string_list_append(struct string_list *list, char *string)
{
	ASSERT(list->length <= list->capacity);
	if (list->length == list->capacity) {
		list->capacity = (list->capacity * 2) + 4;
		list->strings = realloc(list->strings,
					sizeof(list->strings[0]) *
					list->capacity);
		if (!list->strings)
			fatal_error("out of memory");
	}
	list->strings[list->length++] = string;
}

void string_list_destroy(struct string_list *list)
{
	free(list->strings);
	memset(list, 0, sizeof(*list));
}
