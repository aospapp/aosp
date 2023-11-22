/* Copyright 2016 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include <sys/cdefs.h>
#include <sys/mman.h>
#ifdef __BIONIC__
#include <cutils/ashmem.h>
#else
#include <sys/shm.h>
#endif
#include <errno.h>
#include <syslog.h>
#include <stdio.h>
#include <string.h>

#include "cras_shm.h"

/* Set the correct SELinux label for SHM fds. */
static void cras_shm_restorecon(int fd)
{
#ifdef CRAS_SELINUX
	char fd_proc_path[64];

	if (snprintf(fd_proc_path, sizeof(fd_proc_path), "/proc/self/fd/%d", fd) < 0) {
		syslog(LOG_WARNING,
		       "Couldn't construct proc symlink path of fd: %d", fd);
		return;
	}

	/* Get the actual file-path for this fd. */
	char *path = realpath(fd_proc_path, NULL);
	if (path == NULL) {
		syslog(LOG_WARNING, "Couldn't run realpath() for %s: %s",
		       fd_proc_path, strerror(errno));
		return;
	}

	if (cras_selinux_restorecon(path) < 0) {
		syslog(LOG_WARNING, "Restorecon on %s failed: %s",
		       fd_proc_path, strerror(errno));
	}

	free(path);
#endif
}

#ifdef __BIONIC__

int cras_shm_open_rw (const char *name, size_t size)
{
	int fd;

	/* Eliminate the / in the shm_name. */
	if (name[0] == '/')
		name++;
	fd = ashmem_create_region(name, size);
	if (fd < 0) {
		fd = -errno;
		syslog(LOG_ERR, "failed to ashmem_create_region %s: %s\n",
		       name, strerror(-fd));
	}
	return fd;
}

int cras_shm_reopen_ro (const char *name, int fd)
{
	/* After mmaping the ashmem read/write, change it's protection
	   bits to disallow further write access. */
	if (ashmem_set_prot_region(fd, PROT_READ) != 0) {
		fd = -errno;
		syslog(LOG_ERR,
		       "failed to ashmem_set_prot_region %s: %s\n",
		       name, strerror(-fd));
	}
	return fd;
}

void cras_shm_close_unlink (const char *name, int fd)
{
	close(fd);
}

#else

int cras_shm_open_rw (const char *name, size_t size)
{
	int fd;
	int rc;

	fd = shm_open(name, O_CREAT | O_EXCL | O_RDWR, 0600);
	if (fd < 0) {
		fd = -errno;
		syslog(LOG_ERR, "failed to shm_open %s: %s\n",
		       name, strerror(-fd));
		return fd;
	}
	rc = ftruncate(fd, size);
	if (rc) {
		rc = -errno;
		syslog(LOG_ERR, "failed to set size of shm %s: %s\n",
		       name, strerror(-rc));
		return rc;
	}

	cras_shm_restorecon(fd);

	return fd;
}

int cras_shm_reopen_ro (const char *name, int fd)
{
	/* Open a read-only copy to dup and pass to clients. */
	fd = shm_open(name, O_RDONLY, 0);
	if (fd < 0) {
		fd = -errno;
		syslog(LOG_ERR,
		       "Failed to re-open shared memory '%s' read-only: %s",
		       name, strerror(-fd));
	}
	return fd;
}

void cras_shm_close_unlink (const char *name, int fd)
{
	shm_unlink(name);
	close(fd);
}

#endif

void *cras_shm_setup(const char *name,
		     size_t mmap_size,
		     int *rw_fd_out,
		     int *ro_fd_out)
{
	int rw_shm_fd = cras_shm_open_rw(name, mmap_size);
	if (rw_shm_fd < 0)
		return NULL;

	/* mmap shm. */
	void *exp_state = mmap(NULL, mmap_size,
			       PROT_READ | PROT_WRITE, MAP_SHARED,
			       rw_shm_fd, 0);
	if (exp_state == (void *)-1)
		return NULL;

	/* Open a read-only copy to dup and pass to clients. */
	int ro_shm_fd = cras_shm_reopen_ro(name, rw_shm_fd);
	if (ro_shm_fd < 0)
		return NULL;

	*rw_fd_out = rw_shm_fd;
	*ro_fd_out = ro_shm_fd;

	return exp_state;
}
