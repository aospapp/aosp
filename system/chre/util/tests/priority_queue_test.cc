/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "chre/util/priority_queue.h"
#include "gtest/gtest.h"

using chre::PriorityQueue;

namespace {
class FakeElement {
 public:
  FakeElement(){};
  FakeElement(int index, int value) {
    mValue = value;
    mIndex = index;
  };
  ~FakeElement(){};
  void setValue(int value) {
    mValue = value;
  }
  int getValue() const {
    return mValue;
  }
  int getIndex() const {
    return mIndex;
  }

 private:
  int mIndex = -1;
  int mValue = -1;
};

bool compareFunction(const FakeElement &left, const FakeElement &right) {
  return left.getValue() > right.getValue();
};

class CompareClass {
 public:
  bool operator()(const FakeElement &left, const FakeElement &right) const {
    return left.getValue() > right.getValue();
  }
};
}  // namespace

TEST(PriorityQueueTest, IsEmptyInitially) {
  PriorityQueue<int> q;
  EXPECT_TRUE(q.empty());
  EXPECT_EQ(0, q.size());
  EXPECT_EQ(0, q.capacity());
}

TEST(PriorityQueueTest, SimplePushPop) {
  PriorityQueue<int> q;

  EXPECT_TRUE(q.push(0));
  EXPECT_TRUE(q.push(2));
  EXPECT_TRUE(q.push(3));
  EXPECT_TRUE(q.push(1));
  q.pop();
  EXPECT_TRUE(q.push(4));
}

TEST(PriorityQueueTest, TestSize) {
  PriorityQueue<int> q;

  q.push(1);
  EXPECT_EQ(1, q.size());
  q.push(2);
  EXPECT_EQ(2, q.size());
  q.pop();
  EXPECT_EQ(1, q.size());
}

TEST(PriorityQueueTest, TestEmpty) {
  PriorityQueue<int> q;

  q.push(1);
  EXPECT_FALSE(q.empty());
  q.push(2);
  EXPECT_FALSE(q.empty());
  q.pop();
  EXPECT_FALSE(q.empty());
  q.pop();
  EXPECT_TRUE(q.empty());
}

TEST(PriorityQueueTest, TestCapacity) {
  PriorityQueue<int> q;

  q.push(1);
  EXPECT_EQ(1, q.capacity());
  q.push(2);
  EXPECT_EQ(2, q.capacity());
  q.push(3);
  EXPECT_EQ(4, q.capacity());
}

TEST(PriorityQueueTest, PopWhenEmpty) {
  PriorityQueue<int> q;
  q.pop();
  EXPECT_EQ(0, q.size());
}

TEST(PriorityQueueDeathTest, TopWhenEmpty) {
  PriorityQueue<int> q;
  EXPECT_DEATH(q.top(), "");
}

TEST(PriorityQueueTest, TestTop) {
  PriorityQueue<int> q;
  q.push(1);
  EXPECT_EQ(1, q.top());
  q.push(2);
  q.push(3);
  EXPECT_EQ(3, q.top());
  q.pop();
  EXPECT_EQ(2, q.top());
  q.pop();
  EXPECT_EQ(1, q.top());
}

TEST(PriorityQueueDeathTest, InvalidSubscript) {
  PriorityQueue<int> q;
  EXPECT_DEATH(q[0], "");
}

TEST(PriorityQueueTest, Subscript) {
  PriorityQueue<int> q;
  q.push(1);
  q.push(2);
  EXPECT_EQ(2, q[0]);
  EXPECT_EQ(1, q[1]);

  q.pop();
  EXPECT_EQ(1, q[0]);
}

TEST(PriorityQueueDeathTest, RemoveWithInvalidIndex) {
  PriorityQueue<int> q;
  EXPECT_DEATH(q.remove(0), "");
  EXPECT_EQ(0, q.size());
}

TEST(PriorityQueueTest, RemoveWithIndex) {
  PriorityQueue<int> q;
  q.push(1);
  q.push(2);
  q.remove(0);
  EXPECT_EQ(1, q.top());
  EXPECT_EQ(1, q.size());

  q.push(3);
  q.remove(1);
  EXPECT_EQ(3, q.top());
  EXPECT_EQ(1, q.size());
}

TEST(PriorityQueueTest, CompareGreater) {
  PriorityQueue<int, std::greater<int>> q;

  EXPECT_TRUE(q.push(0));
  EXPECT_TRUE(q.push(2));
  EXPECT_TRUE(q.push(3));
  EXPECT_TRUE(q.push(1));

  for (size_t i = 0; i < 4; i++) {
    EXPECT_EQ(i, q.top());
    q.pop();
  }
}

TEST(PriorityQueueTest, EmplaceCompareLambda) {
  auto cmp = [](const FakeElement &left, const FakeElement &right) {
    return left.getValue() > right.getValue();
  };
  PriorityQueue<FakeElement, decltype(cmp)> q(cmp);

  EXPECT_TRUE(q.emplace(0, 0));
  EXPECT_TRUE(q.emplace(1, 2));
  EXPECT_TRUE(q.emplace(2, 1));
  EXPECT_EQ(3, q.size());

  EXPECT_EQ(0, q.top().getValue());
  EXPECT_EQ(0, q.top().getIndex());

  q.pop();
  EXPECT_EQ(1, q.top().getValue());
  EXPECT_EQ(2, q.top().getIndex());

  q.pop();
  EXPECT_EQ(2, q.top().getValue());
  EXPECT_EQ(1, q.top().getIndex());
}

TEST(PriorityQueueTest, EmplaceCompareFunction) {
  PriorityQueue<FakeElement,
                std::function<bool(const FakeElement &, const FakeElement &)>>
      q(compareFunction);

  EXPECT_TRUE(q.emplace(0, 0));
  EXPECT_TRUE(q.emplace(1, 2));
  EXPECT_TRUE(q.emplace(2, 1));
  EXPECT_EQ(3, q.size());

  EXPECT_EQ(0, q.top().getValue());
  EXPECT_EQ(0, q.top().getIndex());

  q.pop();
  EXPECT_EQ(1, q.top().getValue());
  EXPECT_EQ(2, q.top().getIndex());

  q.pop();
  EXPECT_EQ(2, q.top().getValue());
  EXPECT_EQ(1, q.top().getIndex());
}

TEST(PriorityQueueTest, EmplaceCompareClass) {
  PriorityQueue<FakeElement, CompareClass> q;

  EXPECT_TRUE(q.emplace(0, 0));
  EXPECT_TRUE(q.emplace(1, 2));
  EXPECT_TRUE(q.emplace(2, 1));
  EXPECT_EQ(3, q.size());

  EXPECT_EQ(0, q.top().getValue());
  EXPECT_EQ(0, q.top().getIndex());

  q.pop();
  EXPECT_EQ(1, q.top().getValue());
  EXPECT_EQ(2, q.top().getIndex());

  q.pop();
  EXPECT_EQ(2, q.top().getValue());
  EXPECT_EQ(1, q.top().getIndex());
}

TEST(PriorityQueuetest, Iterator) {
  PriorityQueue<int> q;
  q.push(0);
  q.push(1);
  q.push(2);

  PriorityQueue<int>::iterator it = q.begin();
  EXPECT_EQ(q[0], *it);

  it += q.size();
  EXPECT_TRUE(it == q.end());
}

TEST(PriorityQueuetest, ConstIterator) {
  PriorityQueue<int> q;
  q.push(0);
  q.push(1);
  q.push(2);

  PriorityQueue<int>::const_iterator cit = q.cbegin();
  EXPECT_EQ(q[0], *cit);

  cit += q.size();
  EXPECT_TRUE(cit == q.cend());
}
