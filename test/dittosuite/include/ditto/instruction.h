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

#pragma once

#include <pthread.h>

#include <memory>
#include <string>
#include <thread>

#include <ditto/result.h>
#include <ditto/sampler.h>
#include <ditto/syscall.h>

namespace dittosuite {

enum class Order { kSequential, kRandom };
enum class Reseeding { kOnce, kEachRoundOfCycles, kEachCycle };

class Instruction {
 public:
  explicit Instruction(SyscallInterface& syscall, const std::string& name, int repeat);
  virtual ~Instruction() = default;

  virtual void SetUp();
  void Run();
  void RunSynchronized(pthread_barrier_t* barrier);
  std::thread SpawnThread(pthread_barrier_t* barrier);
  virtual void TearDown();

  virtual std::unique_ptr<Result> CollectResults(const std::string& prefix);

  static void SetAbsolutePathKey(int absolute_path_key);

 protected:
  virtual void SetUpSingle();
  virtual void RunSingle() = 0;
  virtual void TearDownSingle();

  std::string GetAbsolutePath();

  SyscallInterface& syscall_;
  static int absolute_path_key_;
  std::string name_;
  int repeat_;
  TimeSampler time_sampler_;
};

} // namespace dittosuite
