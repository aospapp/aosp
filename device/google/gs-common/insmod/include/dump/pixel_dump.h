#ifndef DEVICE_GOOGLE_GS_COMMON_INSMOD_INCLUDE_DUMP_PIXEL_DUMP_H_
#define DEVICE_GOOGLE_GS_COMMON_INSMOD_INCLUDE_DUMP_PIXEL_DUMP_H_

#include <string>

#define BUGREPORT_PACKING_DIR "/data/vendor/radio/logs/always-on/all_logs"
#define MODEM_LOG_DIRECTORY "/data/vendor/radio/logs/always-on"

void dumpFileContent(const char* title, const char* file_path);
void runCommand(const char* title, const char* cmd);
std::string concatenatePath(const char* folder, const char* file);
void dumpLogs(const char* SrcDir, const char* DestDir, int limit, const char* prefix);
void copyFile(const char* SrcDir, const char* DestDir);

#endif  // DEVICE_GOOGLE_GS_COMMON_INSMOD_INCLUDE_DUMP_PIXEL_DUMP_H_
