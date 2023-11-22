#
# Google Reference CHRE Implementation for Linux on x86
#

include $(CHRE_PREFIX)/build/clean_build_template_args.mk

TARGET_NAME = google_x86_linux
TARGET_CFLAGS = -DCHRE_MESSAGE_TO_HOST_MAX_SIZE=2048
TARGET_VARIANT_SRCS = $(GOOGLE_X86_LINUX_SRCS)
TARGET_SO_LATE_LIBS = $(GOOGLE_X86_LINUX_LATE_LIBS)

# Enable conversion warnings for the simulator. Since this is a platform 100%
# within our control we expect that there will be no conversion issues. It would
# be nice to enable this globally in the tools_config.mk but some vendor header
# files do not compile cleanly with it.
TARGET_CFLAGS += -Wconversion

# Add the target CFLAGS after the -Wconversion warning to allow targets to
# disable it.
TARGET_CFLAGS += $(GOOGLE_X86_LINUX_CFLAGS)

ifneq ($(filter $(TARGET_NAME)% all, $(MAKECMDGOALS)),)
ifneq ($(IS_NANOAPP_BUILD),)
include $(CHRE_PREFIX)/build/nanoapp/google_linux.mk
else
# Instruct the build to link a final executable.
TARGET_BUILD_BIN = true

# Link in libraries for the final executable and export symbols to dynamically
# loaded objects.
TARGET_BIN_LDFLAGS += -lrt -ldl -Wl,--export-dynamic
endif

include $(CHRE_PREFIX)/build/arch/x86.mk
include $(CHRE_PREFIX)/build/build_template.mk
endif
