#
# Copyright (C) 2018 Cadence Design Systems, Inc.
# 
# Permission is hereby granted, free of charge, to any person obtaining
# a copy of this software and associated documentation files (the
# "Software"), to use this Software with Cadence processor cores only and 
# not with any other processors and platforms, subject to
# the following conditions:
# 
# The above copyright notice and this permission notice shall be included
# in all copies or substantial portions of the Software.
# 
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
# MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
# IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
# CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
# TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
# SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
#

QUIET =
MAPFILE  = map_$(CODEC_NAME).txt
LDSCRIPT = ldscript_$(CODEC_NAME).txt
SYMFILE  = symbols_$(CODEC_NAME).txt
ELFTOBIN = $(ROOTDIR)/build_hikey/tools/elftobin.sh

ifeq ($(CPU), gcc)
    S = /
    AR = ar
    OBJCOPY = objcopy
    CC = gcc
    CXX = g++
    CFLAGS += -fno-exceptions -DCSTUB=1
    CFLAGS += -ffloat-store 
    CFLAGS += -DHIFI3_CSTUB
    RM = rm -f
    RM_R = rm -rf
    MKPATH = mkdir -p
    CP = cp -f
    INCLUDES += \
    -I$(ROOTDIR)/test/include
else
    AR = xt-ar $(XTCORE)
    OBJCOPY = xt-objcopy $(XTCORE)
    CC = xt-xcc $(XTCORE)
    CXX = xt-xc++ $(XTCORE)
    ISS = xt-run $(XTCORE)
    CONFIGDIR := $(shell $(ISS) --show-config=config)
    include $(CONFIGDIR)/misc/hostenv.mk
    #CFLAGS += -Wall 
    #CFLAGS += -Werror 
    #CFLAGS += -mno-mul16 -mno-mul32 -mno-div32 -fsigned-char -fno-exceptions -mlongcalls -INLINE:requested -mcoproc -fno-zero-initialized-in-bss
    CFLAGS += -mlongcalls -mtext-section-literals
    ASMFLAGS += -mlongcalls
endif

OBJDIR = objs$(S)$(CODEC_NAME)
LIBDIR = $(ROOTDIR)$(S)lib

OBJ_LIBO2OBJS = $(addprefix $(OBJDIR)/,$(LIBO2OBJS))
OBJ_LIBOSOBJS = $(addprefix $(OBJDIR)/,$(LIBOSOBJS))
OBJ_LIBO2CPPOBJS = $(addprefix $(OBJDIR)/,$(LIBO2CPPOBJS))
OBJ_LIBOSCPPOBJS = $(addprefix $(OBJDIR)/,$(LIBOSCPPOBJS))
OBJ_LIBASMOBJS = $(addprefix $(OBJDIR)/,$(LIBASMOBJS))

TEMPOBJ = temp.o    

ifeq ($(CPU), gcc)
    LIBOBJ   = $(OBJDIR)/xgcc_$(CODEC_NAME).o
    LIB      = xgcc_$(CODEC_NAME).img
else
    LIBOBJ   = $(OBJDIR)/xa_$(CODEC_NAME).o
    LIB      = xa_$(CODEC_NAME).img
endif

CFLAGS += \
    $(EXTRA_CFLAGS) $(EXTRA_CFLAGS2)


ifeq ($(DEBUG),1)
  NOSTRIP = 1
  OPT_O2 = -O0 -g 
  OPT_OS = -O0 -g
  CFLAGS += -DDEBUG
else
ifeq ($(CPU), gcc)
  OPT_O2 = -O2 -g 
  OPT_OS = -O2 -g 
else
  #OPT_O2 = -O3 -LNO:simd 
  OPT_O2 = -g -O2 
  OPT_OS = -Os 
endif
endif

OPT_ASM = -g -Wa,--gdwarf-2


all: $(OBJDIR) $(LIB) 
$(CODEC_NAME): $(OBJDIR) $(LIB) 

install: $(LIB)
	@echo "Installing $(LIB)"
	$(QUIET) -$(MKPATH) "$(LIBDIR)"
	$(QUIET) $(CP) $(LIB) "$(LIBDIR)"

$(OBJDIR):
	$(QUIET) -$(MKPATH) $@

ifeq ($(NOSTRIP), 1)
$(LIBOBJ): $(OBJ_LIBO2OBJS) $(OBJ_LIBOSOBJS) $(OBJ_LIBO2CPPOBJS) $(OBJ_LIBOSCPPOBJS) $(OBJ_LIBASMOBJS) $(PLUGINLIBS)
	@echo "Linking Objects"
	$(QUIET) $(CXX) -c $(OPT_O2) $(CFLAGS) -o $@ $^ \
	-Wl,-Map,$(MAPFILE) --no-standard-libraries \
	$(LDFLAGS) $(EXTRA_LDFLAGS)
else
$(LIBOBJ): $(OBJ_LIBO2OBJS) $(OBJ_LIBOSOBJS) $(OBJ_LIBO2CPPOBJS) $(OBJ_LIBOSCPPOBJS) $(OBJ_LIBASMOBJS) $(PLUGINLIBS)
	@echo "Linking Objects"
	$(QUIET) $(CXX) -c $(OPT_O2) $(CFLAGS) -o $@ $^ \
	-Wl,-Map,$(MAPFILE) --no-standard-libraries \
	-Wl,--retain-symbols-file,$(SYMFILE) \
	$(IPA_FLAGS) $(LDFLAGS) $(EXTRA_LDFLAGS)
	$(QUIET) $(OBJCOPY) --keep-global-symbols=$(SYMFILE) $@ $(TEMPOBJ)
	$(QUIET) $(OBJCOPY) --strip-unneeded $(TEMPOBJ) $@
	$(QUIET) -$(RM) $(TEMPOBJ)
endif 


$(OBJ_LIBO2OBJS): $(OBJDIR)/%.o: %.c
	@echo "Compiling $<"
	$(QUIET) $(CC) -o $@ $(OPT_O2) $(CFLAGS) $(INCLUDES) -c $<
	
$(OBJ_LIBOSOBJS): $(OBJDIR)/%.o: %.c
	@echo "Compiling $<"
	$(QUIET) $(CC) -o $@ $(OPT_OS) $(CFLAGS) $(INCLUDES) -c $<
	
$(OBJ_LIBO2CPPOBJS): $(OBJDIR)/%.o: %.cpp
	@echo "Compiling $<"
	$(QUIET) $(CXX) -o $@ $(OPT_O2) $(CFLAGS) $(INCLUDES) -c $<
	
$(OBJ_LIBOSCPPOBJS): $(OBJDIR)/%.o: %.cpp
	@echo "Compiling $<"
	$(QUIET) $(CXX) -o $@ $(OPT_OS) $(CFLAGS) $(INCLUDES) -c $<

$(OBJ_LIBASMOBJS): $(OBJDIR)/%.o: %.S
	@echo "Compiling $<"
	$(QUIET) $(CC) -o $@ $(OPT_ASM) $(ASMFLAGS) $(INCLUDES) -c $<
	
$(LIB): %.img: $(OBJDIR)/%.o
	@echo "Creating Library $@"
	$(ELFTOBIN) $< $@

clean:
	-$(RM) $(LIB) $(MAPFILE)
	-$(RM_R) $(OBJDIR) $(LIBDIR) 
