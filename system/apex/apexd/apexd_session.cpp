/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "apexd_session.h"

#include "apexd_utils.h"
#include "string_log.h"

#include "session_state.pb.h"

#include <android-base/logging.h>
#include <dirent.h>
#include <sys/stat.h>

#include <filesystem>
#include <fstream>
#include <optional>
#include <utility>

using android::base::Error;
using android::base::Result;
using apex::proto::SessionState;

namespace android {
namespace apex {

namespace {

static constexpr const char* kStateFileName = "state";

std::string getSessionDir(int session_id) {
  return kApexSessionsDir + "/" + std::to_string(session_id);
}

std::string getSessionStateFilePath(int session_id) {
  return getSessionDir(session_id) + "/" + kStateFileName;
}

Result<std::string> createSessionDirIfNeeded(int session_id) {
  // create /data/sessions
  auto res = createDirIfNeeded(kApexSessionsDir, 0700);
  if (!res.ok()) {
    return res.error();
  }
  // create /data/sessions/session_id
  std::string sessionDir = getSessionDir(session_id);
  res = createDirIfNeeded(sessionDir, 0700);
  if (!res.ok()) {
    return res.error();
  }

  return sessionDir;
}

Result<void> deleteSessionDir(int session_id) {
  std::string session_dir = getSessionDir(session_id);
  LOG(DEBUG) << "Deleting " << session_dir;
  auto path = std::filesystem::path(session_dir);
  std::error_code error_code;
  std::filesystem::remove_all(path, error_code);
  if (error_code) {
    return Error() << "Failed to delete " << session_dir << " : "
                   << error_code.message();
  }
  return {};
}

}  // namespace

ApexSession::ApexSession(SessionState state) : state_(std::move(state)) {}

Result<ApexSession> ApexSession::CreateSession(int session_id) {
  SessionState state;
  // Create session directory
  auto sessionPath = createSessionDirIfNeeded(session_id);
  if (!sessionPath.ok()) {
    return sessionPath.error();
  }
  state.set_id(session_id);

  return ApexSession(state);
}
Result<ApexSession> ApexSession::GetSessionFromFile(const std::string& path) {
  SessionState state;
  std::fstream stateFile(path, std::ios::in | std::ios::binary);
  if (!stateFile) {
    return Error() << "Failed to open " << path;
  }

  if (!state.ParseFromIstream(&stateFile)) {
    return Error() << "Failed to parse " << path;
  }

  return ApexSession(state);
}

Result<ApexSession> ApexSession::GetSession(int session_id) {
  auto path = getSessionStateFilePath(session_id);

  return GetSessionFromFile(path);
}

std::vector<ApexSession> ApexSession::GetSessions() {
  std::vector<ApexSession> sessions;

  Result<std::vector<std::string>> sessionPaths = ReadDir(
      kApexSessionsDir, [](const std::filesystem::directory_entry& entry) {
        std::error_code ec;
        return entry.is_directory(ec);
      });

  if (!sessionPaths.ok()) {
    return sessions;
  }

  for (const std::string& sessionDirPath : *sessionPaths) {
    // Try to read session state
    auto session = GetSessionFromFile(sessionDirPath + "/" + kStateFileName);
    if (!session.ok()) {
      LOG(WARNING) << session.error();
      continue;
    }
    sessions.push_back(std::move(*session));
  }

  return sessions;
}

std::vector<ApexSession> ApexSession::GetSessionsInState(
    SessionState::State state) {
  auto sessions = GetSessions();
  sessions.erase(
      std::remove_if(sessions.begin(), sessions.end(),
                     [&](const ApexSession &s) { return s.GetState() != state; }),
      sessions.end());

  return sessions;
}

std::vector<ApexSession> ApexSession::GetActiveSessions() {
  auto sessions = GetSessions();
  std::vector<ApexSession> activeSessions;
  for (const ApexSession& session : sessions) {
    if (!session.IsFinalized() && session.GetState() != SessionState::UNKNOWN) {
      activeSessions.push_back(session);
    }
  }
  return activeSessions;
}

SessionState::State ApexSession::GetState() const { return state_.state(); }

int ApexSession::GetId() const { return state_.id(); }

std::string ApexSession::GetBuildFingerprint() const {
  return state_.expected_build_fingerprint();
}

bool ApexSession::IsFinalized() const {
  switch (GetState()) {
    case SessionState::SUCCESS:
    case SessionState::ACTIVATION_FAILED:
    case SessionState::REVERTED:
    case SessionState::REVERT_FAILED:
      return true;
    default:
      return false;
  }
}

bool ApexSession::HasRollbackEnabled() const {
  return state_.rollback_enabled();
}

bool ApexSession::IsRollback() const { return state_.is_rollback(); }

int ApexSession::GetRollbackId() const { return state_.rollback_id(); }

std::string ApexSession::GetCrashingNativeProcess() const {
  return state_.crashing_native_process();
}

const google::protobuf::RepeatedField<int> ApexSession::GetChildSessionIds()
    const {
  return state_.child_session_ids();
}

void ApexSession::SetChildSessionIds(
    const std::vector<int>& child_session_ids) {
  *(state_.mutable_child_session_ids()) = {child_session_ids.begin(),
                                           child_session_ids.end()};
}

const google::protobuf::RepeatedPtrField<std::string>
ApexSession::GetApexNames() const {
  return state_.apex_names();
}

void ApexSession::SetBuildFingerprint(const std::string& fingerprint) {
  *(state_.mutable_expected_build_fingerprint()) = fingerprint;
}

void ApexSession::SetHasRollbackEnabled(const bool enabled) {
  state_.set_rollback_enabled(enabled);
}

void ApexSession::SetIsRollback(const bool is_rollback) {
  state_.set_is_rollback(is_rollback);
}

void ApexSession::SetRollbackId(const int rollback_id) {
  state_.set_rollback_id(rollback_id);
}

void ApexSession::SetCrashingNativeProcess(
    const std::string& crashing_process) {
  state_.set_crashing_native_process(crashing_process);
}

void ApexSession::AddApexName(const std::string& apex_name) {
  state_.add_apex_names(apex_name);
}

Result<void> ApexSession::UpdateStateAndCommit(
    const SessionState::State& session_state) {
  state_.set_state(session_state);

  auto stateFilePath = getSessionStateFilePath(state_.id());

  std::fstream stateFile(stateFilePath,
                         std::ios::out | std::ios::trunc | std::ios::binary);
  if (!state_.SerializeToOstream(&stateFile)) {
    return Error() << "Failed to write state file " << stateFilePath;
  }

  return {};
}

Result<void> ApexSession::DeleteSession() const {
  return deleteSessionDir(GetId());
}

std::ostream& operator<<(std::ostream& out, const ApexSession& session) {
  return out << "[id = " << session.GetId()
             << "; state = " << SessionState::State_Name(session.GetState())
             << "]";
}

}  // namespace apex
}  // namespace android
