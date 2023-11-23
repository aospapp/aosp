/*
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

#ifndef BERBERIS_BASE_MMAP_POOL_H_
#define BERBERIS_BASE_MMAP_POOL_H_

#include <atomic>
#include <cstddef>

#include "berberis/base/lock_free_stack.h"
#include "berberis/base/logging.h"
#include "berberis/base/mmap.h"

namespace berberis {

// Pool of memory mappings to be used by berberis runtime.
// Thread-safe, signal-safe, reentrant.
// Singleton interface (no non-static members).
template <size_t kBlockSize, size_t kSizeLimit>
class MmapPool {
  static_assert(kBlockSize % kPageSize == 0);
  static_assert(kBlockSize <= kSizeLimit);

 public:
  static void* Alloc() {
    Node* node = g_free_list_.Pop();
    if (node) {
      ReleaseFreeListBlock();
      return node;
    }
    return MmapOrDie(kBlockSize);
  }

  static void Free(void* node) {
    if (AcquireFreeListBlock()) {
      g_free_list_.Push(static_cast<Node*>(node));
      return;
    }
    return MunmapOrDie(node, kBlockSize);
  }

 private:
  // When a block is freed we reinterpret it as Node, so that
  // it can be linked to LockFreeStack.
  struct Node {
    Node* next;
  };

  static void ReleaseFreeListBlock() {
    // Memory order release to ensure list pop isn't observed by another
    // thread after size decrement.
    g_size_.fetch_sub(kBlockSize, std::memory_order_release);
    // There must be no more releases than acquires.
    // On underflow g_size may become close to size_t max.
    CHECK_LE(g_size_.load(std::memory_order_relaxed), kSizeLimit);
  }

  static bool AcquireFreeListBlock() {
    // We need acquire semantics so that list push isn't observed by another
    // thread before the size reservation.
    size_t cmp = g_size_.load(std::memory_order_acquire);
    while (true) {
      size_t xch = cmp + kBlockSize;
      // This guarantees that g_size_ is never set to a value above kSizeLimit.
      if (xch > kSizeLimit) {
        return false;
      }
      // Updates cmp!
      if (g_size_.compare_exchange_weak(cmp, xch, std::memory_order_acquire)) {
        return true;
      }
    }
  }

  static LockFreeStack<Node> g_free_list_;
  // Note, the size is not strictly synchronized with g_free_list_ updates,
  // but we err on the side of a greater size everywhere to make sure kSizeLimit
  // isn't overflown. We increase the size *before* pushing a node to list, and
  // decrease size *after* removing a node.
  static std::atomic_size_t g_size_;

  MmapPool() = delete;

  friend class MmapPoolTest;
};

template <size_t kBlockSize, size_t kSizeLimit>
std::atomic_size_t MmapPool<kBlockSize, kSizeLimit>::g_size_{0};

template <size_t kBlockSize, size_t kSizeLimit>
LockFreeStack<typename MmapPool<kBlockSize, kSizeLimit>::Node>
    MmapPool<kBlockSize, kSizeLimit>::g_free_list_;

}  // namespace berberis

#endif  // BERBERIS_BASE_MMAP_POOL_H_
