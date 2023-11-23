set(_dumpstate_CXX_FLAGS -Wall -Werror -std=c++17)

include(${trout_SRC_ROOT}/cmake/grpc.cmake)
include(${trout_SRC_ROOT}/cmake/protobuf.cmake)
include(${trout_SRC_ROOT}/cmake/utils/grpc_cpp_generator.cmake)

get_filename_component(dumpstate_SRC_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/.. ABSOLUTE)

if (NOT trout_GENS_DIR)
  set(trout_GENS_DIR ${PROJECT_BINARY_DIR}/gens)
endif()

trout_generate_grpc_cpp_from_proto(
    SRCS
    ${dumpstate_SRC_ROOT}/proto/DumpstateServer.proto)

# ========== Protobuf generated code ==================

add_library(dumpstate_protobuf_gen
    ${trout_GENS_DIR}/DumpstateServer.pb.cc
    ${trout_GENS_DIR}/DumpstateServer.grpc.pb.cc
)

target_link_libraries(dumpstate_protobuf_gen
    ${trout_GRPCXX_LIBRARIES}
)

target_compile_options(dumpstate_protobuf_gen PRIVATE ${_dumpstate_CXX_FLAGS} -Wno-array-bounds)

# ========== dumpstate_grpc_server ==============

add_executable(dumpstate_grpc_server
    ${dumpstate_SRC_ROOT}/DumpstateGrpcServer.cpp
    ${dumpstate_SRC_ROOT}/server_main.cpp
)

target_include_directories(dumpstate_grpc_server
    PRIVATE ${trout_GENS_DIR}
)

target_link_libraries(dumpstate_grpc_server
    dumpstate_protobuf_gen
)

target_compile_options(dumpstate_grpc_server PRIVATE ${_dumpstate_CXX_FLAGS})
