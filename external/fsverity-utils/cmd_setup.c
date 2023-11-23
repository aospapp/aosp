// SPDX-License-Identifier: GPL-2.0+
/*
 * The 'fsverity setup' command
 *
 * Copyright (C) 2018 Google LLC
 *
 * Written by Eric Biggers.
 */

#include <fcntl.h>
#include <getopt.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "commands.h"
#include "fsverity_uapi.h"
#include "fsveritysetup.h"
#include "hash_algs.h"

enum {
	OPT_HASH,
	OPT_SALT,
	OPT_BLOCKSIZE,
	OPT_SIGNING_KEY,
	OPT_SIGNING_CERT,
	OPT_SIGNATURE,
	OPT_ELIDE,
	OPT_PATCH,
};

static const struct option longopts[] = {
	{"hash",		required_argument, NULL, OPT_HASH},
	{"salt",		required_argument, NULL, OPT_SALT},
	{"blocksize",		required_argument, NULL, OPT_BLOCKSIZE},
	{"signing-key",		required_argument, NULL, OPT_SIGNING_KEY},
	{"signing-cert",	required_argument, NULL, OPT_SIGNING_CERT},
	{"signature",		required_argument, NULL, OPT_SIGNATURE},
	{"elide",		required_argument, NULL, OPT_ELIDE},
	{"patch",		required_argument, NULL, OPT_PATCH},
	{NULL, 0, NULL, 0}
};

/* Parse the --blocksize=BLOCKSIZE option */
static bool parse_blocksize_option(const char *opt, int *blocksize_ret)
{
	char *end;
	unsigned long n = strtoul(opt, &end, 10);

	if (n <= 0 || n >= INT32_MAX || *end || !is_power_of_2(n)) {
		error_msg("Invalid block size: %s.  Must be power of 2", opt);
		return false;
	}
	*blocksize_ret = n;
	return true;
}

#define FS_VERITY_MAX_LEVELS	64

/*
 * Calculate the depth of the Merkle tree, then create a map from level to the
 * block offset at which that level's hash blocks start.  Level 'depth - 1' is
 * the root and is stored first in the file, in the first block following the
 * original data.  Level 0 is the "leaf" level: it's directly "above" the data
 * blocks and is stored last in the file.
 */
static void compute_tree_layout(u64 data_size, u64 tree_offset, int blockbits,
				unsigned int hashes_per_block,
				u64 hash_lvl_region_idx[FS_VERITY_MAX_LEVELS],
				int *depth_ret, u64 *tree_end_ret)
{
	u64 blocks = data_size >> blockbits;
	u64 offset = tree_offset >> blockbits;
	int depth = 0;
	int i;

	ASSERT(data_size > 0);
	ASSERT(data_size % (1 << blockbits) == 0);
	ASSERT(tree_offset % (1 << blockbits) == 0);
	ASSERT(hashes_per_block >= 2);

	while (blocks > 1) {
		ASSERT(depth < FS_VERITY_MAX_LEVELS);
		blocks = DIV_ROUND_UP(blocks, hashes_per_block);
		hash_lvl_region_idx[depth++] = blocks;
	}
	for (i = depth - 1; i >= 0; i--) {
		u64 next_count = hash_lvl_region_idx[i];

		hash_lvl_region_idx[i] = offset;
		offset += next_count;
	}
	*depth_ret = depth;
	*tree_end_ret = offset << blockbits;
}

/*
 * Build a Merkle tree (hash tree) over the data of a file.
 *
 * @params: Block size, hashes per block, and salt
 * @hash: Handle for the hash algorithm
 * @data_file: input data file
 * @data_size: size of data file in bytes; must be aligned to ->blocksize
 * @tree_file: output tree file
 * @tree_offset: byte offset in tree file at which to write the tree;
 *		 must be aligned to ->blocksize
 * @tree_end_ret: On success, the byte offset in the tree file of the end of the
 *		  tree is written here
 * @root_hash_ret: On success, the Merkle tree root hash is written here
 *
 * Return: exit status code (0 on success, nonzero on failure)
 */
static int build_merkle_tree(const struct fsveritysetup_params *params,
			     struct hash_ctx *hash,
			     struct filedes *data_file, u64 data_size,
			     struct filedes *tree_file, u64 tree_offset,
			     u64 *tree_end_ret, u8 *root_hash_ret)
{
	const unsigned int digest_size = hash->alg->digest_size;
	int depth;
	u64 hash_lvl_region_idx[FS_VERITY_MAX_LEVELS];
	u8 *data_to_hash = NULL;
	u8 *pending_hashes = NULL;
	unsigned int pending_hash_bytes;
	u64 nr_hashes_at_this_lvl;
	int lvl;
	int status;

	compute_tree_layout(data_size, tree_offset, params->blockbits,
			    params->hashes_per_block, hash_lvl_region_idx,
			    &depth, tree_end_ret);

	/* Allocate block buffers */
	data_to_hash = xmalloc(params->blocksize);
	pending_hashes = xmalloc(params->blocksize);
	pending_hash_bytes = 0;
	nr_hashes_at_this_lvl = data_size >> params->blockbits;

	/*
	 * Generate each level of the Merkle tree, starting at the leaf level
	 * ('lvl == 0') and ascending to the root node ('lvl == depth - 1').
	 * Then at the end ('lvl == depth'), calculate the root node's hash.
	 */
	for (lvl = 0; lvl <= depth; lvl++) {
		u64 i;

		for (i = 0; i < nr_hashes_at_this_lvl; i++) {
			struct filedes *file;
			u64 blk_idx;

			hash_init(hash);
			hash_update(hash, params->salt, params->saltlen);

			if (lvl == 0) {
				/* Leaf: hashing a data block */
				file = data_file;
				blk_idx = i;
			} else {
				/* Non-leaf: hashing a hash block */
				file = tree_file;
				blk_idx = hash_lvl_region_idx[lvl - 1] + i;
			}
			if (!full_pread(file, data_to_hash, params->blocksize,
					blk_idx << params->blockbits))
				goto out_err;
			hash_update(hash, data_to_hash, params->blocksize);

			hash_final(hash, &pending_hashes[pending_hash_bytes]);
			pending_hash_bytes += digest_size;

			if (lvl == depth) {
				/* Root hash */
				ASSERT(nr_hashes_at_this_lvl == 1);
				ASSERT(pending_hash_bytes == digest_size);
				memcpy(root_hash_ret, pending_hashes,
				       digest_size);
				status = 0;
				goto out;
			}

			if (pending_hash_bytes + digest_size > params->blocksize
			    || i + 1 == nr_hashes_at_this_lvl) {
				/* Flush the pending hash block */
				memset(&pending_hashes[pending_hash_bytes], 0,
				       params->blocksize - pending_hash_bytes);
				blk_idx = hash_lvl_region_idx[lvl] +
					  (i / params->hashes_per_block);
				if (!full_pwrite(tree_file,
						 pending_hashes,
						 params->blocksize,
						 blk_idx << params->blockbits))
					goto out_err;
				pending_hash_bytes = 0;
			}
		}

		nr_hashes_at_this_lvl = DIV_ROUND_UP(nr_hashes_at_this_lvl,
						     params->hashes_per_block);
	}
	ASSERT(0); /* unreachable; should exit via "Root hash" case above */
out_err:
	status = 1;
out:
	free(data_to_hash);
	free(pending_hashes);
	return status;
}

/*
 * Append to the buffer @*buf_p an extension (variable-length metadata) item of
 * type @type, containing the data @ext of length @extlen bytes.
 */
void fsverity_append_extension(void **buf_p, int type,
			       const void *ext, size_t extlen)
{
	void *buf = *buf_p;
	struct fsverity_extension *hdr = buf;

	hdr->type = cpu_to_le16(type);
	hdr->length = cpu_to_le32(sizeof(*hdr) + extlen);
	hdr->reserved = 0;
	buf += sizeof(*hdr);
	memcpy(buf, ext, extlen);
	buf += extlen;
	memset(buf, 0, -extlen & 7);
	buf += -extlen & 7;
	ASSERT(buf - *buf_p == FSVERITY_EXTLEN(extlen));
	*buf_p = buf;
}

/*
 * Append the authenticated portion of the fs-verity descriptor to 'out', in the
 * process updating 'hash' with the data written.
 */
static int append_fsverity_descriptor(const struct fsveritysetup_params *params,
				      u64 filesize, const u8 *root_hash,
				      struct filedes *out,
				      struct hash_ctx *hash)
{
	size_t desc_auth_len;
	void *buf;
	struct fsverity_descriptor *desc;
	u16 auth_ext_count;
	int status;

	desc_auth_len = sizeof(*desc);
	desc_auth_len += FSVERITY_EXTLEN(params->hash_alg->digest_size);
	if (params->saltlen)
		desc_auth_len += FSVERITY_EXTLEN(params->saltlen);
	desc_auth_len += total_elide_patch_ext_length(params);
	desc = buf = xzalloc(desc_auth_len);

	memcpy(desc->magic, FS_VERITY_MAGIC, sizeof(desc->magic));
	desc->major_version = 1;
	desc->minor_version = 0;
	desc->log_data_blocksize = params->blockbits;
	desc->log_tree_blocksize = params->blockbits;
	desc->data_algorithm = cpu_to_le16(params->hash_alg -
					   fsverity_hash_algs);
	desc->tree_algorithm = desc->data_algorithm;
	desc->orig_file_size = cpu_to_le64(filesize);

	auth_ext_count = 1; /* root hash */
	if (params->saltlen)
		auth_ext_count++;
	auth_ext_count += params->num_elisions_and_patches;
	desc->auth_ext_count = cpu_to_le16(auth_ext_count);

	buf += sizeof(*desc);
	fsverity_append_extension(&buf, FS_VERITY_EXT_ROOT_HASH,
				  root_hash, params->hash_alg->digest_size);
	if (params->saltlen)
		fsverity_append_extension(&buf, FS_VERITY_EXT_SALT,
					  params->salt, params->saltlen);
	append_elide_patch_exts(&buf, params);
	ASSERT(buf - (void *)desc == desc_auth_len);

	hash_update(hash, desc, desc_auth_len);
	if (!full_write(out, desc, desc_auth_len))
		goto out_err;
	status = 0;
out:
	free(desc);
	return status;

out_err:
	status = 1;
	goto out;
}

/*
 * Append any needed unauthenticated extension items: currently, just possibly a
 * PKCS7_SIGNATURE item containing the signed file measurement.
 */
static int
append_unauthenticated_extensions(struct filedes *out,
				  const struct fsveritysetup_params *params,
				  const u8 *measurement)
{
	u16 unauth_ext_count = 0;
	struct {
		__le16 unauth_ext_count;
		__le16 pad[3];
	} hdr;
	bool have_sig = params->signing_key_file || params->signature_file;

	if (have_sig)
		unauth_ext_count++;

	ASSERT(sizeof(hdr) % 8 == 0);
	memset(&hdr, 0, sizeof(hdr));
	hdr.unauth_ext_count = cpu_to_le16(unauth_ext_count);

	if (!full_write(out, &hdr, sizeof(hdr)))
		return 1;

	if (have_sig)
		return append_signed_measurement(out, params, measurement);

	return 0;
}

static int append_footer(struct filedes *out, u64 desc_offset)
{
	struct fsverity_footer ftr;
	u32 offset = (out->pos + sizeof(ftr)) - desc_offset;

	ftr.desc_reverse_offset = cpu_to_le32(offset);
	memcpy(ftr.magic, FS_VERITY_MAGIC, sizeof(ftr.magic));

	if (!full_write(out, &ftr, sizeof(ftr)))
		return 1;
	return 0;
}

static int fsveritysetup(const char *infile, const char *outfile,
			 const struct fsveritysetup_params *params)
{
	struct filedes _in = { .fd = -1 };
	struct filedes _out = { .fd = -1 };
	struct filedes _tmp = { .fd = -1 };
	struct hash_ctx *hash = NULL;
	struct filedes *in = &_in, *out = &_out, *src;
	u64 filesize;
	u64 aligned_filesize;
	u64 src_filesize;
	u64 tree_end_offset;
	u8 root_hash[FS_VERITY_MAX_DIGEST_SIZE];
	u8 measurement[FS_VERITY_MAX_DIGEST_SIZE];
	char hash_hex[FS_VERITY_MAX_DIGEST_SIZE * 2 + 1];
	int status;

	if (!open_file(in, infile, (infile == outfile ? O_RDWR : O_RDONLY), 0))
		goto out_err;

	if (!get_file_size(in, &filesize))
		goto out_err;

	if (filesize <= 0) {
		error_msg("input file is empty: '%s'", infile);
		goto out_err;
	}

	if (infile == outfile) {
		/*
		 * Invoked with one file argument: we're appending verity
		 * metadata to an existing file.
		 */
		out = in;
		if (!filedes_seek(out, filesize, SEEK_SET))
			goto out_err;
	} else {
		/*
		 * Invoked with two file arguments: we're copying the first file
		 * to the second file, then appending verity metadata to it.
		 */
		if (!open_file(out, outfile, O_RDWR|O_CREAT|O_TRUNC, 0644))
			goto out_err;
		if (!copy_file_data(in, out, filesize))
			goto out_err;
	}

	/* Zero-pad the output file to the next block boundary */
	aligned_filesize = ALIGN(filesize, params->blocksize);
	if (!write_zeroes(out, aligned_filesize - filesize))
		goto out_err;

	if (params->num_elisions_and_patches) {
		/* Merkle tree is built over temporary elided/patched file */
		src = &_tmp;
		if (!apply_elisions_and_patches(params, in, filesize,
						src, &src_filesize))
			goto out_err;
	} else {
		/* Merkle tree is built over original file */
		src = out;
		src_filesize = aligned_filesize;
	}

	hash = hash_create(params->hash_alg);

	/* Build the file's Merkle tree and calculate its root hash */
	status = build_merkle_tree(params, hash, src, src_filesize,
				   out, aligned_filesize,
				   &tree_end_offset, root_hash);
	if (status)
		goto out;
	if (!filedes_seek(out, tree_end_offset, SEEK_SET))
		goto out_err;

	/* Append the additional needed metadata */

	hash_init(hash);
	status = append_fsverity_descriptor(params, filesize, root_hash,
					    out, hash);
	if (status)
		goto out;
	hash_final(hash, measurement);

	status = append_unauthenticated_extensions(out, params, measurement);
	if (status)
		goto out;

	status = append_footer(out, tree_end_offset);
	if (status)
		goto out;

	bin2hex(measurement, params->hash_alg->digest_size, hash_hex);
	printf("File measurement: %s:%s\n", params->hash_alg->name, hash_hex);
	status = 0;
out:
	hash_free(hash);
	if (status != 0 && out->fd >= 0) {
		/* Error occurred; undo what we wrote */
		if (in == out)
			(void)ftruncate(out->fd, filesize);
		else
			out->autodelete = true;
	}
	filedes_close(&_in);
	filedes_close(&_tmp);
	if (!filedes_close(&_out) && status == 0)
		status = 1;
	return status;

out_err:
	status = 1;
	goto out;
}

int fsverity_cmd_setup(const struct fsverity_command *cmd,
		       int argc, char *argv[])
{
	struct fsveritysetup_params params = {
		.hash_alg = DEFAULT_HASH_ALG,
	};
	STRING_LIST(elide_opts);
	STRING_LIST(patch_opts);
	int c;
	int status;

	while ((c = getopt_long(argc, argv, "", longopts, NULL)) != -1) {
		switch (c) {
		case OPT_HASH:
			params.hash_alg = find_hash_alg_by_name(optarg);
			if (!params.hash_alg)
				goto out_usage;
			break;
		case OPT_SALT:
			if (params.salt) {
				error_msg("--salt can only be specified once");
				goto out_usage;
			}
			params.saltlen = strlen(optarg) / 2;
			params.salt = xmalloc(params.saltlen);
			if (!hex2bin(optarg, params.salt, params.saltlen)) {
				error_msg("salt is not a valid hex string");
				goto out_usage;
			}
			break;
		case OPT_BLOCKSIZE:
			if (!parse_blocksize_option(optarg, &params.blocksize))
				goto out_usage;
			break;
		case OPT_SIGNING_KEY:
			params.signing_key_file = optarg;
			break;
		case OPT_SIGNING_CERT:
			params.signing_cert_file = optarg;
			break;
		case OPT_SIGNATURE:
			params.signature_file = optarg;
			break;
		case OPT_ELIDE:
			string_list_append(&elide_opts, optarg);
			break;
		case OPT_PATCH:
			string_list_append(&patch_opts, optarg);
			break;
		default:
			goto out_usage;
		}
	}

	argv += optind;
	argc -= optind;

	if (argc != 1 && argc != 2)
		goto out_usage;

	ASSERT(params.hash_alg->digest_size <= FS_VERITY_MAX_DIGEST_SIZE);

	if (params.blocksize == 0) {
		params.blocksize = sysconf(_SC_PAGESIZE);
		if (params.blocksize <= 0 || !is_power_of_2(params.blocksize)) {
			fprintf(stderr,
				"Warning: invalid _SC_PAGESIZE (%d).  Assuming 4K blocks.\n",
				params.blocksize);
			params.blocksize = 4096;
		}
	}
	params.blockbits = ilog2(params.blocksize);

	params.hashes_per_block = params.blocksize /
				  params.hash_alg->digest_size;
	if (params.hashes_per_block < 2) {
		error_msg("block size of %d bytes is too small for %s hash",
			  params.blocksize, params.hash_alg->name);
		goto out_err;
	}

	if (params.signing_cert_file && !params.signing_key_file) {
		error_msg("--signing-cert was given, but --signing-key was not.\n"
"       You must provide the certificate's private key file using --signing-key.");
		goto out_err;
	}

	if ((params.signing_key_file || params.signature_file) &&
	    !params.hash_alg->cryptographic) {
		error_msg("Signing a file using '%s' checksums does not make sense\n"
			  "       because '%s' is not a cryptographically secure hash algorithm.",
			  params.hash_alg->name, params.hash_alg->name);
		goto out_err;
	}

	if (!load_elisions_and_patches(&elide_opts, &patch_opts, &params))
		goto out_err;

	status = fsveritysetup(argv[0], argv[argc - 1], &params);
out:
	free(params.salt);
	free_elisions_and_patches(&params);
	string_list_destroy(&elide_opts);
	string_list_destroy(&patch_opts);
	return status;

out_err:
	status = 1;
	goto out;

out_usage:
	usage(cmd, stderr);
	status = 2;
	goto out;
}
