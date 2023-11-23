// SPDX-License-Identifier: GPL-2.0+
/*
 * The 'fsverity enable' command
 *
 * Copyright (C) 2018 Google LLC
 *
 * Written by Eric Biggers.
 */

#include <fcntl.h>
#include <sys/ioctl.h>

#include "commands.h"
#include "fsverity_uapi.h"

int fsverity_cmd_enable(const struct fsverity_command *cmd,
			int argc, char *argv[])
{
	struct filedes file;

	if (argc != 2) {
		usage(cmd, stderr);
		return 2;
	}

	if (!open_file(&file, argv[1], O_RDONLY, 0))
		return 1;
	if (ioctl(file.fd, FS_IOC_ENABLE_VERITY, NULL) != 0) {
		error_msg_errno("FS_IOC_ENABLE_VERITY failed on '%s'",
				file.name);
		filedes_close(&file);
		return 1;
	}
	if (!filedes_close(&file))
		return 1;
	return 0;
}
