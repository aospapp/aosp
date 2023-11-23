# Copyright (C) 2022 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("@bazel_skylib//lib:paths.bzl", "paths")
load(":installable_info.bzl", "InstallableInfo", "installable_aspect")

# TODO(b/249685973): Reenable the partition rule
product_config = {}

_IMAGE_TYPES = [
    "system",
    "system_other",
    "userdata",
    "cache",
    "vendor",
    "product",
    "system_ext",
    "odm",
    "vendor_dlkm",
    "system_dlkm",
    "oem",
]

def _p(varname, default = ""):
    return product_config.get(varname, default)

def _add_common_flags_to_image_props(image_props, image_type):
    image_props[image_type + "_selinux_fc"] = _p("SELINUX_FC", "")
    image_props["building_" + image_type + "_image"] = _p("BUILDING_" + image_type.upper() + "_IMAGE", "")

def _add_common_ro_flags_to_image_props(image_props, image_type):
    image_type = image_type.lower()
    IMAGE_TYPE = image_type.upper()

    def add_board_var(varname, finalname = None):
        if not finalname:
            finalname = varname
        if _p("BOARD_" + IMAGE_TYPE + "IMAGE_" + varname.upper()):
            image_props[image_type + "_" + finalname.lower()] = _p("BOARD_" + IMAGE_TYPE + "IMAGE_" + varname.upper())

    add_board_var("EROFS_COMPRESSOR")
    add_board_var("EROFS_COMPRESS_HINTS")
    add_board_var("EROFS_PCLUSTER_SIZE")
    add_board_var("EXTFS_INODE_COUNT")
    add_board_var("EXTFS_RSV_PCT")
    add_board_var("F2FS_SLOAD_COMPRESS_FLAGS", "f2fs_sldc_flags")
    add_board_var("FILE_SYSTEM_COMPRESS", "f2fs_compress")
    add_board_var("FILE_SYSTEM_TYPE", "fs_type")
    add_board_var("JOURNAL_SIZE", "journal_size")
    add_board_var("PARTITION_RESERVED_SIZE", "reserved_size")
    add_board_var("PARTITION_SIZE", "size")
    add_board_var("SQUASHFS_BLOCK_SIZE")
    add_board_var("SQUASHFS_COMPRESSOR")
    add_board_var("SQUASHFS_COMPRESSOR_OPT")
    add_board_var("SQUASHFS_DISABLE_4K_ALIGN")
    if _p("PRODUCT_" + IMAGE_TYPE + "_BASE_FS_PATH"):
        image_props[image_type + "_base_fs_file"] = _p("PRODUCT_" + IMAGE_TYPE + "_BASE_FS_PATH")

    if not (_p("BOARD_" + IMAGE_TYPE + "IMAGE_PARTITION_SIZE") or
            _p("BOARD_" + IMAGE_TYPE + "IMAGE_PARTITION_RESERVED_SIZE") or
            _p("PRODUCT_" + IMAGE_TYPE + "_HEADROOM")):
        image_props[image_type + "_disable_sparse"] = "true"

    _add_common_flags_to_image_props(image_props, image_type)

def _generate_image_prop_dictionary(ctx, image_types, extra_props = {}):
    """Generates the image properties file.

    Args:
      file: The file that will be written to
      types: A list of one or more of "system", "system_other",
             "userdata", "cache", "vendor", "product", "system_ext",
             "odm", "vendor_dlkm", "system_dlkm", or "oem"
      extra_props: A dictionary of props to append at the end of the file.
    """
    # TODO(b/237106430): This should probably be mostly replaced with attributes on the system_image rule,
    # and then there can be a separate macro to adapt product config variables to a
    # correctly-spec'd system_image rule.

    toolchain = ctx.toolchains[":partition_toolchain_type"].toolchain_info

    for image_type in image_types:
        if image_type not in _IMAGE_TYPES:
            fail("Image type %s unknown. Valid types are %s", image_type, _IMAGE_TYPES)
    image_props = {}

    if "system" in image_types:
        if _p("INTERNAL_SYSTEM_OTHER_PARTITION_SIZE"):
            image_props["system_other_size"] = _p("INTERNAL_SYSTEM_OTHER_PARTITION_SIZE")
        if _p("PRODUCT_SYSTEM_HEADROOM"):
            image_props["system_headroom"] = _p("PRODUCT_SYSTEM_HEADROOM")
        _add_common_ro_flags_to_image_props(image_props, "system")
    if "system_other" in image_types:
        image_props["building_system_other_image"] = _p("BUILDING_SYSTEM_OTHER_IMAGE", "")
        if _p("INTERNAL_SYSTEM_OTHER_PARTITION_SIZE"):
            image_props["system_other_disable_sparse"] = "true"
    if "userdata" in image_types:
        if _p("PRODUCT_FS_CASEFOLD"):
            image_props["needs_casefold"] = _p("PRODUCT_FS_CASEFOLD")
        if _p("PRODUCT_QUOTA_PROJID"):
            image_props["needs_projid"] = _p("PRODUCT_QUOTA_PROJID")
        if _p("PRODUCT_FS_COMPRESSION"):
            image_props["needs_compress"] = _p("PRODUCT_FS_COMPRESSION")
        _add_common_ro_flags_to_image_props(image_props, "userdata")
    if "cache" in image_types:
        _add_common_ro_flags_to_image_props(image_props, "cache")
    if "vendor" in image_types:
        _add_common_ro_flags_to_image_props(image_props, "vendor")
    if "product" in image_types:
        _add_common_ro_flags_to_image_props(image_props, "product")
    if "system_ext" in image_types:
        _add_common_ro_flags_to_image_props(image_props, "system_ext")
    if "odm" in image_types:
        _add_common_ro_flags_to_image_props(image_props, "odm")
    if "vendor_dlkm" in image_types:
        _add_common_ro_flags_to_image_props(image_props, "vendor_dlkm")
    if "odm_dlkm" in image_types:
        _add_common_ro_flags_to_image_props(image_props, "odm_dlkm")
    if "system_dlkm" in image_types:
        _add_common_ro_flags_to_image_props(image_props, "system_dlkm")
    if "oem" in image_types:
        if _p("BOARD_OEMIMAGE_EXTFS_INODE_COUNT"):
            image_props["oem_extfs_inode_count"] = _p("BOARD_OEMIMAGE_EXTFS_INODE_COUNT")
        if _p("BOARD_OEMIMAGE_EXTFS_RSV_PCT"):
            image_props["oem_extfs_rsv_pct"] = _p("BOARD_OEMIMAGE_EXTFS_RSV_PCT")
        _add_common_ro_flags_to_image_props(image_props, "oem")
    image_props["ext_mkuserimg"] = toolchain.mkuserimg_mke2fs.path  #_p("MKEXTUSRIMG")

    if _p("TARGET_USERIMAGES_USE_EXT2") == "true":
        image_props["fs_type"] = "ext2"
    elif _p("TARGET_USERIMAGES_USE_EXT3") == "true":
        image_props["fs_type"] = "ext3"
    elif _p("TARGET_USERIMAGES_USE_EXT4") == "true":
        image_props["fs_type"] = "ext4"

    if _p("TARGET_USERIMAGES_SPARSE_EXT_DISABLED") != "true":
        image_props["extfs_sparse_flag"] = "-s"
    if _p("TARGET_USERIMAGES_SPARSE_EROFS_DISABLED") != "true":
        image_props["erofs_sparse_flag"] = "-s"
    if _p("TARGET_USERIMAGES_SPARSE_SQUASHFS_DISABLED") != "true":
        image_props["squashfs_sparse_flag"] = "-s"
    if _p("TARGET_USERIMAGES_SPARSE_F2FS_DISABLED") != "true":
        image_props["f2fs_sparse_flag"] = "-S"
    if _p("BOARD_EROFS_COMPRESSOR"):
        image_props["erofs_default_compressor"] = _p("BOARD_EROFS_COMPRESSOR")
    if _p("BOARD_EROFS_COMPRESS_HINTS"):
        image_props["erofs_default_compress_hints"] = _p("BOARD_EROFS_COMPRESS_HINTS")
    if _p("BOARD_EROFS_PCLUSTER_SIZE"):
        image_props["erofs_pcluster_size"] = _p("BOARD_EROFS_PCLUSTER_SIZE")
    if _p("BOARD_EROFS_SHARE_DUP_BLOCKS"):
        image_props["erofs_share_dup_blocks"] = _p("BOARD_EROFS_SHARE_DUP_BLOCKS")
    if _p("BOARD_EROFS_USE_LEGACY_COMPRESSION"):
        image_props["erofs_use_legacy_compression"] = _p("BOARD_EROFS_USE_LEGACY_COMPRESSION")
    if _p("BOARD_EXT4_SHARE_DUP_BLOCKS"):
        image_props["ext4_share_dup_blocks"] = _p("BOARD_EXT4_SHARE_DUP_BLOCKS")
    if _p("BOARD_FLASH_LOGICAL_BLOCK_SIZE"):
        image_props["flash_logical_block_size"] = _p("BOARD_FLASH_LOGICAL_BLOCK_SIZE")
    if _p("BOARD_FLASH_ERASE_BLOCK_SIZE"):
        image_props["flash_erase_block_size"] = _p("BOARD_FLASH_ERASE_BLOCK_SIZE")
    if _p("PRODUCT_SUPPORTS_BOOT_SIGNER"):
        image_props["boot_signer"] = _p("PRODUCT_SUPPORTS_BOOT_SIGNER")
    if _p("PRODUCT_SUPPORTS_VERITY"):
        image_props["verity"] = _p("PRODUCT_SUPPORTS_VERITY")
        image_props["verity_key"] = _p("PRODUCT_VERITY_SIGNING_KEY")
        image_props["verity_signer_cmd"] = paths.basename(_p("VERITY_SIGNER"))
    if _p("PRODUCT_SUPPORTS_VERITY_FEC"):
        image_props["verity_fec"] = _p("PRODUCT_SUPPORTS_VERITY_FEC")
    if _p("TARGET_BUILD_VARIANT") == "eng":
        image_props["verity_disable"] = "true"
    if _p("PRODUCT_SYSTEM_VERITY_PARTITION"):
        image_props["system_verity_block_device"] = _p("PRODUCT_SYSTEM_VERITY_PARTITION")
    if _p("PRODUCT_VENDOR_VERITY_PARTITION"):
        image_props["vendor_verity_block_device"] = _p("PRODUCT_VENDOR_VERITY_PARTITION")
    if _p("PRODUCT_PRODUCT_VERITY_PARTITION"):
        image_props["product_verity_block_device"] = _p("PRODUCT_PRODUCT_VERITY_PARTITION")
    if _p("PRODUCT_SYSTEM_EXT_VERITY_PARTITION"):
        image_props["system_ext_verity_block_device"] = _p("PRODUCT_SYSTEM_EXT_VERITY_PARTITION")
    if _p("PRODUCT_VENDOR_DLKM_VERITY_PARTITION"):
        image_props["vendor_dlkm_verity_block_device"] = _p("PRODUCT_VENDOR_DLKM_VERITY_PARTITION")
    if _p("PRODUCT_ODM_DLKM_VERITY_PARTITION"):
        image_props["odm_dlkm_verity_block_device"] = _p("PRODUCT_ODM_DLKM_VERITY_PARTITION")
    if _p("PRODUCT_SYSTEM_DLKM_VERITY_PARTITION"):
        image_props["system_dlkm_verity_block_device"] = _p("PRODUCT_SYSTEM_DLKM_VERITY_PARTITION")
    if _p("PRODUCT_SUPPORTS_VBOOT"):
        image_props["vboot"] = _p("PRODUCT_SUPPORTS_VBOOT")
        image_props["vboot_key"] = _p("PRODUCT_VBOOT_SIGNING_KEY")
        image_props["vboot_subkey"] = _p("PRODUCT_VBOOT_SIGNING_SUBKEY")
        image_props["futility"] = paths.basename(_p("FUTILITY"))
        image_props["vboot_signer_cmd"] = _p("VBOOT_SIGNER")

    # TODO(b/237106430): Avb code is commented out because it's not yet functional
    # if _p("BOARD_AVB_ENABLE"):
    #     image_props["avb_avbtool"] = paths.basename(_p("AVBTOOL"))
    #     image_props["avb_system_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_system_add_hashtree_footer_args"] = _p("BOARD_AVB_SYSTEM_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_SYSTEM_KEY_PATH"):
    #         image_props["avb_system_key_path"] = _p("BOARD_AVB_SYSTEM_KEY_PATH")
    #         image_props["avb_system_algorithm"] = _p("BOARD_AVB_SYSTEM_ALGORITHM")
    #         image_props["avb_system_rollback_index_location"] = _p("BOARD_AVB_SYSTEM_ROLLBACK_INDEX_LOCATION")
    #     image_props["avb_system_other_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_system_other_add_hashtree_footer_args"] = _p("BOARD_AVB_SYSTEM_OTHER_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_SYSTEM_OTHER_KEY_PATH"):
    #         image_props["avb_system_other_key_path"] = _p("BOARD_AVB_SYSTEM_OTHER_KEY_PATH")
    #         image_props["avb_system_other_algorithm"] = _p("BOARD_AVB_SYSTEM_OTHER_ALGORITHM")
    #     image_props["avb_vendor_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_vendor_add_hashtree_footer_args"] = _p("BOARD_AVB_VENDOR_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_VENDOR_KEY_PATH"):
    #         image_props["avb_vendor_key_path"] = _p("BOARD_AVB_VENDOR_KEY_PATH")
    #         image_props["avb_vendor_algorithm"] = _p("BOARD_AVB_VENDOR_ALGORITHM")
    #         image_props["avb_vendor_rollback_index_location"] = _p("BOARD_AVB_VENDOR_ROLLBACK_INDEX_LOCATION")
    #     image_props["avb_product_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_product_add_hashtree_footer_args"] = _p("BOARD_AVB_PRODUCT_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_PRODUCT_KEY_PATH"):
    #         image_props["avb_product_key_path"] = _p("BOARD_AVB_PRODUCT_KEY_PATH")
    #         image_props["avb_product_algorithm"] = _p("BOARD_AVB_PRODUCT_ALGORITHM")
    #         image_props["avb_product_rollback_index_location"] = _p("BOARD_AVB_PRODUCT_ROLLBACK_INDEX_LOCATION")
    #     image_props["avb_system_ext_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_system_ext_add_hashtree_footer_args"] = _p("BOARD_AVB_SYSTEM_EXT_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_SYSTEM_EXT_KEY_PATH"):
    #         image_props["avb_system_ext_key_path"] = _p("BOARD_AVB_SYSTEM_EXT_KEY_PATH")
    #         image_props["avb_system_ext_algorithm"] = _p("BOARD_AVB_SYSTEM_EXT_ALGORITHM")
    #         image_props["avb_system_ext_rollback_index_location"] = _p("BOARD_AVB_SYSTEM_EXT_ROLLBACK_INDEX_LOCATION")
    #     image_props["avb_odm_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_odm_add_hashtree_footer_args"] = _p("BOARD_AVB_ODM_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_ODM_KEY_PATH"):
    #         image_props["avb_odm_key_path"] = _p("BOARD_AVB_ODM_KEY_PATH")
    #         image_props["avb_odm_algorithm"] = _p("BOARD_AVB_ODM_ALGORITHM")
    #         image_props["avb_odm_rollback_index_location"] = _p("BOARD_AVB_ODM_ROLLBACK_INDEX_LOCATION")
    #     image_props["avb_vendor_dlkm_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_vendor_dlkm_add_hashtree_footer_args"] = _p("BOARD_AVB_VENDOR_DLKM_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_VENDOR_DLKM_KEY_PATH"):
    #         image_props["avb_vendor_dlkm_key_path"] = _p("BOARD_AVB_VENDOR_DLKM_KEY_PATH")
    #         image_props["avb_vendor_dlkm_algorithm"] = _p("BOARD_AVB_VENDOR_DLKM_ALGORITHM")
    #         image_props["avb_vendor_dlkm_rollback_index_location"] = _p("BOARD_AVB_VENDOR_DLKM_ROLLBACK_INDEX_LOCATION")
    #     image_props["avb_odm_dlkm_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_odm_dlkm_add_hashtree_footer_args"] = _p("BOARD_AVB_ODM_DLKM_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_ODM_DLKM_KEY_PATH"):
    #         image_props["avb_odm_dlkm_key_path"] = _p("BOARD_AVB_ODM_DLKM_KEY_PATH")
    #         image_props["avb_odm_dlkm_algorithm"] = _p("BOARD_AVB_ODM_DLKM_ALGORITHM")
    #         image_props["avb_odm_dlkm_rollback_index_location"] = _p("BOARD_AVB_ODM_DLKM_ROLLBACK_INDEX_LOCATION")
    #     image_props["avb_system_dlkm_hashtree_enable"] = _p("BOARD_AVB_ENABLE")
    #     image_props["avb_system_dlkm_add_hashtree_footer_args"] = _p("BOARD_AVB_SYSTEM_DLKM_ADD_HASHTREE_FOOTER_ARGS")
    #     if _p("BOARD_AVB_SYSTEM_DLKM_KEY_PATH"):
    #         image_props["avb_system_dlkm_key_path"] = _p("BOARD_AVB_SYSTEM_DLKM_KEY_PATH")
    #         image_props["avb_system_dlkm_algorithm"] = _p("BOARD_AVB_SYSTEM_DLKM_ALGORITHM")
    #         image_props["avb_system_dlkm_rollback_index_location"] = _p("BOARD_SYSTEM_SYSTEM_DLKM_ROLLBACK_INDEX_LOCATION")
    if _p("BOARD_USES_RECOVERY_AS_BOOT") == "true":
        image_props["recovery_as_boot"] = "true"
    if _p("BOARD_BUILD_GKI_BOOT_IMAGE_WITHOUT_RAMDISK") == "true":
        image_props["gki_boot_image_without_ramdisk"] = "true"

    #image_props["root_dir"] = _p("TARGET_ROOT_OUT") # TODO: replace with actual path
    if _p("PRODUCT_USE_DYNAMIC_PARTITION_SIZE") == "true":
        image_props["use_dynamic_partition_size"] = "true"
    for k, v in extra_props.items():
        image_props[k] = v

    result = "\n".join([k + "=" + v for k, v in image_props.items()])
    if result:
        result += "\n"
    return result

def get_python3(ctx):
    python_interpreter = ctx.toolchains["@bazel_tools//tools/python:toolchain_type"].py3_runtime.interpreter
    if python_interpreter.basename == "python3":
        return python_interpreter

    renamed = ctx.actions.declare_file("python3")
    ctx.actions.symlink(
        output = renamed,
        target_file = python_interpreter,
        is_executable = True,
    )
    return renamed

def _partition_impl(ctx):
    if ctx.attr.type != "system":
        fail("currently only system images are supported")

    toolchain = ctx.toolchains[":partition_toolchain_type"].toolchain_info
    python_interpreter = get_python3(ctx)

    # build_image requires that the output file be named specifically <type>.img, so
    # put all the outputs under a name-qualified folder.
    image_info = ctx.actions.declare_file(ctx.attr.name + "/image_info.txt")
    output_image = ctx.actions.declare_file(ctx.attr.name + "/" + ctx.attr.type + ".img")
    ctx.actions.write(image_info, _generate_image_prop_dictionary(ctx, [ctx.attr.type], {"skip_fsck": "true"}))

    files = {}
    for dep in ctx.attr.deps:
        files.update(dep[InstallableInfo].files)

    for v in files.keys():
        if not v.startswith("/system"):
            fail("Files outside of /system are not currently supported: %s", v)

    file_mapping_file = ctx.actions.declare_file(ctx.attr.name + "/partition_file_mapping.json")

    # It seems build_image will prepend /system to the paths when building_system_image=true
    ctx.actions.write(file_mapping_file, json.encode({k.removeprefix("/system"): v.path for k, v in files.items()}))

    staging_dir = ctx.actions.declare_directory(ctx.attr.name + "_staging_dir")

    ctx.actions.run(
        inputs = [
            image_info,
            file_mapping_file,
        ] + files.keys(),
        tools = [
            toolchain.build_image,
            toolchain.mkuserimg_mke2fs,
            python_interpreter,
        ],
        outputs = [output_image],
        executable = ctx.executable._staging_dir_builder,
        arguments = [
            file_mapping_file.path,
            staging_dir.path,
            toolchain.build_image.path,
            staging_dir.path,
            image_info.path,
            output_image.path,
            staging_dir.path,
        ],
        mnemonic = "BuildPartition",
        # TODO: the /usr/bin addition is because build_image uses the du command
        # in GetDiskUsage(). This can probably be rewritten to just use python code
        # instead.
        env = {"PATH": python_interpreter.dirname + ":/usr/bin"},
    )

    return DefaultInfo(files = depset([output_image]))

_partition = rule(
    implementation = _partition_impl,
    attrs = {
        "type": attr.string(
            mandatory = True,
            values = _IMAGE_TYPES,
        ),
        "deps": attr.label_list(
            providers = [[InstallableInfo]],
            aspects = [installable_aspect],
        ),
        "_staging_dir_builder": attr.label(
            cfg = "exec",
            doc = "The tool used to build a staging directory, because if bazel were to build it it would be entirely symlinks.",
            executable = True,
            default = "//build/bazel/rules:staging_dir_builder",
        ),
    },
    toolchains = [
        ":partition_toolchain_type",
        "@bazel_tools//tools/python:toolchain_type",
    ],
)

def partition(target_compatible_with = [], **kwargs):
    target_compatible_with = select({
        "//build/bazel/platforms/os:android": [],
        "//conditions:default": ["@platforms//:incompatible"],
    }) + target_compatible_with
    _partition(
        target_compatible_with = target_compatible_with,
        **kwargs
    )
