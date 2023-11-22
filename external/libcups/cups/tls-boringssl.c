/*
 * TLS support code for CUPS using Google BoringSSL.
 *
 * Copyright 2007-2016 by Apple Inc.
 * Copyright 1997-2007 by Easy Software Products, all rights reserved.
 *
 * These coded instructions, statements, and computer programs are the
 * property of Apple Inc. and are protected by Federal copyright
 * law.  Distribution and use rights are outlined in the file "LICENSE.txt"
 * which should have been included with this file.  If this file is
 * file is missing or damaged, see the license at "http://www.cups.org/".
 *
 * This file is subject to the Apple OS-Developed Software exception.
 */

/**** This file is included from tls.c ****/

/*
 * Local globals...
 */

#include "cups-private.h"
#include "http.h"
#include "thread-private.h"
#include <openssl/err.h>
#include <openssl/rand.h>
#include <openssl/ssl.h>

#include <sys/stat.h>

static char		*tls_keypath = NULL;
					/* Server cert keychain path */
static int		tls_options = -1;/* Options for TLS connections */


/*
 * Local functions...
 */

static const char	*http_bssl_default_path(char *buffer, size_t bufsize);
static const char	*http_bssl_make_path(char *buffer, size_t bufsize, const char *dirname, const char *filename, const char *ext);
static BIO_METHOD *     _httpBIOMethods(void);
static int              http_bio_write(BIO *h, const char *buf, int num);
static int              http_bio_read(BIO *h, char *buf, int size);
static int              http_bio_puts(BIO *h, const char *str);
static long             http_bio_ctrl(BIO *h, int cmd, long arg1, void *arg2);
static int              http_bio_new(BIO *h);
static int              http_bio_free(BIO *data);

static BIO_METHOD	http_bio_methods =
			{
			  BIO_TYPE_SSL,
			  "http",
			  http_bio_write,
			  http_bio_read,
			  http_bio_puts,
			  NULL, /* http_bio_gets, */
			  http_bio_ctrl,
			  http_bio_new,
			  http_bio_free,
			  NULL,
			};

/*
 * 'cupsMakeServerCredentials()' - Make a self-signed certificate and private key pair.
 *
 * @since CUPS 2.0/OS 10.10@
 */

int					/* O - 1 on success, 0 on failure */
cupsMakeServerCredentials(
    const char *path,			/* I - Path to keychain/directory */
    const char *common_name,		/* I - Common name */
    int        num_alt_names,		/* I - Number of subject alternate names */
    const char **alt_names,		/* I - Subject Alternate Names */
    time_t     expiration_date)		/* I - Expiration date */
{
  int		pid,			/* Process ID of command */
		status;			/* Status of command */
  char		command[1024],		/* Command */
		*argv[12],		/* Command-line arguments */
		*envp[1000],	        /* Environment variables */
		infofile[1024],		/* Type-in information for cert */
		seedfile[1024];		/* Random number seed file */
  int		envc,			/* Number of environment variables */
		bytes;			/* Bytes written */
  cups_file_t	*fp;			/* Seed/info file */
  int		infofd;			/* Info file descriptor */
  char          temp[1024],	        /* Temporary directory name */
                crtfile[1024],	        /* Certificate filename */
                keyfile[1024];	        /* Private key filename */

  DEBUG_printf(("cupsMakeServerCredentials(path=\"%s\", common_name=\"%s\", num_alt_names=%d, alt_names=%p, expiration_date=%d)", path, common_name, num_alt_names, alt_names, (int)expiration_date));

  return 0;
}


/*
 * '_httpCreateCredentials()' - Create credentials in the internal format.
 */

http_tls_credentials_t			/* O - Internal credentials */
_httpCreateCredentials(
    cups_array_t *credentials)		/* I - Array of credentials */
{
  (void)credentials;

  return (NULL);
}


/*
 * '_httpFreeCredentials()' - Free internal credentials.
 */

void
_httpFreeCredentials(
    http_tls_credentials_t credentials)	/* I - Internal credentials */
{
  (void)credentials;
}


/*
 * 'http_gnutls_default_path()' - Get the default credential store path.
 */

static const char *			/* O - Path or NULL on error */
http_bssl_default_path(char   *buffer,/* I - Path buffer */
                         size_t bufsize)/* I - Size of path buffer */
{
  const char *home = getenv("HOME");	/* HOME environment variable */


  if (getuid() && home)
  {
    snprintf(buffer, bufsize, "%s/.cups", home);
    if (access(buffer, 0))
    {
      DEBUG_printf(("1http_gnutls_default_path: Making directory \"%s\".", buffer));
      if (mkdir(buffer, 0700))
      {
        DEBUG_printf(("1http_gnutls_default_path: Failed to make directory: %s", strerror(errno)));
        return (NULL);
      }
    }

    snprintf(buffer, bufsize, "%s/.cups/ssl", home);
    if (access(buffer, 0))
    {
      DEBUG_printf(("1http_gnutls_default_path: Making directory \"%s\".", buffer));
      if (mkdir(buffer, 0700))
      {
        DEBUG_printf(("1http_gnutls_default_path: Failed to make directory: %s", strerror(errno)));
        return (NULL);
      }
    }
  }
  else
    strlcpy(buffer, CUPS_SERVERROOT "/ssl", bufsize);

  DEBUG_printf(("1http_gnutls_default_path: Using default path \"%s\".", buffer));

  return (buffer);
}


/*
 * 'http_gnutls_make_path()' - Format a filename for a certificate or key file.
 */

static const char *			/* O - Filename */
http_bssl_make_path(
    char       *buffer,			/* I - Filename buffer */
    size_t     bufsize,			/* I - Size of buffer */
    const char *dirname,		/* I - Directory */
    const char *filename,		/* I - Filename (usually hostname) */
    const char *ext)			/* I - Extension */
{
  char	*bufptr,			/* Pointer into buffer */
	*bufend = buffer + bufsize - 1;	/* End of buffer */


  snprintf(buffer, bufsize, "%s/", dirname);
  bufptr = buffer + strlen(buffer);

  while (*filename && bufptr < bufend)
  {
    if (_cups_isalnum(*filename) || *filename == '-' || *filename == '.')
      *bufptr++ = *filename;
    else
      *bufptr++ = '_';

    filename ++;
  }

  if (bufptr < bufend)
    *bufptr++ = '.';

  strlcpy(bufptr, ext, (size_t)(bufend - bufptr + 1));

  return (buffer);
}


/*
 * '_httpBIOMethods()' - Get the OpenSSL BIO methods for HTTP connections.
 */

static BIO_METHOD *                            /* O - BIO methods for OpenSSL */
_httpBIOMethods(void)
{
  return (&http_bio_methods);
}


/*
 * 'http_bio_ctrl()' - Control the HTTP connection.
 */

static long				/* O - Result/data */
http_bio_ctrl(BIO  *h,			/* I - BIO data */
              int  cmd,			/* I - Control command */
	      long arg1,		/* I - First argument */
	      void *arg2)		/* I - Second argument */
{
  switch (cmd)
  {
    default :
        return (0);

    case BIO_CTRL_RESET :
        h->ptr = NULL;
	return (0);

    case BIO_C_SET_FILE_PTR :
        h->ptr  = arg2;
	h->init = 1;
	return (1);

    case BIO_C_GET_FILE_PTR :
        if (arg2)
	{
	  *((void **)arg2) = h->ptr;
	  return (1);
	}
	else
	  return (0);

    case BIO_CTRL_DUP :
    case BIO_CTRL_FLUSH :
        return (1);
  }
}


/*
 * 'http_bio_free()' - Free OpenSSL data.
 */

static int				/* O - 1 on success, 0 on failure */
http_bio_free(BIO *h)			/* I - BIO data */
{
  if (!h)
    return (0);

  if (h->shutdown)
  {
    h->init  = 0;
    h->flags = 0;
  }

  return (1);
}


/*
 * 'http_bio_new()' - Initialize an OpenSSL BIO structure.
 */

static int				/* O - 1 on success, 0 on failure */
http_bio_new(BIO *h)			/* I - BIO data */
{
  if (!h)
    return (0);

  h->init  = 0;
  h->num   = 0;
  h->ptr   = NULL;
  h->flags = 0;

  return (1);
}


/*
 * 'http_bio_puts()' - Send a string for OpenSSL.
 */

static int				/* O - Bytes written */
http_bio_puts(BIO        *h,		/* I - BIO data */
              const char *str)		/* I - String to write */
{
  return (send(((http_t *)h->ptr)->fd, str, strlen(str), 0));
}


/*
 * 'http_bio_read()' - Read data for OpenSSL.
 */

static int				/* O - Bytes read */
http_bio_read(BIO  *h,			/* I - BIO data */
              char *buf,		/* I - Buffer */
	      int  size)		/* I - Number of bytes to read */
{
  http_t	*http;			/* HTTP connection */


  http = (http_t *)h->ptr;

  if (!http->blocking)
  {
   /*
    * Make sure we have data before we read...
    */

    while (!_httpWait(http, http->wait_value, 0))
    {
      if (http->timeout_cb && (*http->timeout_cb)(http, http->timeout_data))
	continue;

      http->error = ETIMEDOUT;

      return (-1);
    }
  }

  return (recv(http->fd, buf, size, 0));
}


/*
 * 'http_bio_write()' - Write data for OpenSSL.
 */

static int				/* O - Bytes written */
http_bio_write(BIO        *h,		/* I - BIO data */
               const char *buf,		/* I - Buffer to write */
	       int        num)		/* I - Number of bytes to write */
{
  return (send(((http_t *)h->ptr)->fd, buf, num, 0));
}


/*
 * '_httpTLSInitialize()' - Initialize the TLS stack.
 */

void
_httpTLSInitialize(void)
{
  int           i;                      /* Looping var */
  unsigned char data[1024];             /* Seed data */

 /*
  * Initialize OpenSSL...
  */

  SSL_load_error_strings();
  SSL_library_init();

 /*
  * Using the current time is a dubious random seed, but on some systems
  * it is the best we can do (on others, this seed isn't even used...)
  */

  CUPS_SRAND(time(NULL));

  for (i = 0; i < sizeof(data); i ++)
    data[i] = CUPS_RAND();

  RAND_seed(data, sizeof(data));
}


/*
 * '_httpTLSPending()' - Return the number of pending TLS-encrypted bytes.
 */

size_t					/* O - Bytes available */
_httpTLSPending(http_t *http)		/* I - HTTP connection */
{
  return (SSL_pending(http->tls));
}


/*
 * '_httpTLSRead()' - Read from a SSL/TLS connection.
 */

int					/* O - Bytes read */
_httpTLSRead(http_t *http,		/* I - Connection to server */
	     char   *buf,		/* I - Buffer to store data */
	     int    len)		/* I - Length of buffer */
{
  return (SSL_read((SSL *)(http->tls), buf, len));
}


/*
 * '_httpTLSSetOptions()' - Set TLS protocol and cipher suite options.
 */

void
_httpTLSSetOptions(int options)		/* I - Options */
{
  tls_options = options;
}


/*
 * '_httpTLSStart()' - Set up SSL/TLS support on a connection.
 */

int					/* O - 0 on success, -1 on failure */
_httpTLSStart(http_t *http)		/* I - Connection to server */
{
  char			hostname[256],	/* Hostname */
			*hostptr;	/* Pointer into hostname */

  SSL_CTX		*context;	/* Context for encryption */
  BIO			*bio;		/* BIO data */
  const char		*message = NULL;/* Error message */

  DEBUG_printf(("3_httpTLSStart(http=%p)", (void *)http));

  if (tls_options < 0)
  {
    DEBUG_puts("4_httpTLSStart: Setting defaults.");
    _cupsSetDefaults();
    DEBUG_printf(("4_httpTLSStart: tls_options=%x", tls_options));
  }

  if (http->mode == _HTTP_MODE_SERVER && !tls_keypath)
  {
    DEBUG_puts("4_httpTLSStart: cupsSetServerCredentials not called.");
    http->error  = errno = EINVAL;
    http->status = HTTP_STATUS_ERROR;
    _cupsSetError(IPP_STATUS_ERROR_INTERNAL, _("Server credentials not set."), 1);

    return (-1);
  }

  if (tls_options & _HTTP_TLS_DENY_TLS10)
    context = SSL_CTX_new(http->mode == _HTTP_MODE_CLIENT ? TLSv1_1_client_method() : TLSv1_1_server_method());
  else if (tls_options & _HTTP_TLS_ALLOW_SSL3)
    context = SSL_CTX_new(http->mode == _HTTP_MODE_CLIENT ? SSLv3_client_method() : SSLv3_server_method());
  else
    context = SSL_CTX_new(http->mode == _HTTP_MODE_CLIENT ? TLSv1_client_method() : TLSv1_server_method());

  bio = BIO_new(_httpBIOMethods());
  BIO_ctrl(bio, BIO_C_SET_FILE_PTR, 0, (char *)http);

  http->tls = SSL_new(context);
  SSL_set_bio(http->tls, bio, bio);

  if (http->mode == _HTTP_MODE_CLIENT)
  {
   /*
    * Client: get the hostname to use for TLS...
    */

    if (httpAddrLocalhost(http->hostaddr))
    {
      strlcpy(hostname, "localhost", sizeof(hostname));
    }
    else
    {
     /*
      * Otherwise make sure the hostname we have does not end in a trailing dot.
      */

      strlcpy(hostname, http->hostname, sizeof(hostname));
      if ((hostptr = hostname + strlen(hostname) - 1) >= hostname &&
	  *hostptr == '.')
	*hostptr = '\0';
    }
#   ifdef HAVE_SSL_SET_TLSEXT_HOST_NAME
    SSL_set_tlsext_host_name(http->tls, hostname);
#   endif /* HAVE_SSL_SET_TLSEXT_HOST_NAME */

  }
  else
  {
/* @@@ TODO @@@ */
//    SSL_CTX_use_PrivateKey_file(context, ServerKey, SSL_FILETYPE_PEM);
//    SSL_CTX_use_certificate_chain_file(context, ServerCertificate);
  }


  if (http->mode == _HTTP_MODE_CLIENT ? SSL_connect(http->tls) != 1 :SSL_connect(http->tls) != 1)
  {
    unsigned long	error;	/* Error code */

    while ((error = ERR_get_error()) != 0)
    {
      message = ERR_error_string(error, NULL);
      DEBUG_printf(("8http_setup_ssl: %s", message));
    }

    SSL_CTX_free(context);
    SSL_free(http->tls);
    http->tls = NULL;

    http->error  = errno;
    http->status = HTTP_STATUS_ERROR;

    if (!message)
      message = _("Unable to establish a secure connection to host.");

    _cupsSetError(IPP_STATUS_ERROR_CUPS_PKI, message, 1);

    return (-1);
  }

  return (0);
}


/*
 * '_httpTLSStop()' - Shut down SSL/TLS on a connection.
 */

void
_httpTLSStop(http_t *http)		/* I - Connection to server */
{
  SSL_CTX	*context;		/* Context for encryption */
  unsigned long	error;			/* Error code */

  context = SSL_get_SSL_CTX(http->tls);

  switch (SSL_shutdown(http->tls))
  {
    case 1 :
	break;

    case -1 :
        _cupsSetError(IPP_STATUS_ERROR_INTERNAL,
			"Fatal error during SSL shutdown!", 0);
    default :
	while ((error = ERR_get_error()) != 0)
    	  _cupsSetError(IPP_STATUS_ERROR_INTERNAL,
			ERR_error_string(error, NULL), 0);
	break;
  }

  SSL_CTX_free(context);
  SSL_free(http->tls);
  http->tls = NULL;
}

/*
 * '_httpTLSWrite()' - Write to a SSL/TLS connection.
 */

int					/* O - Bytes written */
_httpTLSWrite(http_t     *http,		/* I - Connection to server */
	      const char *buf,		/* I - Buffer holding data */
	      int        len)		/* I - Length of buffer */
{
  ssize_t	result;			/* Return value */


  DEBUG_printf(("2http_write_ssl(http=%p, buf=%p, len=%d)", http, buf, len));

  result = SSL_write((SSL *)(http->tls), buf, len);

  DEBUG_printf(("3http_write_ssl: Returning %d.", (int)result));

  return ((int)result);
}
