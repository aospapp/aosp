// SPDX-License-Identifier: GPL-2.0+
/*
 * Elide and patch handling for 'fsverity setup'
 *
 * Copyright (C) 2018 Google LLC
 *
 * Written by Eric Biggers.
 */

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "fsverity_uapi.h"
#include "fsveritysetup.h"

/* An elision or a patch */
struct fsverity_elide_patch {
	u64 offset;	/* byte offset within the original data */
	u64 length;	/* length in bytes */
	bool patch;	/* false if elision, true if patch */
	u8 data[];	/* replacement data (if patch=true) */
};

/* Maximum supported patch size, in bytes */
#define FS_VERITY_MAX_PATCH_SIZE	255

/* Parse an --elide=OFFSET,LENGTH option */
static struct fsverity_elide_patch *parse_elide_option(const char *optarg)
{
	struct fsverity_elide_patch *ext = NULL;
	char *sep, *end;
	unsigned long long offset;
	unsigned long long length;

	sep = strchr(optarg, ',');
	if (!sep || sep == optarg)
		goto invalid;
	errno = 0;
	*sep = '\0';
	offset = strtoull(optarg, &end, 10);
	*sep = ',';
	if (errno || end != sep)
		goto invalid;
	length = strtoull(sep + 1, &end, 10);
	if (errno || *end)
		goto invalid;
	if (length <= 0 || length > UINT64_MAX - offset) {
		error_msg("Invalid length in '--elide=%s'", optarg);
		return NULL;
	}
	ext = xzalloc(sizeof(*ext));
	ext->offset = offset;
	ext->length = length;
	ext->patch = false;
	return ext;

invalid:
	error_msg("Invalid --elide option: '%s'.  Must be formatted as OFFSET,LENGTH",
		  optarg);
	return NULL;
}

/* Parse a --patch=OFFSET,PATCHFILE option */
static struct fsverity_elide_patch *parse_patch_option(const char *optarg)
{
	struct fsverity_elide_patch *ext = NULL;
	struct filedes patchfile = { .fd = -1 };
	char *sep, *end;
	unsigned long long offset;
	u64 length;

	sep = strchr(optarg, ',');
	if (!sep || sep == optarg)
		goto invalid;
	errno = 0;
	*sep = '\0';
	offset = strtoull(optarg, &end, 10);
	*sep = ',';
	if (errno || end != sep)
		goto invalid;
	if (!open_file(&patchfile, sep + 1, O_RDONLY, 0))
		goto out;
	if (!get_file_size(&patchfile, &length))
		goto out;
	if (length <= 0) {
		error_msg("patch file '%s' is empty", patchfile.name);
		goto out;
	}
	if (length > FS_VERITY_MAX_PATCH_SIZE) {
		error_msg("Patch file '%s' is too long.  Max patch size is %d bytes.",
			  patchfile.name, FS_VERITY_MAX_PATCH_SIZE);
		goto out;
	}
	ext = xzalloc(sizeof(*ext) + length);
	ext->offset = offset;
	ext->length = length;
	ext->patch = true;
	if (!full_read(&patchfile, ext->data, length)) {
		free(ext);
		ext = NULL;
	}
out:
	filedes_close(&patchfile);
	return ext;

invalid:
	error_msg("Invalid --patch option: '%s'.  Must be formatted as OFFSET,PATCHFILE",
		  optarg);
	goto out;
}

/* Sort by increasing offset */
static int cmp_elide_patch_exts(const void *_p1, const void *_p2)
{
	const struct fsverity_elide_patch *ext1, *ext2;

	ext1 = *(const struct fsverity_elide_patch **)_p1;
	ext2 = *(const struct fsverity_elide_patch **)_p2;

	if (ext1->offset > ext2->offset)
		return 1;
	if (ext1->offset < ext2->offset)
		return -1;
	return 0;
}

/*
 * Given the lists of --elide and --patch options, validate and load the
 * elisions and patches into @params.
 */
bool load_elisions_and_patches(const struct string_list *elide_opts,
			       const struct string_list *patch_opts,
			       struct fsveritysetup_params *params)
{
	const size_t num_exts = elide_opts->length + patch_opts->length;
	struct fsverity_elide_patch **exts;
	size_t i, j;

	if (num_exts == 0)	/* Normal case: no elisions or patches */
		return true;
	params->num_elisions_and_patches = num_exts;
	exts = xzalloc(num_exts * sizeof(exts[0]));
	params->elisions_and_patches = exts;
	j = 0;

	/* Parse the --elide options */
	for (i = 0; i < elide_opts->length; i++) {
		exts[j] = parse_elide_option(elide_opts->strings[i]);
		if (!exts[j++])
			return false;
	}

	/* Parse the --patch options */
	for (i = 0; i < patch_opts->length; i++) {
		exts[j] = parse_patch_option(patch_opts->strings[i]);
		if (!exts[j++])
			return false;
	}

	/* Sort the elisions and patches by increasing offset */
	qsort(exts, num_exts, sizeof(exts[0]), cmp_elide_patch_exts);

	/* Verify that no elisions or patches overlap */
	for (j = 1; j < num_exts; j++) {
		if (exts[j]->offset <
		    exts[j - 1]->offset + exts[j - 1]->length) {
			error_msg("%s at [%"PRIu64", %"PRIu64") overlaps "
				  "%s at [%"PRIu64", %"PRIu64")",
				  exts[j - 1]->patch ? "Patch" : "Elision",
				  exts[j - 1]->offset,
				  exts[j - 1]->offset + exts[j - 1]->length,
				  exts[j]->patch ? "patch" : "elision",
				  exts[j]->offset,
				  exts[j]->offset + exts[j]->length);
			return false;
		}
	}
	return true;
}

void free_elisions_and_patches(struct fsveritysetup_params *params)
{
	size_t i;

	for (i = 0; i < params->num_elisions_and_patches; i++)
		free(params->elisions_and_patches[i]);
	free(params->elisions_and_patches);
}

/*
 * Given the original file @in of length @in_length bytes, create a temporary
 * file @out_ret and write to it the data with the elisions and patches applied,
 * with the end zero-padded to the next block boundary.  Returns in
 * @out_length_ret the length of the elided/patched file in bytes.
 */
bool apply_elisions_and_patches(const struct fsveritysetup_params *params,
				struct filedes *in, u64 in_length,
				struct filedes *out_ret, u64 *out_length_ret)
{
	struct fsverity_elide_patch **exts = params->elisions_and_patches;
	struct filedes *out = out_ret;
	size_t i;

	for (i = 0; i < params->num_elisions_and_patches; i++) {
		if (exts[i]->offset + exts[i]->length > in_length) {
			error_msg("%s at [%"PRIu64", %"PRIu64") extends beyond end of input file",
				  exts[i]->patch ? "Patch" : "Elision",
				  exts[i]->offset,
				  exts[i]->offset + exts[i]->length);
			return false;
		}
	}

	if (!filedes_seek(in, 0, SEEK_SET))
		return false;

	if (!open_tempfile(out))
		return false;

	for (i = 0; i < params->num_elisions_and_patches; i++) {
		printf("Applying %s: offset=%"PRIu64", length=%"PRIu64"\n",
		       exts[i]->patch ? "patch" : "elision",
		       exts[i]->offset, exts[i]->length);

		if (!copy_file_data(in, out, exts[i]->offset - in->pos))
			return false;

		if (exts[i]->patch &&
		    !full_write(out, exts[i]->data, exts[i]->length))
			return false;

		if (!filedes_seek(in, exts[i]->length, SEEK_CUR))
			return false;
	}
	if (!copy_file_data(in, out, in_length - in->pos))
		return false;
	if (!write_zeroes(out, ALIGN(out->pos, params->blocksize) - out->pos))
		return false;
	*out_length_ret = out->pos;
	return true;
}

/* Calculate the size the elisions and patches will take up when serialized */
size_t total_elide_patch_ext_length(const struct fsveritysetup_params *params)
{
	size_t total = 0;
	size_t i;

	for (i = 0; i < params->num_elisions_and_patches; i++) {
		const struct fsverity_elide_patch *ext =
			params->elisions_and_patches[i];
		size_t inner_len;

		if (ext->patch) {
			inner_len = sizeof(struct fsverity_extension_patch) +
				    ext->length;
		} else {
			inner_len = sizeof(struct fsverity_extension_elide);
		}
		total += FSVERITY_EXTLEN(inner_len);
	}
	return total;
}

/*
 * Append the elide and patch extensions (if any) to the given buffer.
 * The buffer must have enough space; call total_elide_patch_ext_length() first.
 */
void append_elide_patch_exts(void **buf_p,
			     const struct fsveritysetup_params *params)
{
	void *buf = *buf_p;
	size_t i;
	union {
		struct {
			struct fsverity_extension_patch hdr;
			u8 data[FS_VERITY_MAX_PATCH_SIZE];
		} patch;
		struct fsverity_extension_elide elide;
	} u;

	for (i = 0; i < params->num_elisions_and_patches; i++) {
		const struct fsverity_elide_patch *ext =
			params->elisions_and_patches[i];
		int type;
		size_t extlen;

		if (ext->patch) {
			type = FS_VERITY_EXT_PATCH;
			u.patch.hdr.offset = cpu_to_le64(ext->offset);
			ASSERT(ext->length <= sizeof(u.patch.data));
			memcpy(u.patch.data, ext->data, ext->length);
			extlen = sizeof(u.patch.hdr) + ext->length;
		} else {
			type = FS_VERITY_EXT_ELIDE;
			u.elide.offset = cpu_to_le64(ext->offset),
			u.elide.length = cpu_to_le64(ext->length);
			extlen = sizeof(u.elide);
		}
		fsverity_append_extension(&buf, type, &u, extlen);
	}

	*buf_p = buf;
}
