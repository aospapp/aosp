/**
 * Copyright (C) 2022 The Android Open Source Project
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

#include <IImsMediaThread.h>
#include <ImsMediaTrace.h>
#include <thread>

IImsMediaThread::IImsMediaThread()
{
    mThreadStopped = true;
}

IImsMediaThread::~IImsMediaThread() {}

void* runThread(void* arg)
{
    if (arg == nullptr)
    {
        IMLOGE0("[runThread] invalid argument");
        return nullptr;
    }

    IImsMediaThread* thread = reinterpret_cast<IImsMediaThread*>(arg);
    return thread->runBase();
}

bool IImsMediaThread::StartThread()
{
    std::lock_guard<std::mutex> guard(mThreadMutex);
    mThreadStopped = false;

    std::thread t1(&runThread, this);
    t1.detach();
    return true;
}

void IImsMediaThread::StopThread()
{
    std::lock_guard<std::mutex> guard(mThreadMutex);
    mThreadStopped = true;
}

bool IImsMediaThread::IsThreadStopped()
{
    std::lock_guard<std::mutex> guard(mThreadMutex);
    return mThreadStopped;
}

void* IImsMediaThread::runBase()
{
    return run();
}