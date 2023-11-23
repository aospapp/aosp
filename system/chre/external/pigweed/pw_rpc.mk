#
# Makefile for Pigweed's RPC module
#
# NOTE: In order to use this, you *must* have the following:
# - Installed mypy-protobuf and protoc
# - nanopb-c git repo checked out
#

ifneq ($(PW_RPC_SRCS),)

# Environment Checks ###########################################################

# Location of various Pigweed modules
PIGWEED_DIR = $(ANDROID_BUILD_TOP)/external/pigweed
CHRE_PREFIX = $(ANDROID_BUILD_TOP)/system/chre
CHRE_UTIL_DIR = $(CHRE_PREFIX)/util
CHRE_API_DIR = $(CHRE_PREFIX)/chre_api
PIGWEED_CHRE_DIR=$(CHRE_PREFIX)/external/pigweed
PIGWEED_CHRE_UTIL_DIR = $(CHRE_UTIL_DIR)/pigweed

ifeq ($(NANOPB_PREFIX),)
$(error "PW_RPC_SRCS is non-empty. You must supply a NANOPB_PREFIX environment \
         variable containing a path to the nanopb project. Example: \
         export NANOPB_PREFIX=$$HOME/path/to/nanopb/nanopb-c")
endif

ifeq ($(PROTOC),)
PROTOC=protoc
endif

PW_RPC_GEN_PATH = $(OUT)/pw_rpc_gen

# Create proto used for header generation ######################################

PW_RPC_PROTO_GENERATOR = $(PIGWEED_DIR)/pw_protobuf_compiler/py/pw_protobuf_compiler/generate_protos.py
PW_RPC_GENERATOR_PROTO = $(PIGWEED_DIR)/pw_rpc/internal/packet.proto
PW_RPC_GENERATOR_COMPILED_PROTO = $(PW_RPC_GEN_PATH)/py/pw_rpc/internal/packet_pb2.py
PW_PROTOBUF_PROTOS = $(PIGWEED_DIR)/pw_protobuf/pw_protobuf_protos/common.proto \
	  $(PIGWEED_DIR)/pw_protobuf/pw_protobuf_protos/field_options.proto \
	  $(PIGWEED_DIR)/pw_protobuf/pw_protobuf_protos/status.proto

# Modifies PYTHONPATH so that python can see all of pigweed's modules used by
# their protoc plugins
PW_RPC_GENERATOR_CMD = PYTHONPATH=$$PYTHONPATH:$(PW_RPC_GEN_PATH)/py:$\
  $(PIGWEED_DIR)/pw_status/py:$(PIGWEED_DIR)/pw_protobuf/py:$\
  $(PIGWEED_DIR)/pw_protobuf_compiler/py $(PYTHON)

$(PW_RPC_GENERATOR_COMPILED_PROTO): $(PW_RPC_GENERATOR_PROTO)
	@echo " [PW_RPC] $<"
	$(V)mkdir -p $(PW_RPC_GEN_PATH)/py/pw_rpc/internal
	$(V)mkdir -p $(PW_RPC_GEN_PATH)/py/pw_protobuf_codegen_protos
	$(V)mkdir -p $(PW_RPC_GEN_PATH)/py/pw_protobuf_protos
	$(V)cp -R $(PIGWEED_DIR)/pw_rpc/py/pw_rpc $(PW_RPC_GEN_PATH)/py/

	$(PROTOC) -I$(PIGWEED_DIR)/pw_protobuf/pw_protobuf_protos \
	  --experimental_allow_proto3_optional \
	  --python_out=$(PW_RPC_GEN_PATH)/py/pw_protobuf_protos \
	  $(PW_PROTOBUF_PROTOS)

	$(PROTOC) -I$(PIGWEED_DIR)/pw_protobuf/pw_protobuf_codegen_protos \
	  --experimental_allow_proto3_optional \
	  --python_out=$(PW_RPC_GEN_PATH)/py/pw_protobuf_codegen_protos \
	  $(PIGWEED_DIR)/pw_protobuf/pw_protobuf_codegen_protos/codegen_options.proto

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) --out-dir=$(PW_RPC_GEN_PATH)/py/pw_rpc/internal \
	  --compile-dir=$(dir $<) --sources $(PW_RPC_GENERATOR_PROTO) \
	  --language python

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) \
	  --plugin-path=$(PIGWEED_DIR)/pw_protobuf/py/pw_protobuf/plugin.py \
	  --compile-dir=$(dir $<) --sources $(PW_RPC_GENERATOR_PROTO) \
	  --language pwpb

# Generated PW RPC Files #######################################################

PW_RPC_GEN_SRCS = $(patsubst %.proto, \
                             $(PW_RPC_GEN_PATH)/%.pb.c, \
                             $(PW_RPC_SRCS))

# Include to-be-generated files
COMMON_CFLAGS += -I$(PW_RPC_GEN_PATH)
COMMON_CFLAGS += -I$(PW_RPC_GEN_PATH)/$(PIGWEED_DIR)
COMMON_CFLAGS += $(addprefix -I$(PW_RPC_GEN_PATH)/, $(abspath $(dir $(PW_RPC_SRCS))))

COMMON_SRCS += $(PW_RPC_GEN_SRCS)

# PW RPC library ###############################################################

# Pigweed RPC include paths
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_assert/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_bytes/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_containers/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_function/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_log/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_polyfill/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_polyfill/public_overrides
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_polyfill/standard_library_public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_preprocessor/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_protobuf/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_result/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_rpc/nanopb/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_rpc/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_rpc/pwpb/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_rpc/raw/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_span/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_span/public_overrides
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_status/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_stream/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_string/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_sync/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_toolchain/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/pw_varint/public
COMMON_CFLAGS += -I$(PIGWEED_DIR)/third_party/fuchsia/repo/sdk/lib/fit/include
COMMON_CFLAGS += -I$(PIGWEED_DIR)/third_party/fuchsia/repo/sdk/lib/stdcompat/include

# Pigweed RPC sources
COMMON_SRCS += $(PIGWEED_DIR)/pw_assert_log/assert_log.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_containers/intrusive_list.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_protobuf/decoder.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_protobuf/encoder.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_protobuf/stream_decoder.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/call.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/channel.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/channel_list.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/client.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/client_call.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/endpoint.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/packet.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/server.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/server_call.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/service.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/nanopb/common.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/nanopb/method.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/nanopb/server_reader_writer.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_rpc/pwpb/server_reader_writer.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_stream/memory_stream.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_varint/stream.cc
COMMON_SRCS += $(PIGWEED_DIR)/pw_varint/varint.cc

# NanoPB header includes
COMMON_CFLAGS += -I$(NANOPB_PREFIX)

COMMON_CFLAGS += -DPW_RPC_USE_GLOBAL_MUTEX=0
COMMON_CFLAGS += -DPW_RPC_YIELD_MODE=PW_RPC_YIELD_MODE_BUSY_LOOP

# Enable closing a client stream.
COMMON_CFLAGS += -DPW_RPC_CLIENT_STREAM_END_CALLBACK


# Use dynamic channel allocation
COMMON_CFLAGS += -DPW_RPC_DYNAMIC_ALLOCATION
COMMON_CFLAGS += -DPW_RPC_DYNAMIC_CONTAINER\(type\)="chre::DynamicVector<type>"
COMMON_CFLAGS += -DPW_RPC_DYNAMIC_CONTAINER_INCLUDE='"chre/util/dynamic_vector.h"'

# NanoPB sources
COMMON_SRCS += $(NANOPB_PREFIX)/pb_common.c
COMMON_SRCS += $(NANOPB_PREFIX)/pb_decode.c
COMMON_SRCS += $(NANOPB_PREFIX)/pb_encode.c

COMMON_CFLAGS += -DPB_NO_PACKED_STRUCTS=1

# Add CHRE Pigweed util sources since nanoapps should always use these
COMMON_SRCS += $(PIGWEED_CHRE_UTIL_DIR)/chre_channel_output.cc
COMMON_SRCS += $(PIGWEED_CHRE_UTIL_DIR)/rpc_client.cc
COMMON_SRCS += $(PIGWEED_CHRE_UTIL_DIR)/rpc_helper.cc
COMMON_SRCS += $(PIGWEED_CHRE_UTIL_DIR)/rpc_server.cc
COMMON_SRCS += $(CHRE_UTIL_DIR)/nanoapp/callbacks.cc
COMMON_SRCS += $(CHRE_UTIL_DIR)/dynamic_vector_base.cc

# CHRE Pigweed overrides
COMMON_CFLAGS += -I$(PIGWEED_CHRE_DIR)/pw_log_nanoapp/public_overrides
COMMON_CFLAGS += -I$(PIGWEED_CHRE_DIR)/pw_assert_nanoapp/public_overrides

# Generate PW RPC headers ######################################################

$(PW_RPC_GEN_PATH)/%.pb.c \
        $(PW_RPC_GEN_PATH)/%.pb.h \
        $(PW_RPC_GEN_PATH)/%.rpc.pb.h \
        $(PW_RPC_GEN_PATH)/%.raw_rpc.pb.h: %.proto \
                                           %.options \
                                           $(NANOPB_GENERATOR_SRCS) \
                                           $(PW_RPC_GENERATOR_COMPILED_PROTO)
	@echo " [PW_RPC] $<"
	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(NANOPB_PROTOC) \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language nanopb \
	  --sources $<

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(PIGWEED_DIR)/pw_protobuf/py/pw_protobuf/plugin.py \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language pwpb \
		--sources $<

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(PIGWEED_DIR)/pw_rpc/py/pw_rpc/plugin_nanopb.py \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language nanopb_rpc \
	  --sources $<

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(PIGWEED_DIR)/pw_rpc/py/pw_rpc/plugin_raw.py \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language raw_rpc \
	  --sources $<

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(PIGWEED_DIR)/pw_rpc/py/pw_rpc/plugin_pwpb.py \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language pwpb_rpc \
	  --sources $<

$(PW_RPC_GEN_PATH)/%.pb.c \
        $(PW_RPC_GEN_PATH)/%.pb.h \
        $(PW_RPC_GEN_PATH)/%.rpc.pb.h \
        $(PW_RPC_GEN_PATH)/%.raw_rpc.pb.h: %.proto \
                                           $(NANOPB_OPTIONS) \
                                           $(NANOPB_GENERATOR_SRCS) \
                                           $(PW_RPC_GENERATOR_COMPILED_PROTO)
	@echo " [PW_RPC] $<"
	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(NANOPB_PROTOC) \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language nanopb \
	  --sources $<

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(PIGWEED_DIR)/pw_protobuf/py/pw_protobuf/plugin.py \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language pwpb \
	  --sources $<

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(PIGWEED_DIR)/pw_rpc/py/pw_rpc/plugin_nanopb.py \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language nanopb_rpc \
	  --sources $<

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(PIGWEED_DIR)/pw_rpc/py/pw_rpc/plugin_raw.py \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language raw_rpc \
	  --sources $<

	$(V)$(PW_RPC_GENERATOR_CMD) $(PW_RPC_PROTO_GENERATOR) \
	  --plugin-path=$(PIGWEED_DIR)/pw_rpc/py/pw_rpc/plugin_pwpb.py \
	  --out-dir=$(PW_RPC_GEN_PATH)/$(dir $<) --compile-dir=$(dir $<) --language pwpb_rpc \
	  --sources $<
endif