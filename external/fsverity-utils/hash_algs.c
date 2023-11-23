// SPDX-License-Identifier: GPL-2.0+
/*
 * fs-verity hash algorithms
 *
 * Copyright (C) 2018 Google LLC
 *
 * Written by Eric Biggers.
 */

#include <openssl/evp.h>
#include <stdlib.h>
#include <string.h>

#include "fsverity_uapi.h"
#include "hash_algs.h"

static void free_hash_ctx(struct hash_ctx *ctx)
{
	free(ctx);
}

/* ========== libcrypto (OpenSSL) wrappers ========== */

struct openssl_hash_ctx {
	struct hash_ctx base;	/* must be first */
	EVP_MD_CTX *md_ctx;
	const EVP_MD *md;
};

static void openssl_digest_init(struct hash_ctx *_ctx)
{
	struct openssl_hash_ctx *ctx = (void *)_ctx;

	if (EVP_DigestInit_ex(ctx->md_ctx, ctx->md, NULL) != 1)
		fatal_error("EVP_DigestInit_ex() failed for algorithm '%s'",
			    ctx->base.alg->name);
}

static void openssl_digest_update(struct hash_ctx *_ctx,
				  const void *data, size_t size)
{
	struct openssl_hash_ctx *ctx = (void *)_ctx;

	if (EVP_DigestUpdate(ctx->md_ctx, data, size) != 1)
		fatal_error("EVP_DigestUpdate() failed for algorithm '%s'",
			    ctx->base.alg->name);
}

static void openssl_digest_final(struct hash_ctx *_ctx, u8 *digest)
{
	struct openssl_hash_ctx *ctx = (void *)_ctx;

	if (EVP_DigestFinal_ex(ctx->md_ctx, digest, NULL) != 1)
		fatal_error("EVP_DigestFinal_ex() failed for algorithm '%s'",
			    ctx->base.alg->name);
}

static void openssl_digest_ctx_free(struct hash_ctx *_ctx)
{
	struct openssl_hash_ctx *ctx = (void *)_ctx;

	/*
	 * OpenSSL 1.1.0 renamed EVP_MD_CTX_destroy() to EVP_MD_CTX_free() but
	 * kept the old name as a macro.  Use the old name for compatibility
	 * with older OpenSSL versions.
	 */
	EVP_MD_CTX_destroy(ctx->md_ctx);
	free(ctx);
}

static struct hash_ctx *
openssl_digest_ctx_create(const struct fsverity_hash_alg *alg, const EVP_MD *md)
{
	struct openssl_hash_ctx *ctx;

	ctx = xzalloc(sizeof(*ctx));
	ctx->base.alg = alg;
	ctx->base.init = openssl_digest_init;
	ctx->base.update = openssl_digest_update;
	ctx->base.final = openssl_digest_final;
	ctx->base.free = openssl_digest_ctx_free;
	/*
	 * OpenSSL 1.1.0 renamed EVP_MD_CTX_create() to EVP_MD_CTX_new() but
	 * kept the old name as a macro.  Use the old name for compatibility
	 * with older OpenSSL versions.
	 */
	ctx->md_ctx = EVP_MD_CTX_create();
	if (!ctx->md_ctx)
		fatal_error("out of memory");

	ctx->md = md;
	ASSERT(EVP_MD_size(md) == alg->digest_size);

	return &ctx->base;
}

static struct hash_ctx *create_sha256_ctx(const struct fsverity_hash_alg *alg)
{
	return openssl_digest_ctx_create(alg, EVP_sha256());
}

static struct hash_ctx *create_sha512_ctx(const struct fsverity_hash_alg *alg)
{
	return openssl_digest_ctx_create(alg, EVP_sha512());
}

/* ========== CRC-32C ========== */

/*
 * There are faster ways to calculate CRC's, but for now we just use the
 * 256-entry table method as it's portable and not too complex.
 */

#include "crc32c_table.h"

struct crc32c_hash_ctx {
	struct hash_ctx base;	/* must be first */
	u32 remainder;
};

static void crc32c_init(struct hash_ctx *_ctx)
{
	struct crc32c_hash_ctx *ctx = (void *)_ctx;

	ctx->remainder = ~0;
}

static void crc32c_update(struct hash_ctx *_ctx, const void *data, size_t size)
{
	struct crc32c_hash_ctx *ctx = (void *)_ctx;
	const u8 *p = data;
	u32 r = ctx->remainder;

	while (size--)
		r = (r >> 8) ^ crc32c_table[(u8)r ^ *p++];

	ctx->remainder = r;
}

static void crc32c_final(struct hash_ctx *_ctx, u8 *digest)
{
	struct crc32c_hash_ctx *ctx = (void *)_ctx;
	__le32 remainder = cpu_to_le32(~ctx->remainder);

	memcpy(digest, &remainder, sizeof(remainder));
}

static struct hash_ctx *create_crc32c_ctx(const struct fsverity_hash_alg *alg)
{
	struct crc32c_hash_ctx *ctx = xzalloc(sizeof(*ctx));

	ctx->base.alg = alg;
	ctx->base.init = crc32c_init;
	ctx->base.update = crc32c_update;
	ctx->base.final = crc32c_final;
	ctx->base.free = free_hash_ctx;
	return &ctx->base;
}

/* ========== Hash algorithm definitions ========== */

const struct fsverity_hash_alg fsverity_hash_algs[] = {
	[FS_VERITY_ALG_SHA256] = {
		.name = "sha256",
		.digest_size = 32,
		.cryptographic = true,
		.create_ctx = create_sha256_ctx,
	},
	[FS_VERITY_ALG_SHA512] = {
		.name = "sha512",
		.digest_size = 64,
		.cryptographic = true,
		.create_ctx = create_sha512_ctx,
	},
	[FS_VERITY_ALG_CRC32C] = {
		.name = "crc32c",
		.digest_size = 4,
		.create_ctx = create_crc32c_ctx,
	},
};

const struct fsverity_hash_alg *find_hash_alg_by_name(const char *name)
{
	int i;

	for (i = 0; i < ARRAY_SIZE(fsverity_hash_algs); i++) {
		if (fsverity_hash_algs[i].name &&
		    !strcmp(name, fsverity_hash_algs[i].name))
			return &fsverity_hash_algs[i];
	}
	error_msg("unknown hash algorithm: '%s'", name);
	fputs("Available hash algorithms: ", stderr);
	show_all_hash_algs(stderr);
	putc('\n', stderr);
	return NULL;
}

const struct fsverity_hash_alg *find_hash_alg_by_num(unsigned int num)
{
	if (num < ARRAY_SIZE(fsverity_hash_algs) &&
	    fsverity_hash_algs[num].name)
		return &fsverity_hash_algs[num];

	return NULL;
}

void show_all_hash_algs(FILE *fp)
{
	int i;
	const char *sep = "";

	for (i = 0; i < ARRAY_SIZE(fsverity_hash_algs); i++) {
		if (fsverity_hash_algs[i].name) {
			fprintf(fp, "%s%s", sep, fsverity_hash_algs[i].name);
			sep = ", ";
		}
	}
}
