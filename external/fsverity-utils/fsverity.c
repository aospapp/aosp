// SPDX-License-Identifier: GPL-2.0+
/*
 * fs-verity userspace tool
 *
 * Copyright (C) 2018 Google LLC
 *
 * Written by Eric Biggers.
 */

#include <stdlib.h>
#include <string.h>

#include "commands.h"
#include "hash_algs.h"

static const struct fsverity_command {
	const char *name;
	int (*func)(const struct fsverity_command *cmd, int argc, char *argv[]);
	const char *short_desc;
	const char *usage_str;
} fsverity_commands[] = {
	{
		.name = "enable",
		.func = fsverity_cmd_enable,
		.short_desc =
"Enable fs-verity on a file with verity metadata",
		.usage_str =
"    fsverity enable FILE\n"
	}, {
		.name = "measure",
		.func = fsverity_cmd_measure,
		.short_desc =
"Display the measurement of the given fs-verity file(s)",
		.usage_str =
"    fsverity measure FILE...\n"
	}, {
		.name = "setup",
		.func = fsverity_cmd_setup,
		.short_desc = "Create the verity metadata for a file",
		.usage_str =
"    fsverity setup INFILE [OUTFILE]\n"
"                   [--hash=HASH_ALG] [--salt=SALT] [--signing-key=KEYFILE]\n"
"                   [--signing-cert=CERTFILE] [--signature=SIGFILE]\n"
"                   [--patch=OFFSET,PATCHFILE] [--elide=OFFSET,LENGTH]\n"
	}
};

static void usage_all(FILE *fp)
{
	int i;

	fputs("Usage:\n", fp);
	for (i = 0; i < ARRAY_SIZE(fsverity_commands); i++)
		fprintf(fp, "  %s:\n%s\n", fsverity_commands[i].short_desc,
			fsverity_commands[i].usage_str);
	fputs(
"  Standard options:\n"
"    fsverity --help\n"
"    fsverity --version\n"
"\n"
"Available hash algorithms: ", fp);
	show_all_hash_algs(fp);
	fputs("\nSee `man fsverity` for more details.\n", fp);
}

static void usage_cmd(const struct fsverity_command *cmd, FILE *fp)
{
	fprintf(fp, "Usage:\n%s", cmd->usage_str);
}

void usage(const struct fsverity_command *cmd, FILE *fp)
{
	if (cmd)
		usage_cmd(cmd, fp);
	else
		usage_all(fp);
}

#define PACKAGE_VERSION    "v0.0-alpha"
#define PACKAGE_BUGREPORT  "linux-fscrypt@vger.kernel.org"

static void show_version(void)
{
	static const char * const str =
"fsverity " PACKAGE_VERSION "\n"
"Copyright (C) 2018 Google LLC\n"
"License GPLv2+: GNU GPL version 2 or later <http://gnu.org/licenses/gpl.html>.\n"
"This is free software: you are free to change and redistribute it.\n"
"There is NO WARRANTY, to the extent permitted by law.\n"
"\n"
"Report bugs to " PACKAGE_BUGREPORT ".\n";
	fputs(str, stdout);
}

static void handle_common_options(int argc, char *argv[],
				  const struct fsverity_command *cmd)
{
	int i;

	for (i = 1; i < argc; i++) {
		const char *arg = argv[i];

		if (*arg++ != '-')
			continue;
		if (*arg++ != '-')
			continue;
		if (!strcmp(arg, "help")) {
			usage(cmd, stdout);
			exit(0);
		} else if (!strcmp(arg, "version")) {
			show_version();
			exit(0);
		} else if (!*arg) /* reached "--", no more options */
			return;
	}
}

static const struct fsverity_command *find_command(const char *name)
{
	int i;

	for (i = 0; i < ARRAY_SIZE(fsverity_commands); i++)
		if (!strcmp(name, fsverity_commands[i].name))
			return &fsverity_commands[i];
	return NULL;
}

int main(int argc, char *argv[])
{
	const struct fsverity_command *cmd;

	if (argc < 2) {
		error_msg("no command specified");
		usage_all(stderr);
		return 2;
	}

	cmd = find_command(argv[1]);

	handle_common_options(argc, argv, cmd);

	if (!cmd) {
		error_msg("unrecognized command: '%s'", argv[1]);
		usage_all(stderr);
		return 2;
	}
	return cmd->func(cmd, argc - 1, argv + 1);
}
