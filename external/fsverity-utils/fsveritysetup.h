/* SPDX-License-Identifier: GPL-2.0+ */
#ifndef FSVERITYSETUP_H
#define FSVERITYSETUP_H

#include "util.h"

struct fsveritysetup_params {
	const struct fsverity_hash_alg *hash_alg;
	u8 *salt;
	size_t saltlen;
	int blocksize;
	int blockbits;			/* ilog2(blocksize) */
	unsigned int hashes_per_block;	/* blocksize / digest_size */
	const char *signing_key_file;
	const char *signing_cert_file;
	const char *signature_file;
	struct fsverity_elide_patch **elisions_and_patches;
	size_t num_elisions_and_patches;
};

void fsverity_append_extension(void **buf_p, int type,
			       const void *ext, size_t extlen);

#define FSVERITY_EXTLEN(inner_len)	\
	ALIGN(sizeof(struct fsverity_extension) + (inner_len), 8)

/* elide_patch.c */
bool load_elisions_and_patches(const struct string_list *elide_opts,
			       const struct string_list *patch_opts,
			       struct fsveritysetup_params *params);
void free_elisions_and_patches(struct fsveritysetup_params *params);
bool apply_elisions_and_patches(const struct fsveritysetup_params *params,
				struct filedes *in, u64 in_length,
				struct filedes *out_ret, u64 *out_length_ret);
size_t total_elide_patch_ext_length(const struct fsveritysetup_params *params);
void append_elide_patch_exts(void **buf_p,
			     const struct fsveritysetup_params *params);
/* sign.c */
int append_signed_measurement(struct filedes *out,
			      const struct fsveritysetup_params *params,
			      const u8 *measurement);

#endif /* FSVERITYSETUP_H */
