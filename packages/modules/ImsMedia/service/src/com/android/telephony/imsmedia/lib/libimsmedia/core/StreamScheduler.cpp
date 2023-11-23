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

#include <StreamScheduler.h>
#include <ImsMediaTrace.h>
#include <stdint.h>
#include <chrono>
#include <thread>
#include <algorithm>

using namespace std::chrono;

#define RUN_WAIT_TIMEOUT_MS  1
#define STOP_WAIT_TIMEOUT_MS 1000

StreamScheduler::StreamScheduler() {}

StreamScheduler::~StreamScheduler()
{
    Stop();
}

void StreamScheduler::RegisterNode(BaseNode* pNode)
{
    if (pNode == nullptr)
    {
        return;
    }

    IMLOGD2("[RegisterNode] [%p], node[%s]", this, pNode->GetNodeName());
    std::lock_guard<std::mutex> guard(mMutex);
    mlistRegisteredNode.push_back(pNode);
}

void StreamScheduler::DeRegisterNode(BaseNode* pNode)
{
    if (pNode == nullptr)
    {
        return;
    }

    IMLOGD2("[DeRegisterNode] [%p], node[%s]", this, pNode->GetNodeName());
    std::lock_guard<std::mutex> guard(mMutex);
    mlistRegisteredNode.remove(pNode);
}

void StreamScheduler::Start()
{
    IMLOGD1("[Start] [%p] enter", this);

    for (auto& node : mlistRegisteredNode)
    {
        if (node != nullptr)
        {
            IMLOGD2("[Start] [%p] registered node [%s]", this, node->GetNodeName());
        }
    }

    if (!mlistRegisteredNode.empty())
    {
        IMLOGD1("[Start] [%p] Start thread", this);
        StartThread();
    }

    IMLOGD1("[Start] [%p] exit", this);
}

void StreamScheduler::Stop()
{
    IMLOGD1("[Stop] [%p] enter", this);

    if (!IsThreadStopped())
    {
        StopThread();
        Awake();
        mConditionExit.wait_timeout(STOP_WAIT_TIMEOUT_MS);
    }

    IMLOGD1("[Stop] [%p] exit", this);
}

void StreamScheduler::Awake()
{
    mConditionMain.signal();
}

void StreamScheduler::RunRegisteredNode()
{
    // the list to contain non-source type node
    std::list<BaseNode*> listNodesToRun;

    for (auto& node : mlistRegisteredNode)
    {
        if (node != nullptr && node->GetState() == kNodeStateRunning && !node->IsRunTime())
        {
            if (node->IsSourceNode())  // process the source node
            {
                node->ProcessData();
            }
            else if (node->GetDataCount() > 0)
            {
                listNodesToRun.push_back(node);  // store node to run
            }
        }
    }

    while (!listNodesToRun.empty())
    {
        std::list<BaseNode*>::iterator maxNode =
                std::max_element(listNodesToRun.begin(), listNodesToRun.end(),
                        [=](BaseNode* a, BaseNode* b)
                        {
                            return a->GetDataCount() < b->GetDataCount();
                        });

        if (maxNode == listNodesToRun.end())
        {
            break;
        }

        (*maxNode)->ProcessData();  // process the non runtime node

        if (IsThreadStopped())
        {
            break;
        }

        listNodesToRun.remove(*maxNode);
    };
}

void* StreamScheduler::run()
{
    IMLOGD1("[run] [%p] enter", this);

    // start nodes
    mMutex.lock();

    for (auto& node : mlistRegisteredNode)
    {
        if (node != nullptr && !node->IsRunTimeStart())
        {
            if (node->GetState() == kNodeStateStopped && node->ProcessStart() != RESULT_SUCCESS)
            {
                // TODO: report error
                IMLOGE0("[run] error");
            }
        }
    }

    mMutex.unlock();

    while (!IsThreadStopped())
    {
        mMutex.lock();
        RunRegisteredNode();
        mMutex.unlock();

        if (IsThreadStopped())
        {
            break;
        }

        mConditionMain.wait_timeout(RUN_WAIT_TIMEOUT_MS);
    }

    mConditionExit.signal();
    IMLOGD1("[run] [%p] exit", this);
    return nullptr;
}