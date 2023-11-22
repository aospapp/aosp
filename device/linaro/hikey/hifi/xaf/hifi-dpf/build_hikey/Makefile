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


.PHONY: all install clean HIKEY960

NOSTRIP = 1
XF_TRACE = 1

ROOTDIR = ..
CODEC_NAME = hikey

PLUGINO2OBJS =
PLUGINLIBS =
PLUGININCLUDES =

XA_MP3_DECODER ?= 0
XA_VORBIS_DECODER ?= 1
XA_MIXER 	   ?= 1
XA_PCM 	   ?= 1


vpath %.c $(ROOTDIR)/ipc/xt-shmem/hikey
vpath %.c $(ROOTDIR)/ipc/xt-shmem
vpath %.c $(ROOTDIR)/core/util
vpath %.c $(ROOTDIR)/core/util/gdbstub
vpath %.c $(ROOTDIR)/core
vpath %.c $(ROOTDIR)/app
vpath %.c $(ROOTDIR)/audio

vpath %.S $(ROOTDIR)/ipc/xt-shmem/hikey
vpath %.S $(ROOTDIR)/core/util/gdbstub

vpath %.c $(ROOTDIR)/plugins/cadence/mixer

LIBXAFASMOBJS = \
			reset.o \
			int_vector.o \
			arch_hifi330.o \
			gdbstub-entry.o

LIBXAFO2OBJS = \
            cpu_c.o \
			dsp_debug.o \
			dsp_comm.o \
			mutex.o \
			rbtree.o \
			xf-core.o \
			xf-io.o \
			xf-mem.o \
			xf-msg.o \
			xf-sched.o \
			xa-factory.o \
			xf-shmem.o \
			tinyvprintf.o \
			xf-isr.o \
			gdbstub.o \
			xa-class-base.o \
			xa-class-audio-codec.o \
			xa-class-mixer.o \
			xf-main.o 

ifeq ($(XF_TRACE),1)
  CFLAGS         += -DXF_TRACE=1
  CFLAGS         += -DXAF_PROFILE_DSP=1
endif

ifeq ($(XA_MP3_DECODER),1)
  vpath %.c $(ROOTDIR)/plugins/cadence/mp3_dec
  CFLAGS         += -DXA_MP3_DECODER=1
  PLUGINO2OBJS   += xa-mp3-decoder.o
  PLUGINLIBS     += $(ROOTDIR)/plugins/cadence/mp3_dec/lib/hifi2/xa_mp3_dec.a 
  PLUGININCLUDES += -I$(ROOTDIR)/plugins/cadence/mp3_dec
endif

ifeq ($(XA_PCM),1)
  vpath %.c $(ROOTDIR)/plugins/cadence/pcm_proc
  CFLAGS         += -DXA_PCM=1
  PLUGINO2OBJS   += xa-pcm.o
  PLUGINLIBS     += 
  PLUGININCLUDES += -I$(ROOTDIR)/plugins/cadence/pcm_proc
endif  

ifeq ($(XA_VORBIS_DECODER),1)
  vpath %.c $(ROOTDIR)/plugins/cadence/vorbis_dec
  CFLAGS         += -DXA_VORBIS_DECODER=1
  PLUGINO2OBJS   += xa-vorbis-decoder.o
  PLUGINLIBS     += $(ROOTDIR)/plugins/cadence/vorbis_dec/lib/hifi3/xa_vorbis_dec.a
  PLUGININCLUDES += -I$(ROOTDIR)/plugins/cadence/vorbis_dec
endif

ifeq ($(XA_MIXER),1)
  vpath %.c $(ROOTDIR)/plugins/cadence/mixer
  CFLAGS         += -DXA_MIXER=1
  PLUGINO2OBJS   += xa-mixer.o
  PLUGINLIBS     += 
  PLUGININCLUDES += -I$(ROOTDIR)/plugins/cadence/mixer
endif

LIBO2OBJS = $(LIBXAFO2OBJS) $(LIBMP3DECO2OBJS) $(LIBMIXERO2OBJS) $(PLUGINO2OBJS)
LIBASMOBJS = $(LIBXAFASMOBJS)
 
INCLUDES += \
    -I$(ROOTDIR)/include \
    -I$(ROOTDIR)/include/audio \
    -I$(ROOTDIR)/include/sys/xt-shmem \
	-I$(ROOTDIR)/include/sys/xt-shmem/board-hikey \
    -I$(ROOTDIR)/plugins/cadence/mp3_dec 
    
INCLUDES += $(PLUGININCLUDES)

LDFLAGS += -nostdlib -lhal -lhandlers-board -lc -lgcc 
LDFLAGS += -mlsp="./hifi_hikey_lsp"

HIKEY960: $(CODEC_NAME) 

include $(ROOTDIR)/build_hikey/common.mk
