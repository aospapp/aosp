/* SPDX-License-Identifier: GPL-2.0+ */
/*
 * Utility functions and macros for the 'fsverity' program
 *
 * Copyright (C) 2018 Google LLC
 */
#ifndef UTIL_H
#define UTIL_H

#include <inttypes.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;
typedef uint64_t u64;

#ifndef __force
#  ifdef __CHECKER__
#    define __force	__attribute__((force))
#  else
#    define __force
#  endif
#endif

#ifndef __printf
#  define __printf(fmt_idx, vargs_idx) \
	__attribute__((format(printf, fmt_idx, vargs_idx)))
#endif

#ifndef __noreturn
#  define __noreturn	__attribute__((noreturn))
#endif

#ifndef __cold
#  define __cold	__attribute__((cold))
#endif

#define min(a, b) ({			\
	__typeof__(a) _a = (a);		\
	__typeof__(b) _b = (b);		\
	_a < _b ? _a : _b;		\
})
#define max(a, b) ({			\
	__typeof__(a) _a = (a);		\
	__typeof__(b) _b = (b);		\
	_a > _b ? _a : _b;		\
})

#define ARRAY_SIZE(A)		(sizeof(A) / sizeof((A)[0]))

#define DIV_ROUND_UP(n, d)	(((n) + (d) - 1) / (d))

/*
 * Round 'v' up to the next 'alignment'-byte aligned boundary.
 * 'alignment' must be a power of 2.
 */
#define ALIGN(v, alignment)	(((v) + ((alignment) - 1)) & ~((alignment) - 1))

static inline bool is_power_of_2(unsigned long n)
{
	return n != 0 && ((n & (n - 1)) == 0);
}

static inline int ilog2(unsigned long n)
{
	return (8 * sizeof(n) - 1) - __builtin_clzl(n);
}

/* ========== Endianness conversion ========== */

#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#  define cpu_to_le16(v)	((__force __le16)(u16)(v))
#  define le16_to_cpu(v)	((__force u16)(__le16)(v))
#  define cpu_to_le32(v)	((__force __le32)(u32)(v))
#  define le32_to_cpu(v)	((__force u32)(__le32)(v))
#  define cpu_to_le64(v)	((__force __le64)(u64)(v))
#  define le64_to_cpu(v)	((__force u64)(__le64)(v))
#  define cpu_to_be16(v)	((__force __be16)__builtin_bswap16(v))
#  define be16_to_cpu(v)	(__builtin_bswap16((__force u16)(v)))
#  define cpu_to_be32(v)	((__force __be32)__builtin_bswap32(v))
#  define be32_to_cpu(v)	(__builtin_bswap32((__force u32)(v)))
#  define cpu_to_be64(v)	((__force __be64)__builtin_bswap64(v))
#  define be64_to_cpu(v)	(__builtin_bswap64((__force u64)(v)))
#else
#  define cpu_to_le16(v)	((__force __le16)__builtin_bswap16(v))
#  define le16_to_cpu(v)	(__builtin_bswap16((__force u16)(v)))
#  define cpu_to_le32(v)	((__force __le32)__builtin_bswap32(v))
#  define le32_to_cpu(v)	(__builtin_bswap32((__force u32)(v)))
#  define cpu_to_le64(v)	((__force __le64)__builtin_bswap64(v))
#  define le64_to_cpu(v)	(__builtin_bswap64((__force u64)(v)))
#  define cpu_to_be16(v)	((__force __be16)(u16)(v))
#  define be16_to_cpu(v)	((__force u16)(__be16)(v))
#  define cpu_to_be32(v)	((__force __be32)(u32)(v))
#  define be32_to_cpu(v)	((__force u32)(__be32)(v))
#  define cpu_to_be64(v)	((__force __be64)(u64)(v))
#  define be64_to_cpu(v)	((__force u64)(__be64)(v))
#endif

/* ========== Memory allocation ========== */

void *xmalloc(size_t size);
void *xzalloc(size_t size);
void *xmemdup(const void *mem, size_t size);
char *xstrdup(const char *s);
__printf(1, 2) char *xasprintf(const char *format, ...);

/* ========== Error messages and assertions ========== */

__cold void do_error_msg(const char *format, va_list va, int err);
__printf(1, 2) __cold void error_msg(const char *format, ...);
__printf(1, 2) __cold void error_msg_errno(const char *format, ...);
__printf(1, 2) __cold __noreturn void fatal_error(const char *format, ...);
__cold __noreturn void assertion_failed(const char *expr,
					const char *file, int line);

#define ASSERT(e) ({ if (!(e)) assertion_failed(#e, __FILE__, __LINE__); })

/* ========== File utilities ========== */

struct filedes {
	int fd;
	bool autodelete;	/* unlink when closed? */
	char *name;		/* filename, for logging or error messages */
	u64 pos;		/* lseek() position */
};

bool open_file(struct filedes *file, const char *filename, int flags, int mode);
bool open_tempfile(struct filedes *file);
bool get_file_size(struct filedes *file, u64 *size_ret);
bool filedes_seek(struct filedes *file, u64 pos, int whence);
bool full_read(struct filedes *file, void *buf, size_t count);
bool full_pread(struct filedes *file, void *buf, size_t count, u64 offset);
bool full_write(struct filedes *file, const void *buf, size_t count);
bool full_pwrite(struct filedes *file, const void *buf, size_t count,
		 u64 offset);
bool copy_file_data(struct filedes *src, struct filedes *dst, u64 length);
bool write_zeroes(struct filedes *file, u64 length);
bool filedes_close(struct filedes *file);

/* ========== String utilities ========== */

bool hex2bin(const char *hex, u8 *bin, size_t bin_len);
void bin2hex(const u8 *bin, size_t bin_len, char *hex);

struct string_list {
	char **strings;
	size_t length;
	size_t capacity;
};

#define STRING_LIST_INITIALIZER { .strings = NULL, .length = 0, .capacity = 0 }
#define STRING_LIST(_list) struct string_list _list = STRING_LIST_INITIALIZER

void string_list_append(struct string_list *list, char *string);
void string_list_destroy(struct string_list *list);

#endif /* UTIL_H */
