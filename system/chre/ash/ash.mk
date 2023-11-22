#
# ASH Makefile
#

# Common Compiler Flags ########################################################

# Include paths.
COMMON_CFLAGS += -Iash/include

# Hexagon-specific Source Files ################################################

HEXAGON_SRCS += ash/platform/slpi/ash.cc

# x86-specific Source Files ####################################################

X86_SRCS += ash/platform/linux/ash.cc
