//
// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "host/commands/cvd/fetch/fetch_cvd.h"

#include <sys/stat.h>

#include <chrono>
#include <fstream>
#include <future>
#include <iostream>
#include <iterator>
#include <memory>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <android-base/logging.h>
#include <android-base/strings.h>
#include <curl/curl.h>
#include <gflags/gflags.h>

#include "common/libs/fs/shared_fd.h"
#include "common/libs/utils/archive.h"
#include "common/libs/utils/environment.h"
#include "common/libs/utils/files.h"
#include "common/libs/utils/flag_parser.h"
#include "common/libs/utils/result.h"
#include "common/libs/utils/subprocess.h"
#include "host/libs/config/fetcher_config.h"
#include "host/libs/web/build_api.h"
#include "host/libs/web/credential_source.h"

namespace cuttlefish {
namespace {

const std::string DEFAULT_BRANCH = "aosp-master";
const std::string DEFAULT_BUILD_TARGET = "aosp_cf_x86_64_phone-userdebug";
const std::string HOST_TOOLS = "cvd-host_package.tar.gz";
const std::string KERNEL = "kernel";
const std::string OTA_TOOLS = "otatools.zip";
const std::string OTA_TOOLS_DIR = "/otatools/";
const int DEFAULT_RETRY_PERIOD = 20;
const std::string USAGE_MESSAGE =
    "<flags>\n"
    "\n"
    "\"*_build\" flags accept values in the following format:\n"
    "\"branch/build_target\" - latest build of \"branch\" for "
    "\"build_target\"\n"
    "\"build_id/build_target\" - build \"build_id\" for \"build_target\"\n"
    "\"branch\" - latest build of \"branch\" for "
    "\"aosp_cf_x86_phone-userdebug\"\n"
    "\"build_id\" - build \"build_id\" for \"aosp_cf_x86_phone-userdebug\"\n";
const mode_t RWX_ALL_MODE = S_IRWXU | S_IRWXG | S_IRWXO;

struct BuildApiFlags {
  std::string api_key = "";
  std::string credential_source = "";
  std::chrono::seconds wait_retry_period =
      std::chrono::seconds(DEFAULT_RETRY_PERIOD);
  bool external_dns_resolver =
#ifdef __BIONIC__
      true;
#else
      false;
#endif
};

struct BuildSourceFlags {
  std::string default_build = DEFAULT_BRANCH + "/" + DEFAULT_BUILD_TARGET;
  std::string system_build = "";
  std::string kernel_build = "";
  std::string boot_build = "";
  std::string bootloader_build = "";
  std::string otatools_build = "";
  std::string host_package_build = "";
};

struct DownloadFlags {
  std::string boot_artifact = "";
  bool download_img_zip = true;
  bool download_target_files_zip = false;
};

struct FetchFlags {
  std::string target_directory = "";
  bool keep_downloaded_archives = false;
  bool helpxml = false;
  BuildApiFlags build_api_flags;
  BuildSourceFlags build_source_flags;
  DownloadFlags download_flags;
};

struct Builds {
  Build default_build;
  std::optional<Build> system;
  std::optional<Build> kernel;
  std::optional<Build> boot;
  std::optional<Build> bootloader;
  std::optional<Build> otatools;
  std::optional<Build> host_package;
};

std::vector<Flag> GetFlagsVector(FetchFlags& fetch_flags,
                                 BuildApiFlags& build_api_flags,
                                 BuildSourceFlags& build_source_flags,
                                 DownloadFlags& download_flags,
                                 int& retry_period, std::string& directory) {
  std::vector<Flag> flags;
  flags.emplace_back(
      GflagsCompatFlag("directory", directory)
          .Help("Target directory to fetch files into. (deprecated)"));
  flags.emplace_back(
      GflagsCompatFlag("target_directory", fetch_flags.target_directory)
          .Help("Target directory to fetch files into."));
  flags.emplace_back(GflagsCompatFlag("keep_downloaded_archives",
                                      fetch_flags.keep_downloaded_archives)
                         .Help("Keep downloaded zip/tar."));

  flags.emplace_back(GflagsCompatFlag("api_key", build_api_flags.api_key)
                         .Help("API key ofr the Android Build API"));
  flags.emplace_back(
      GflagsCompatFlag("credential_source", build_api_flags.credential_source)
          .Help("Build API credential source"));
  flags.emplace_back(GflagsCompatFlag("wait_retry_period", retry_period)
                         .Help("Retry period for pending builds given in "
                               "seconds. Set to 0 to not wait."));
  flags.emplace_back(
      GflagsCompatFlag("external_dns_resolver",
                       build_api_flags.external_dns_resolver)
          .Help("Use an out-of-process mechanism to resolve DNS queries"));

  flags.emplace_back(
      GflagsCompatFlag("default_build", build_source_flags.default_build)
          .Help("source for the cuttlefish build to use (vendor.img + host)"));
  flags.emplace_back(
      GflagsCompatFlag("system_build", build_source_flags.system_build)
          .Help("source for system.img and product.img"));
  flags.emplace_back(
      GflagsCompatFlag("kernel_build", build_source_flags.kernel_build)
          .Help("source for the kernel or gki target"));
  flags.emplace_back(
      GflagsCompatFlag("boot_build", build_source_flags.boot_build)
          .Help("source for the boot or gki target"));
  flags.emplace_back(
      GflagsCompatFlag("bootloader_build", build_source_flags.bootloader_build)
          .Help("source for the bootloader target"));
  flags.emplace_back(
      GflagsCompatFlag("otatools_build", build_source_flags.otatools_build)
          .Help("source for the host ota tools"));
  flags.emplace_back(GflagsCompatFlag("host_package_build",
                                      build_source_flags.host_package_build)
                         .Help("source for the host cvd tools"));

  flags.emplace_back(
      GflagsCompatFlag("boot_artifact", download_flags.boot_artifact)
          .Help("name of the boot image in boot_build"));
  flags.emplace_back(
      GflagsCompatFlag("download_img_zip", download_flags.download_img_zip)
          .Help("Whether to fetch the -img-*.zip file."));
  flags.emplace_back(
      GflagsCompatFlag("download_target_files_zip",
                       download_flags.download_target_files_zip)
          .Help("Whether to fetch the -target_files-*.zip file."));

  flags.emplace_back(UnexpectedArgumentGuard());
  flags.emplace_back(HelpFlag(flags, USAGE_MESSAGE));
  flags.emplace_back(
      HelpXmlFlag(flags, std::cout, fetch_flags.helpxml, USAGE_MESSAGE));
  return flags;
}

Result<FetchFlags> GetFlagValues(int argc, char** argv) {
  FetchFlags fetch_flags;
  BuildApiFlags build_api_flags;
  BuildSourceFlags build_source_flags;
  DownloadFlags download_flags;
  int retry_period = DEFAULT_RETRY_PERIOD;
  std::string directory = "";

  std::vector<Flag> flags =
      GetFlagsVector(fetch_flags, build_api_flags, build_source_flags,
                     download_flags, retry_period, directory);
  std::vector<std::string> args = ArgsToVec(argc - 1, argv + 1);
  CF_EXPECT(ParseFlags(flags, args), "Could not process command line flags.");

  build_api_flags.wait_retry_period = std::chrono::seconds(retry_period);
  if (directory != "") {
    LOG(ERROR) << "Please use --target_directory instead of --directory";
    if (fetch_flags.target_directory == "") {
      fetch_flags.target_directory = directory;
    }
  } else {
    if (fetch_flags.target_directory == "") {
      fetch_flags.target_directory = CurrentDirectory();
    }
  }

  fetch_flags.build_api_flags = build_api_flags;
  fetch_flags.build_source_flags = build_source_flags;
  fetch_flags.download_flags = download_flags;
  return {fetch_flags};
}

Result<std::string> DownloadImageZip(BuildApi& build_api, const Build& build,
                                     const std::string& target_directory) {
  std::string img_zip_name = GetBuildZipName(build, "img");
  return build_api.DownloadFile(build, target_directory, img_zip_name);
}

Result<std::vector<std::string>> DownloadImages(
    BuildApi& build_api, const Build& build,
    const std::string& target_directory, const std::vector<std::string>& images,
    const bool keep_archives) {
  std::string local_path =
      CF_EXPECT(DownloadImageZip(build_api, build, target_directory));
  std::vector<std::string> files = CF_EXPECT(
      ExtractImages(local_path, target_directory, images, keep_archives));
  return files;
}

Result<std::string> DownloadTargetFiles(BuildApi& build_api, const Build& build,
                                        const std::string& target_directory) {
  std::string target_files_name = GetBuildZipName(build, "target_files");
  return build_api.DownloadFile(build, target_directory, target_files_name);
}

Result<std::vector<std::string>> DownloadHostPackage(
    BuildApi& build_api, const Build& build,
    const std::string& target_directory, const bool keep_archives) {
  std::string local_path =
      CF_EXPECT(build_api.DownloadFile(build, target_directory, HOST_TOOLS));
  return ExtractArchiveContents(local_path, target_directory, keep_archives);
}

Result<std::vector<std::string>> DownloadOtaTools(
    BuildApi& build_api, const Build& build,
    const std::string& target_directory, const bool keep_archives) {
  std::string local_path =
      CF_EXPECT(build_api.DownloadFile(build, target_directory, OTA_TOOLS));
  std::string otatools_dir = target_directory + OTA_TOOLS_DIR;
  CF_EXPECT(EnsureDirectoryExists(otatools_dir, RWX_ALL_MODE));
  return ExtractArchiveContents(local_path, otatools_dir, keep_archives);
}

Result<std::string> DownloadMiscInfo(BuildApi& build_api, const Build& build,
                                     const std::string& target_dir) {
  return build_api.DownloadFile(build, target_dir, "misc_info.txt");
}

Result<std::vector<std::string>> DownloadBoot(
    BuildApi& build_api, const Build& build,
    const std::string& specified_artifact, const std::string& target_dir,
    const bool keep_archives) {
  std::string target_boot = target_dir + "/boot.img";
  const std::string& boot_artifact =
      specified_artifact != "" ? specified_artifact : "boot.img";
  if (specified_artifact != "") {
    Result<std::string> artifact_result =
        build_api.DownloadFile(build, target_dir, specified_artifact);
    if (artifact_result.ok()) {
      RenameFile(artifact_result.value(), target_boot);
      return {{target_boot}};
    }
    LOG(INFO) << "Find " << boot_artifact << " in the img zip";
  }

  std::vector<std::string> files{target_boot};
  std::string img_zip =
      CF_EXPECT(DownloadImageZip(build_api, build, target_dir));
  const bool keep_img_zip_archive_for_vendor_boot = true;
  std::string extracted_boot =
      CF_EXPECT(ExtractImage(img_zip, target_dir, boot_artifact,
                             keep_img_zip_archive_for_vendor_boot));
  if (extracted_boot != target_boot) {
    CF_EXPECT(RenameFile(extracted_boot, target_boot));
  }
  Result<std::string> extracted_vendor_boot_result =
      ExtractImage(img_zip, target_dir, "vendor_boot.img", keep_archives);
  if (extracted_vendor_boot_result.ok()) {
    files.push_back(extracted_vendor_boot_result.value());
  }
  return files;
}

Result<void> AddFilesToConfig(FileSource purpose, const Build& build,
                              const std::vector<std::string>& paths,
                              FetcherConfig* config,
                              const std::string& directory_prefix,
                              bool override_entry = false) {
  for (const std::string& path : paths) {
    std::string_view local_path(path);
    if (!android::base::ConsumePrefix(&local_path, directory_prefix)) {
      LOG(ERROR) << "Failed to remove prefix " << directory_prefix << " from "
                 << local_path;
    }
    while (android::base::StartsWith(local_path, "/")) {
      android::base::ConsumePrefix(&local_path, "/");
    }
    // TODO(schuffelen): Do better for local builds here.
    auto id = std::visit([](auto&& arg) { return arg.id; }, build);
    auto target = std::visit([](auto&& arg) { return arg.target; }, build);
    CvdFile file(purpose, id, target, std::string(local_path));
    CF_EXPECT(config->add_cvd_file(file, override_entry),
              "Duplicate file \"" << file << "\", Existing file: \""
                                  << config->get_cvd_files()[path]
                                  << "\". Failed to add path \"" << path
                                  << "\"");
  }
  return {};
}

std::unique_ptr<CredentialSource> TryOpenServiceAccountFile(
    HttpClient& http_client, const std::string& path) {
  LOG(VERBOSE) << "Attempting to open service account file \"" << path << "\"";
  Json::CharReaderBuilder builder;
  std::ifstream ifs(path);
  Json::Value content;
  std::string errorMessage;
  if (!Json::parseFromStream(builder, ifs, &content, &errorMessage)) {
    LOG(VERBOSE) << "Could not read config file \"" << path
                 << "\": " << errorMessage;
    return {};
  }
  static constexpr char BUILD_SCOPE[] =
      "https://www.googleapis.com/auth/androidbuild.internal";
  auto result = ServiceAccountOauthCredentialSource::FromJson(
      http_client, content, BUILD_SCOPE);
  if (!result.ok()) {
    LOG(VERBOSE) << "Failed to load service account json file: \n"
                 << result.error().Trace();
    return {};
  }
  return std::unique_ptr<CredentialSource>(
      new ServiceAccountOauthCredentialSource(std::move(*result)));
}

Result<void> ProcessHostPackage(BuildApi& build_api, const Build& build,
                                const std::string& target_dir,
                                FetcherConfig* config,
                                const std::string& host_package_build,
                                const bool keep_archives) {
  std::vector<std::string> host_package_files = CF_EXPECT(
      DownloadHostPackage(build_api, build, target_dir, keep_archives));
  CF_EXPECT(AddFilesToConfig(host_package_build != ""
                                 ? FileSource::HOST_PACKAGE_BUILD
                                 : FileSource::DEFAULT_BUILD,
                             build, host_package_files, config, target_dir));
  return {};
}

BuildApi GetBuildApi(const BuildApiFlags& flags) {
  auto resolver =
      flags.external_dns_resolver ? GetEntDnsResolve : NameResolver();
  std::unique_ptr<HttpClient> curl = HttpClient::CurlClient(resolver);
  std::unique_ptr<HttpClient> retrying_http_client =
      HttpClient::ServerErrorRetryClient(*curl, 10,
                                         std::chrono::milliseconds(5000));
  std::unique_ptr<CredentialSource> credential_source;
  if (auto crds = TryOpenServiceAccountFile(*curl, flags.credential_source)) {
    credential_source = std::move(crds);
  } else if (flags.credential_source == "gce") {
    credential_source =
        GceMetadataCredentialSource::make(*retrying_http_client);
  } else if (flags.credential_source == "") {
    std::string file = StringFromEnv("HOME", ".") + "/.acloud_oauth2.dat";
    LOG(VERBOSE) << "Probing acloud credentials at " << file;
    if (FileExists(file)) {
      std::ifstream stream(file);
      auto attempt_load =
          RefreshCredentialSource::FromOauth2ClientFile(*curl, stream);
      if (attempt_load.ok()) {
        credential_source.reset(
            new RefreshCredentialSource(std::move(*attempt_load)));
      } else {
        LOG(VERBOSE) << "Failed to load acloud credentials: "
                     << attempt_load.error().Trace();
      }
    } else {
      LOG(INFO) << "\"" << file << "\" missing, running without credentials";
    }
  } else {
    credential_source = FixedCredentialSource::make(flags.credential_source);
  }
  return BuildApi(std::move(retrying_http_client), std::move(curl),
                  std::move(credential_source), flags.api_key,
                  flags.wait_retry_period);
}

Result<std::optional<Build>> GetBuildHelper(BuildApi& build_api,
                                            const std::string& build_source,
                                            const std::string& build_target) {
  if (build_source == "") {
    return std::nullopt;
  }
  return CF_EXPECT(build_api.ArgumentToBuild(build_source, build_target),
                   "Unable to create build from source ("
                       << build_source << ") and target (" << build_target
                       << ")");
}

Result<Builds> GetBuildsFromSources(BuildApi& build_api,
                                    const BuildSourceFlags& build_sources) {
  std::optional<Build> default_build = CF_EXPECT(GetBuildHelper(
      build_api, build_sources.default_build, DEFAULT_BUILD_TARGET));
  CF_EXPECT(default_build.has_value());
  Builds result = Builds{
      .default_build = default_build.value(),
      .system = CF_EXPECT(GetBuildHelper(build_api, build_sources.system_build,
                                         DEFAULT_BUILD_TARGET)),
      .kernel = CF_EXPECT(
          GetBuildHelper(build_api, build_sources.kernel_build, KERNEL)),
      .boot = CF_EXPECT(GetBuildHelper(build_api, build_sources.boot_build,
                                       "gki_x86_64-user")),
      .bootloader = CF_EXPECT(GetBuildHelper(
          build_api, build_sources.bootloader_build, "u-boot_crosvm_x86_64")),
      .otatools = CF_EXPECT(GetBuildHelper(
          build_api, build_sources.otatools_build, DEFAULT_BUILD_TARGET)),
      .host_package = CF_EXPECT(GetBuildHelper(
          build_api, build_sources.host_package_build, DEFAULT_BUILD_TARGET)),
  };
  if (!result.otatools.has_value()) {
    if (result.system.has_value()) {
      result.otatools = result.system.value();
    } else if (result.kernel.has_value()) {
      result.otatools = result.default_build;
    }
  }
  if (!result.host_package.has_value()) {
    result.host_package = result.default_build;
  }
  return {result};
}

}  // namespace

Result<void> FetchCvdMain(int argc, char** argv) {
  ::android::base::InitLogging(argv, android::base::StderrLogger);
  const FetchFlags flags = CF_EXPECT(GetFlagValues(argc, argv));

#ifdef __BIONIC__
  // TODO(schuffelen): Find a better way to deal with tzdata
  setenv("ANDROID_TZDATA_ROOT", "/", /* overwrite */ 0);
  setenv("ANDROID_ROOT", "/", /* overwrite */ 0);
#endif

  std::string target_dir = AbsolutePath(flags.target_directory);
  CF_EXPECT(EnsureDirectoryExists(target_dir, RWX_ALL_MODE));
  FetcherConfig config;
  curl_global_init(CURL_GLOBAL_DEFAULT);
  {
    BuildApi build_api = GetBuildApi(flags.build_api_flags);
    const Builds builds =
        CF_EXPECT(GetBuildsFromSources(build_api, flags.build_source_flags));

    auto process_pkg_ret = std::async(
        std::launch::async, ProcessHostPackage, std::ref(build_api),
        std::cref(builds.host_package.value()), std::cref(target_dir), &config,
        std::cref(flags.build_source_flags.host_package_build),
        std::cref(flags.keep_downloaded_archives));

    if (builds.otatools.has_value()) {
      std::vector<std::string> ota_tools_files = CF_EXPECT(
          DownloadOtaTools(build_api, builds.otatools.value(), target_dir,
                           flags.keep_downloaded_archives));
      CF_EXPECT(AddFilesToConfig(FileSource::DEFAULT_BUILD,
                                 builds.default_build, ota_tools_files, &config,
                                 target_dir));
    }
    if (flags.download_flags.download_img_zip) {
      std::string local_path = CF_EXPECT(
          DownloadImageZip(build_api, builds.default_build, target_dir));
      std::vector<std::string> image_files = CF_EXPECT(ExtractArchiveContents(
          local_path, target_dir, flags.keep_downloaded_archives));
      LOG(INFO) << "Adding img-zip files for default build";
      for (auto& file : image_files) {
        LOG(INFO) << file;
      }
      CF_EXPECT(AddFilesToConfig(FileSource::DEFAULT_BUILD,
                                 builds.default_build, image_files, &config,
                                 target_dir));
    }
    if (builds.system.has_value() ||
        flags.download_flags.download_target_files_zip) {
      std::string default_target_dir = target_dir + "/default";
      CF_EXPECT(EnsureDirectoryExists(default_target_dir), RWX_ALL_MODE);
      std::string target_files = CF_EXPECT(DownloadTargetFiles(
          build_api, builds.default_build, default_target_dir));
      LOG(INFO) << "Adding target files for default build";
      CF_EXPECT(AddFilesToConfig(FileSource::DEFAULT_BUILD,
                                 builds.default_build, {target_files}, &config,
                                 target_dir));
    }

    if (builds.system.has_value()) {
      bool system_in_img_zip = true;
      if (flags.download_flags.download_img_zip) {
        auto image_files = DownloadImages(
            build_api, builds.system.value(), target_dir,
            {"system.img", "product.img"}, flags.keep_downloaded_archives);
        if (!image_files.ok() || image_files->empty()) {
          LOG(INFO)
              << "Could not find system image for " << builds.system.value()
              << "in the img zip. Assuming a super image build, which will "
              << "get the system image from the target zip.";
          system_in_img_zip = false;
        } else {
          LOG(INFO) << "Adding img-zip files for system build";
          CF_EXPECT(AddFilesToConfig(FileSource::SYSTEM_BUILD,
                                     builds.system.value(), *image_files,
                                     &config, target_dir, true));
        }
      }
      std::string system_target_dir = target_dir + "/system";
      CF_EXPECT(EnsureDirectoryExists(system_target_dir, RWX_ALL_MODE));
      std::string target_files = CF_EXPECT(DownloadTargetFiles(
          build_api, builds.system.value(), system_target_dir));
      CF_EXPECT(AddFilesToConfig(FileSource::SYSTEM_BUILD,
                                 builds.system.value(), {target_files}, &config,
                                 target_dir));
      if (!system_in_img_zip) {
        std::string extracted_system = CF_EXPECT(
            ExtractImage(target_files, target_dir, "IMAGES/system.img",
                         flags.keep_downloaded_archives));
        CF_EXPECT(RenameFile(extracted_system, target_dir + "/system.img"));

        Result<std::string> extracted_product_result =
            ExtractImage(target_files, target_dir, "IMAGES/product.img",
                         flags.keep_downloaded_archives);
        if (extracted_product_result.ok()) {
          CF_EXPECT(RenameFile(extracted_product_result.value(),
                               target_dir + "/product.img"));
        }

        Result<std::string> extracted_system_ext_result =
            ExtractImage(target_files, target_dir, "IMAGES/system_ext.img",
                         flags.keep_downloaded_archives);
        if (extracted_system_ext_result.ok()) {
          CF_EXPECT(RenameFile(extracted_system_ext_result.value(),
                               target_dir + "/system_ext.img"));
        }

        Result<std::string> extracted_vbmeta_system =
            ExtractImage(target_files, target_dir, "IMAGES/vbmeta_system.img",
                         flags.keep_downloaded_archives);
        if (extracted_vbmeta_system.ok()) {
          CF_EXPECT(RenameFile(extracted_vbmeta_system.value(),
                               target_dir + "/vbmeta_system.img"));
        }
        // This should technically call AddFilesToConfig with the produced
        // files, but it will conflict with the ones produced from the default
        // system image and pie doesn't care about the produced file list
        // anyway.
      }
    }

    if (builds.kernel.has_value()) {
      std::string local_path = target_dir + "/kernel";
      // If the kernel is from an arm/aarch64 build, the artifact will be called
      // Image.
      std::string kernel_filepath = CF_EXPECT(build_api.DownloadFileWithBackup(
          builds.kernel.value(), target_dir, "bzImage", "Image"));
      RenameFile(kernel_filepath, local_path);
      CF_EXPECT(AddFilesToConfig(FileSource::KERNEL_BUILD,
                                 builds.kernel.value(), {local_path}, &config,
                                 target_dir));

      // Certain kernel builds do not have corresponding ramdisks.
      Result<std::string> initramfs_img_result = build_api.DownloadFile(
          builds.kernel.value(), target_dir, "initramfs.img");
      if (initramfs_img_result.ok()) {
        CF_EXPECT(AddFilesToConfig(
            FileSource::KERNEL_BUILD, builds.kernel.value(),
            {initramfs_img_result.value()}, &config, target_dir));
      }
    }

    if (builds.boot.has_value()) {
      std::vector<std::string> boot_files = CF_EXPECT(DownloadBoot(
          build_api, builds.boot.value(), flags.download_flags.boot_artifact,
          target_dir, flags.keep_downloaded_archives));
      CF_EXPECT(AddFilesToConfig(FileSource::BOOT_BUILD, builds.boot.value(),
                                 boot_files, &config, target_dir, true));
    }

    // Some older builds might not have misc_info.txt, so permit errors on
    // fetching misc_info.txt
    auto misc_info =
        DownloadMiscInfo(build_api, builds.default_build, target_dir);
    if (misc_info.ok()) {
      CF_EXPECT(AddFilesToConfig(FileSource::DEFAULT_BUILD,
                                 builds.default_build, {misc_info.value()},
                                 &config, target_dir, true));
    }

    if (builds.bootloader.has_value()) {
      std::string local_path = target_dir + "/bootloader";
      // If the bootloader is from an arm/aarch64 build, the artifact will be of
      // filetype bin.
      std::string bootloader_filepath =
          CF_EXPECT(build_api.DownloadFileWithBackup(builds.bootloader.value(),
                                                     target_dir, "u-boot.rom",
                                                     "u-boot.bin"));
      RenameFile(bootloader_filepath, local_path);
      CF_EXPECT(AddFilesToConfig(FileSource::BOOTLOADER_BUILD,
                                 builds.bootloader.value(), {local_path},
                                 &config, target_dir, true));
    }

    // Wait for ProcessHostPackage to return.
    CF_EXPECT(process_pkg_ret.get(),
              "Could not download host package for " << builds.default_build);
  }
  curl_global_cleanup();

  // Due to constraints of the build system, artifacts intentionally cannot
  // determine their own build id. So it's unclear which build number fetch_cvd
  // itself was built at.
  // https://android.googlesource.com/platform/build/+/979c9f3/Changes.md#build_number
  std::string fetcher_path = target_dir + "/fetcher_config.json";
  CF_EXPECT(AddFilesToConfig(GENERATED, DeviceBuild("", ""), {fetcher_path},
                             &config, target_dir));
  config.SaveToFile(fetcher_path);

  for (const auto& file : config.get_cvd_files()) {
    std::cout << target_dir << "/" << file.second.file_path << "\n";
  }
  std::cout << std::flush;

  return {};
}

}  // namespace cuttlefish
