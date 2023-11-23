// Copyright (C) 2021 The Android Open Source Project
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

#include <ditto/instruction.h>

#include <ditto/shared_variables.h>

namespace dittosuite {

Instruction::Instruction(SyscallInterface& syscall, const std::string& name, int repeat)
    : syscall_(syscall), name_(name), repeat_(repeat) {}

void Instruction::SetUp() {}

void Instruction::Run() {
  for (int i = 0; i < repeat_; i++) {
    SetUpSingle();
    RunSingle();
    TearDownSingle();
  }
}

void Instruction::RunSynchronized(pthread_barrier_t* barrier) {
  pthread_barrier_wait(barrier);
  Instruction::Run();
}

std::thread Instruction::SpawnThread(pthread_barrier_t* barrier) {
  return std::thread([=] { RunSynchronized(barrier); });
}

void Instruction::TearDown() {}

void Instruction::SetUpSingle() {
  time_sampler_.MeasureStart();
}

void Instruction::TearDownSingle() {
  time_sampler_.MeasureEnd();
}

std::unique_ptr<Result> Instruction::CollectResults(const std::string& prefix) {
  auto result = std::make_unique<Result>(prefix + name_, repeat_);
  result->AddMeasurement("duration", TimespecToDoubleNanos(time_sampler_.GetSamples()));
  return result;
}

void Instruction::SetAbsolutePathKey(int absolute_path_key) {
  absolute_path_key_ = absolute_path_key;
}

std::string Instruction::GetAbsolutePath() {
  return std::get<std::string>(SharedVariables::Get(absolute_path_key_));
}

int Instruction::absolute_path_key_;

}  // namespace dittosuite
