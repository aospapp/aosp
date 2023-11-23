// SPDX-License-Identifier: GPL-2.0+
/*
 * Signature support for 'fsverity setup'
 *
 * Copyright (C) 2018 Google LLC
 *
 * Written by Eric Biggers.
 */

#include <fcntl.h>
#include <limits.h>
#include <openssl/bio.h>
#include <openssl/err.h>
#include <openssl/pem.h>
#include <openssl/pkcs7.h>
#include <stdlib.h>
#include <string.h>

#include "fsverity_uapi.h"
#include "fsveritysetup.h"
#include "hash_algs.h"

static void __printf(1, 2) __cold
error_msg_openssl(const char *format, ...)
{
	va_list va;

	va_start(va, format);
	do_error_msg(format, va, 0);
	va_end(va);

	if (ERR_peek_error() == 0)
		return;

	fprintf(stderr, "OpenSSL library errors:\n");
	ERR_print_errors_fp(stderr);
}

/* Read a PEM PKCS#8 formatted private key */
static EVP_PKEY *read_private_key(const char *keyfile)
{
	BIO *bio;
	EVP_PKEY *pkey;

	bio = BIO_new_file(keyfile, "r");
	if (!bio) {
		error_msg_openssl("can't open '%s' for reading", keyfile);
		return NULL;
	}

	pkey = PEM_read_bio_PrivateKey(bio, NULL, NULL, NULL);
	if (!pkey) {
		error_msg_openssl("Failed to parse private key file '%s'.\n"
				  "       Note: it must be in PEM PKCS#8 format.",
				  keyfile);
	}
	BIO_free(bio);
	return pkey;
}

/* Read a PEM X.509 formatted certificate */
static X509 *read_certificate(const char *certfile)
{
	BIO *bio;
	X509 *cert;

	bio = BIO_new_file(certfile, "r");
	if (!bio) {
		error_msg_openssl("can't open '%s' for reading", certfile);
		return NULL;
	}
	cert = PEM_read_bio_X509(bio, NULL, NULL, NULL);
	if (!cert) {
		error_msg_openssl("Failed to parse X.509 certificate file '%s'.\n"
				  "       Note: it must be in PEM format.",
				  certfile);
	}
	BIO_free(bio);
	return cert;
}

/*
 * Check that the given data is a valid 'struct fsverity_digest_disk' that
 * matches the given @expected_digest and @hash_alg.
 *
 * Return: NULL if the digests match, else a string describing the difference.
 */
static const char *
compare_fsverity_digest(const void *data, size_t size,
			const u8 *expected_digest,
			const struct fsverity_hash_alg *hash_alg)
{
	const struct fsverity_digest_disk *d = data;

	if (size != sizeof(*d) + hash_alg->digest_size)
		return "unexpected length";

	if (le16_to_cpu(d->digest_algorithm) != hash_alg - fsverity_hash_algs)
		return "unexpected hash algorithm";

	if (le16_to_cpu(d->digest_size) != hash_alg->digest_size)
		return "wrong digest size for hash algorithm";

	if (memcmp(expected_digest, d->digest, hash_alg->digest_size))
		return "wrong digest";

	return NULL;
}

#ifdef OPENSSL_IS_BORINGSSL

static bool sign_pkcs7(const void *data_to_sign, size_t data_size,
		       EVP_PKEY *pkey, X509 *cert, const EVP_MD *md,
		       void **sig_ret, int *sig_size_ret)
{
	CBB out, outer_seq, wrapped_seq, seq, digest_algos_set, digest_algo,
		null, content_info, issuer_and_serial, signed_data,
		wrapped_signed_data, signer_infos, signer_info, sign_algo,
		signature;
	EVP_MD_CTX md_ctx;
	u8 *name_der = NULL, *sig = NULL, *pkcs7_data = NULL;
	size_t pkcs7_data_len, sig_len;
	int name_der_len, sig_nid;
	bool ok = false;

	EVP_MD_CTX_init(&md_ctx);
	BIGNUM *serial = ASN1_INTEGER_to_BN(X509_get_serialNumber(cert), NULL);

	if (!CBB_init(&out, 1024)) {
		error_msg("out of memory");
		goto out;
	}

	name_der_len = i2d_X509_NAME(X509_get_subject_name(cert), &name_der);
	if (name_der_len < 0) {
		error_msg_openssl("i2d_X509_NAME failed");
		goto out;
	}

	if (!EVP_DigestSignInit(&md_ctx, NULL, md, NULL, pkey)) {
		error_msg_openssl("EVP_DigestSignInit failed");
		goto out;
	}

	sig_len = EVP_PKEY_size(pkey);
	sig = xmalloc(sig_len);
	if (!EVP_DigestSign(&md_ctx, sig, &sig_len, data_to_sign, data_size)) {
		error_msg_openssl("EVP_DigestSign failed");
		goto out;
	}

	sig_nid = EVP_PKEY_id(pkey);
	/* To mirror OpenSSL behaviour, always use |NID_rsaEncryption| with RSA
	 * rather than the combined hash+pkey NID. */
	if (sig_nid != NID_rsaEncryption) {
		OBJ_find_sigid_by_algs(&sig_nid, EVP_MD_type(md),
				       EVP_PKEY_id(pkey));
	}

	// See https://tools.ietf.org/html/rfc2315#section-7
	if (!CBB_add_asn1(&out, &outer_seq, CBS_ASN1_SEQUENCE) ||
	    !OBJ_nid2cbb(&outer_seq, NID_pkcs7_signed) ||
	    !CBB_add_asn1(&outer_seq, &wrapped_seq, CBS_ASN1_CONTEXT_SPECIFIC |
			  CBS_ASN1_CONSTRUCTED | 0) ||
	    // See https://tools.ietf.org/html/rfc2315#section-9.1
	    !CBB_add_asn1(&wrapped_seq, &seq, CBS_ASN1_SEQUENCE) ||
	    !CBB_add_asn1_uint64(&seq, 1 /* version */) ||
	    !CBB_add_asn1(&seq, &digest_algos_set, CBS_ASN1_SET) ||
	    !CBB_add_asn1(&digest_algos_set, &digest_algo, CBS_ASN1_SEQUENCE) ||
	    !OBJ_nid2cbb(&digest_algo, EVP_MD_type(md)) ||
	    !CBB_add_asn1(&digest_algo, &null, CBS_ASN1_NULL) ||
	    !CBB_add_asn1(&seq, &content_info, CBS_ASN1_SEQUENCE) ||
	    !OBJ_nid2cbb(&content_info, NID_pkcs7_data) ||
	    !CBB_add_asn1(
		&content_info, &signed_data,
		CBS_ASN1_CONTEXT_SPECIFIC | CBS_ASN1_CONSTRUCTED | 0) ||
	    !CBB_add_asn1(&signed_data, &wrapped_signed_data,
			  CBS_ASN1_OCTETSTRING) ||
	    !CBB_add_bytes(&wrapped_signed_data, (const u8 *)data_to_sign,
			   data_size) ||
	    !CBB_add_asn1(&seq, &signer_infos, CBS_ASN1_SET) ||
	    !CBB_add_asn1(&signer_infos, &signer_info, CBS_ASN1_SEQUENCE) ||
	    !CBB_add_asn1_uint64(&signer_info, 1 /* version */) ||
	    !CBB_add_asn1(&signer_info, &issuer_and_serial,
			  CBS_ASN1_SEQUENCE) ||
	    !CBB_add_bytes(&issuer_and_serial, name_der, name_der_len) ||
	    !BN_marshal_asn1(&issuer_and_serial, serial) ||
	    !CBB_add_asn1(&signer_info, &digest_algo, CBS_ASN1_SEQUENCE) ||
	    !OBJ_nid2cbb(&digest_algo, EVP_MD_type(md)) ||
	    !CBB_add_asn1(&digest_algo, &null, CBS_ASN1_NULL) ||
	    !CBB_add_asn1(&signer_info, &sign_algo, CBS_ASN1_SEQUENCE) ||
	    !OBJ_nid2cbb(&sign_algo, sig_nid) ||
	    !CBB_add_asn1(&sign_algo, &null, CBS_ASN1_NULL) ||
	    !CBB_add_asn1(&signer_info, &signature, CBS_ASN1_OCTETSTRING) ||
	    !CBB_add_bytes(&signature, sig, sig_len) ||
	    !CBB_finish(&out, &pkcs7_data, &pkcs7_data_len)) {
		error_msg_openssl("failed to construct PKCS#7 data");
		goto out;
	}

	*sig_ret = xmemdup(pkcs7_data, pkcs7_data_len);
	*sig_size_ret = pkcs7_data_len;
	ok = true;
out:
	BN_free(serial);
	EVP_MD_CTX_cleanup(&md_ctx);
	CBB_cleanup(&out);
	free(sig);
	OPENSSL_free(name_der);
	OPENSSL_free(pkcs7_data);
	return ok;
}

static const char *
compare_fsverity_digest_pkcs7(const void *sig, size_t sig_len,
			      const u8 *expected_measurement,
			      const struct fsverity_hash_alg *hash_alg)
{
	CBS in, content_info, content_type, wrapped_signed_data, signed_data,
		content, wrapped_data, data;
	u64 version;

	CBS_init(&in, sig, sig_len);
	if (!CBS_get_asn1(&in, &content_info, CBS_ASN1_SEQUENCE) ||
	    !CBS_get_asn1(&content_info, &content_type, CBS_ASN1_OBJECT) ||
	    (OBJ_cbs2nid(&content_type) != NID_pkcs7_signed) ||
	    !CBS_get_asn1(
		&content_info, &wrapped_signed_data,
		CBS_ASN1_CONTEXT_SPECIFIC | CBS_ASN1_CONSTRUCTED | 0) ||
	    !CBS_get_asn1(&wrapped_signed_data, &signed_data,
			  CBS_ASN1_SEQUENCE) ||
	    !CBS_get_asn1_uint64(&signed_data, &version) ||
	    (version < 1) ||
	    !CBS_get_asn1(&signed_data, NULL /* digests */, CBS_ASN1_SET) ||
	    !CBS_get_asn1(&signed_data, &content, CBS_ASN1_SEQUENCE) ||
	    !CBS_get_asn1(&content, &content_type, CBS_ASN1_OBJECT) ||
	    (OBJ_cbs2nid(&content_type) != NID_pkcs7_data) ||
	    !CBS_get_asn1(&content, &wrapped_data, CBS_ASN1_CONTEXT_SPECIFIC |
						   CBS_ASN1_CONSTRUCTED | 0) ||
	    !CBS_get_asn1(&wrapped_data, &data, CBS_ASN1_OCTETSTRING)) {
		return "invalid PKCS#7 data";
	}

	return compare_fsverity_digest(CBS_data(&data), CBS_len(&data),
				       expected_measurement, hash_alg);
}

#else /* OPENSSL_IS_BORINGSSL */

static BIO *new_mem_buf(const void *buf, size_t size)
{
	BIO *bio;

	ASSERT(size <= INT_MAX);
	/*
	 * Prior to OpenSSL 1.1.0, BIO_new_mem_buf() took a non-const pointer,
	 * despite still marking the resulting bio as read-only.  So cast away
	 * the const to avoid a compiler warning with older OpenSSL versions.
	 */
	bio = BIO_new_mem_buf((void *)buf, size);
	if (!bio)
		error_msg_openssl("out of memory");
	return bio;
}

static bool sign_pkcs7(const void *data_to_sign, size_t data_size,
		       EVP_PKEY *pkey, X509 *cert, const EVP_MD *md,
		       void **sig_ret, int *sig_size_ret)
{
	/*
	 * PKCS#7 signing flags:
	 *
	 * - PKCS7_BINARY	signing binary data, so skip MIME translation
	 *
	 * - PKCS7_NOATTR	omit extra authenticated attributes, such as
	 *			SMIMECapabilities
	 *
	 * - PKCS7_NOCERTS	omit the signer's certificate
	 *
	 * - PKCS7_PARTIAL	PKCS7_sign() creates a handle only, then
	 *			PKCS7_sign_add_signer() can add a signer later.
	 *			This is necessary to change the message digest
	 *			algorithm from the default of SHA-1.  Requires
	 *			OpenSSL 1.0.0 or later.
	 */
	int pkcs7_flags = PKCS7_BINARY | PKCS7_NOATTR | PKCS7_NOCERTS |
			  PKCS7_PARTIAL;
	void *sig;
	int sig_size;
	BIO *bio = NULL;
	PKCS7 *p7 = NULL;
	bool ok = false;

	bio = new_mem_buf(data_to_sign, data_size);
	if (!bio)
		goto out;

	p7 = PKCS7_sign(NULL, NULL, NULL, bio, pkcs7_flags);
	if (!p7) {
		error_msg_openssl("failed to initialize PKCS#7 signature object");
		goto out;
	}

	if (!PKCS7_sign_add_signer(p7, cert, pkey, md, pkcs7_flags)) {
		error_msg_openssl("failed to add signer to PKCS#7 signature object");
		goto out;
	}

	if (PKCS7_final(p7, bio, pkcs7_flags) != 1) {
		error_msg_openssl("failed to finalize PKCS#7 signature");
		goto out;
	}

	BIO_free(bio);
	bio = BIO_new(BIO_s_mem());
	if (!bio) {
		error_msg_openssl("out of memory");
		goto out;
	}

	if (i2d_PKCS7_bio(bio, p7) != 1) {
		error_msg_openssl("failed to DER-encode PKCS#7 signature object");
		goto out;
	}

	sig_size = BIO_get_mem_data(bio, &sig);
	*sig_ret = xmemdup(sig, sig_size);
	*sig_size_ret = sig_size;
	ok = true;
out:
	PKCS7_free(p7);
	BIO_free(bio);
	return ok;
}

static const char *
compare_fsverity_digest_pkcs7(const void *sig, size_t sig_len,
			      const u8 *expected_measurement,
			      const struct fsverity_hash_alg *hash_alg)
{
	BIO *bio = NULL;
	PKCS7 *p7 = NULL;
	const char *reason = NULL;

	bio = new_mem_buf(sig, sig_len);
	if (!bio)
		return "out of memory";

	p7 = d2i_PKCS7_bio(bio, NULL);
	if (!p7) {
		reason = "failed to decode PKCS#7 signature";
		goto out;
	}

	if (OBJ_obj2nid(p7->type) != NID_pkcs7_signed ||
	    OBJ_obj2nid(p7->d.sign->contents->type) != NID_pkcs7_data) {
		reason = "unexpected PKCS#7 content type";
	} else {
		const ASN1_OCTET_STRING *o = p7->d.sign->contents->d.data;

		reason = compare_fsverity_digest(o->data, o->length,
						 expected_measurement,
						 hash_alg);
	}
out:
	BIO_free(bio);
	PKCS7_free(p7);
	return reason;
}

#endif /* !OPENSSL_IS_BORINGSSL */

/*
 * Sign the specified @data_to_sign of length @data_size bytes using the private
 * key in @keyfile, the certificate in @certfile, and the hash algorithm
 * @hash_alg.  Returns the DER-formatted PKCS#7 signature, with the signed data
 * included (not detached), in @sig_ret and @sig_size_ret.
 */
static bool sign_data(const void *data_to_sign, size_t data_size,
		      const char *keyfile, const char *certfile,
		      const struct fsverity_hash_alg *hash_alg,
		      void **sig_ret, int *sig_size_ret)
{
	EVP_PKEY *pkey = NULL;
	X509 *cert = NULL;
	const EVP_MD *md;
	bool ok = false;

	pkey = read_private_key(keyfile);
	if (!pkey)
		goto out;

	cert = read_certificate(certfile);
	if (!cert)
		goto out;

	OpenSSL_add_all_digests();
	ASSERT(hash_alg->cryptographic);
	md = EVP_get_digestbyname(hash_alg->name);
	if (!md) {
		fprintf(stderr,
			"Warning: '%s' algorithm not found in OpenSSL library.\n"
			"         Falling back to SHA-256 signature.\n",
			hash_alg->name);
		md = EVP_sha256();
	}

	ok = sign_pkcs7(data_to_sign, data_size, pkey, cert, md,
			sig_ret, sig_size_ret);
out:
	EVP_PKEY_free(pkey);
	X509_free(cert);
	return ok;
}

/*
 * Read a file measurement signature in PKCS#7 DER format from @signature_file,
 * validate that the signed data matches the expected measurement, then return
 * the PKCS#7 DER message in @sig_ret and @sig_size_ret.
 */
static bool read_signature(const char *signature_file,
			   const u8 *expected_measurement,
			   const struct fsverity_hash_alg *hash_alg,
			   void **sig_ret, int *sig_size_ret)
{
	struct filedes file = { .fd = -1 };
	u64 filesize;
	void *sig = NULL;
	bool ok = false;
	const char *reason;

	if (!open_file(&file, signature_file, O_RDONLY, 0))
		goto out;
	if (!get_file_size(&file, &filesize))
		goto out;
	if (filesize <= 0) {
		error_msg("signature file '%s' is empty", signature_file);
		goto out;
	}
	if (filesize > 1000000) {
		error_msg("signature file '%s' is too large", signature_file);
		goto out;
	}
	sig = xmalloc(filesize);
	if (!full_read(&file, sig, filesize))
		goto out;

	reason = compare_fsverity_digest_pkcs7(sig, filesize,
					       expected_measurement, hash_alg);
	if (reason) {
		error_msg("signed file measurement from '%s' is invalid (%s)",
			  signature_file, reason);
		goto out;
	}

	printf("Using existing signed file measurement from '%s'\n",
	       signature_file);
	*sig_ret = sig;
	*sig_size_ret = filesize;
	sig = NULL;
	ok = true;
out:
	filedes_close(&file);
	free(sig);
	return ok;
}

static bool write_signature(const char *signature_file,
			    const void *sig, int sig_size)
{
	struct filedes file;
	bool ok;

	if (!open_file(&file, signature_file, O_WRONLY|O_CREAT|O_TRUNC, 0644))
		return false;
	ok = full_write(&file, sig, sig_size);
	ok &= filedes_close(&file);
	if (ok)
		printf("Wrote signed file measurement to '%s'\n",
		       signature_file);
	return ok;
}

/*
 * Append the signed file measurement to the output file as a PKCS7_SIGNATURE
 * extension item.
 *
 * Return: exit status code (0 on success, nonzero on failure)
 */
int append_signed_measurement(struct filedes *out,
			      const struct fsveritysetup_params *params,
			      const u8 *measurement)
{
	struct fsverity_digest_disk *data_to_sign = NULL;
	void *sig = NULL;
	void *extbuf = NULL;
	void *tmp;
	int sig_size;
	int status;

	if (params->signing_key_file) {
		size_t data_size = sizeof(*data_to_sign) +
				   params->hash_alg->digest_size;

		/* Sign the file measurement using the given key */

		data_to_sign = xzalloc(data_size);
		data_to_sign->digest_algorithm =
			cpu_to_le16(params->hash_alg - fsverity_hash_algs);
		data_to_sign->digest_size =
			cpu_to_le16(params->hash_alg->digest_size);
		memcpy(data_to_sign->digest, measurement,
		       params->hash_alg->digest_size);

		ASSERT(compare_fsverity_digest(data_to_sign, data_size,
					measurement, params->hash_alg) == NULL);

		if (!sign_data(data_to_sign, data_size,
			       params->signing_key_file,
			       params->signing_cert_file ?:
			       params->signing_key_file,
			       params->hash_alg,
			       &sig, &sig_size))
			goto out_err;

		if (params->signature_file &&
		    !write_signature(params->signature_file, sig, sig_size))
			goto out_err;
	} else {
		/* Using a signature that was already created */
		if (!read_signature(params->signature_file, measurement,
				    params->hash_alg, &sig, &sig_size))
			goto out_err;
	}

	tmp = extbuf = xzalloc(FSVERITY_EXTLEN(sig_size));
	fsverity_append_extension(&tmp, FS_VERITY_EXT_PKCS7_SIGNATURE,
				  sig, sig_size);
	ASSERT(tmp == extbuf + FSVERITY_EXTLEN(sig_size));
	if (!full_write(out, extbuf, FSVERITY_EXTLEN(sig_size)))
		goto out_err;
	status = 0;
out:
	free(data_to_sign);
	free(sig);
	free(extbuf);
	return status;

out_err:
	status = 1;
	goto out;
}
