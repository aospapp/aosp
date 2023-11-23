/* SPDX-License-Identifier: GPL-2.0+ */
#ifndef COMMANDS_H
#define COMMANDS_H

#include <stdio.h>

#include "util.h"

struct fsverity_command;

void usage(const struct fsverity_command *cmd, FILE *fp);

int fsverity_cmd_enable(const struct fsverity_command *cmd,
			int argc, char *argv[]);
int fsverity_cmd_setup(const struct fsverity_command *cmd,
		       int argc, char *argv[]);
int fsverity_cmd_measure(const struct fsverity_command *cmd,
			 int argc, char *argv[]);

#endif /* COMMANDS_H */
