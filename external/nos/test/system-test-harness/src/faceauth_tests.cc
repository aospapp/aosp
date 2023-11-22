
#include <app_nugget.h>
#include <nos/NuggetClientInterface.h>
#include <gtest/gtest.h>

#include <memory>

#include "user/faceauth/include/fa_common.h"

#include "nugget_tools.h"
#include "util.h"

using std::string;
using std::vector;
using std::unique_ptr;

namespace {

class FaceAuthTest: public testing::Test {
 protected:
  void SetUp() override;

  static void SetUpTestCase();
  static void TearDownTestCase();

  static unique_ptr<nos::NuggetClientInterface> client;
  static unique_ptr<test_harness::TestHarness> uart_printer;

  static const fa_result_t RunTask(const fa_task_t task,
                                   const fa_embedding_t* embed = NULL,
                                   const fa_token_t* token = NULL);
  static void Run(const fa_result_t expected, const fa_task_t task,
                  const fa_embedding_t* embed = NULL,
                  const fa_token_t* token = NULL);

  static void LockProfileTest(uint32_t profile1);
  static void UnlockProfileTest(uint32_t profile1);
  static bool IsProfileLocked(uint32_t profile1);
  static void UnockProfileTest(uint32_t profile1);
  static void FullMatchMismatchTest(uint32_t profile1, uint32_t profile2,
                                    uint32_t slot1, uint32_t slot2);
  static fa_token_t MakeToken(uint32_t profile_id);

  static vector<uint64_t> user_ids;
};

vector<uint64_t> FaceAuthTest::user_ids;

unique_ptr<nos::NuggetClientInterface> FaceAuthTest::client;
unique_ptr<test_harness::TestHarness> FaceAuthTest::uart_printer;

void FaceAuthTest::SetUpTestCase() {
  srand(time(NULL));
  for (int i = 0; i < MAX_NUM_PROFILES; ++i) {
    user_ids.push_back(rand());
  }
  uart_printer = test_harness::TestHarness::MakeUnique();

  client = nugget_tools::MakeNuggetClient();
  client->Open();
  EXPECT_TRUE(client->IsOpen()) << "Unable to connect";
}

void FaceAuthTest::TearDownTestCase() {
  client->Close();
  client = unique_ptr<nos::NuggetClientInterface>();

  uart_printer = nullptr;
}

uint8_t CalcCrc8(const uint8_t *data, int len)
{
  unsigned crc = 0;
  int i, j;

  for (j = len; j; j--, data++) {
    crc ^= (*data << 8);
    for (i = 8; i; i--) {
      if (crc & 0x8000) {
        crc ^= (0x1070 << 3);
      }
      crc <<= 1;
    }
  }

  return (uint8_t)(crc >> 8);
}

static fa_task_t MakeTask(uint64_t session_id, uint32_t profile_id,
                          uint32_t cmd, uint32_t input_data1 = 0,
                          uint32_t input_data2 = 0,
                          uint32_t version = FACEAUTH_MIN_ABH_VERSION) {
  fa_task_t task;
  task.version = version;
  task.session_id = session_id;
  task.profile_id = profile_id;
  task.cmd = cmd;
  task.input.data.first = input_data1;
  task.input.data.second = input_data2;
  task.crc = CalcCrc8(reinterpret_cast<const uint8_t*>(&task),
                      offsetof(struct fa_task_t, crc));
  return task;
}

static fa_embedding_t* MakeEmbedding(uint32_t base, uint32_t version = 1) {
  static fa_embedding_t embed;
  memset(&embed, base, sizeof(fa_embedding_t));
  embed.version = version;
  embed.valid = 0;
  embed.crc = CalcCrc8(reinterpret_cast<const uint8_t*>(&embed),
                       offsetof(struct fa_embedding_t, crc));
  return &embed;
}

static fa_result_t MakeResult(uint64_t session_id, int32_t error,
                              uint32_t output_data1 = 0,
                              uint32_t output_data2 = 0,
                              uint32_t lockout_event = FACEAUTH_LOCKOUT_NOP) {
  fa_result_t result;
  memset(&result, 0, sizeof(fa_result_t));
  result.version = 1;
  result.session_id = session_id;
  result.error = error;
  result.output.data.first = output_data1;
  result.output.data.second = output_data2;
  result.lockout_event = lockout_event;
  result.complete = 1;
  result.crc = CalcCrc8(reinterpret_cast<const uint8_t*>(&result),
                        offsetof(struct fa_result_t, crc));
  return result;
}

fa_token_t FaceAuthTest::MakeToken(uint32_t profile_id) {
  fa_token_t token;
  token.user_id = user_ids[profile_id];
  return token;
}

vector<uint8_t> Task2Buffer(const fa_task_t task, const fa_embedding_t* embed,
                            const fa_token_t* token) {
  vector<uint8_t> buffer;
  for (size_t i = 0; i < sizeof(fa_task_t); ++i) {
    buffer.push_back(*(reinterpret_cast<const uint8_t*>(&task) + i));
  }
  for (size_t i = 0; i < sizeof(fa_embedding_t); ++i) {
    if (embed)
      buffer.push_back(*(reinterpret_cast<const uint8_t*>(embed) + i));
    else
      buffer.push_back(0);
  }
  for (size_t i = 0; i < sizeof(fa_token_t); ++i) {
    if (token)
      buffer.push_back(*(reinterpret_cast<const uint8_t*>(token) + i));
    else
      buffer.push_back(0);
  }

  return buffer;
}

static const fa_result_t Buffer2Result(const vector<uint8_t>& buffer)
{
  const fa_result_t result = *(reinterpret_cast<const fa_result_t*>(
                               buffer.data()));
  return result;
}

static void EXPECT_RESULT_EQ(const fa_result_t& r1, const fa_result_t& r2)
{
  EXPECT_EQ(r1.version, r2.version);
  EXPECT_EQ(r1.session_id, r2.session_id);
  EXPECT_EQ(r1.error, r2.error);
  EXPECT_EQ(r1.output.data.first, r2.output.data.first);
  EXPECT_EQ(r1.output.data.second, r2.output.data.second);
  EXPECT_EQ(r1.lockout_event, r2.lockout_event);
  EXPECT_EQ(r1.complete, r2.complete);
  EXPECT_EQ(r1.crc, r2.crc);
}

const fa_result_t FaceAuthTest::RunTask(const fa_task_t task,
                                        const fa_embedding_t* embed,
                                        const fa_token_t* token) {
  vector<uint8_t> buffer_rx;
  buffer_rx.resize(1024);

  vector<uint8_t> buffer_tx = Task2Buffer(task, embed, token);
  FaceAuthTest::client->CallApp(APP_ID_FACEAUTH_TEST, 1, buffer_tx, &buffer_rx);

  return Buffer2Result(buffer_rx);
}

void FaceAuthTest::Run(const fa_result_t expected, const fa_task_t task,
                       const fa_embedding_t* embed, const fa_token_t* token) {
  EXPECT_RESULT_EQ(expected, RunTask(task, embed, token));
}

void FaceAuthTest::SetUp() {
  for (int profiles = 1; profiles <= MAX_NUM_PROFILES; ++profiles) {
    Run(MakeResult(0x0, FACEAUTH_SUCCESS),
        MakeTask(0x0, profiles, FACEAUTH_CMD_ERASE));
  }
}

TEST_F(FaceAuthTest, SimpleMatchMismatchTest) {
  uint64_t session_id = 0xFACE000011110000ull;
  session_id++;

  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_NOMATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP), MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, 0x1),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_ENROLL), MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_MATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP), MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_ERASE));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_NOMATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP), MakeEmbedding(0x11));
}

void FaceAuthTest::FullMatchMismatchTest(uint32_t profile1, uint32_t profile2,
                                         uint32_t slot1, uint32_t slot2) {
  uint64_t session_id = 0xFACE000022220000ull;
  for (uint32_t i = 0; i < 20; ++i) {
    session_id++;
    Run(MakeResult(session_id, FACEAUTH_SUCCESS, profile1),
        MakeTask(session_id, profile1, FACEAUTH_CMD_ENROLL),
        MakeEmbedding((i == slot1) ? 0x11 : 0x0));

    session_id++;
    Run(MakeResult(session_id, FACEAUTH_SUCCESS, profile2),
        MakeTask(session_id, profile2, FACEAUTH_CMD_ENROLL),
        MakeEmbedding((i == slot2) ? 0xAA : 0x0));
  }

  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_MATCH),
      MakeTask(session_id, profile1, FACEAUTH_CMD_COMP), MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_NOMATCH),
      MakeTask(session_id, profile1, FACEAUTH_CMD_COMP), MakeEmbedding(0xAA));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_NOMATCH),
      MakeTask(session_id, profile2, FACEAUTH_CMD_COMP), MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_MATCH),
      MakeTask(session_id, profile2, FACEAUTH_CMD_COMP), MakeEmbedding(0xAA));
}

TEST_F(FaceAuthTest, SFSFullTest) {
  uint64_t session_id = 0xFACE000033330000ull;
  for (int i = 0; i < 20; ++i) {
    session_id++;
    Run(MakeResult(session_id, FACEAUTH_SUCCESS, 0x1),
        MakeTask(session_id, 0x1, FACEAUTH_CMD_ENROLL), MakeEmbedding(0x0));
  }

  session_id++;
  Run(MakeResult(session_id, FACEAUTH_ERR_SFS_FULL, 0x1),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_ENROLL), MakeEmbedding(0x0));
}

void FaceAuthTest::LockProfileTest(uint32_t profile1) {
  uint64_t session_id = 0xFACE000044440000ull;

  for (int i = 0; i < 4; ++i) {
    session_id++;
    Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_NOMATCH),
        MakeTask(session_id, profile1, FACEAUTH_CMD_COMP), MakeEmbedding(0x0));
  }

  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_NOMATCH, 0,
                 FACEAUTH_LOCKOUT_ENFORCED),
      MakeTask(session_id, profile1, FACEAUTH_CMD_COMP), MakeEmbedding(0x0));

  session_id++;
  Run(MakeResult(session_id, FACEAUTH_ERR_THROTTLE, FACEAUTH_NOMATCH),
      MakeTask(session_id, profile1, FACEAUTH_CMD_COMP), MakeEmbedding(0x0));
}

bool FaceAuthTest::IsProfileLocked(uint32_t profile1) {
  uint64_t session_id = 0xFACE000066660000ull;

  const fa_result_t observed =
      RunTask(MakeTask(session_id, profile1, FACEAUTH_CMD_GET_USER_INFO));
  const fa_result_t expected =
      MakeResult(session_id, FACEAUTH_SUCCESS, 0, observed.output.data.second);
  EXPECT_RESULT_EQ(expected, observed);
  return observed.output.data.second;
}

void FaceAuthTest::UnlockProfileTest(uint32_t profile1) {
  uint64_t session_id = 0xFACE000077770000ull;
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, 0, 0, FACEAUTH_LOCKOUT_REMOVED),
      MakeTask(session_id, profile1, FACEAUTH_CMD_RESET_LOCKOUT));
}

TEST_F(FaceAuthTest, ExhaustiveLockoutTest) {
  EXPECT_EQ(IsProfileLocked(1), false);
  EXPECT_EQ(IsProfileLocked(4), false);
  EXPECT_EQ(IsProfileLocked(5), false);
  EXPECT_EQ(IsProfileLocked(6), false);

  LockProfileTest(1);
  LockProfileTest(5);
  LockProfileTest(6);

  EXPECT_EQ(IsProfileLocked(1), true);
  EXPECT_EQ(IsProfileLocked(4), false);
  EXPECT_EQ(IsProfileLocked(5), true);
  EXPECT_EQ(IsProfileLocked(6), true);

  UnlockProfileTest(1);
  UnlockProfileTest(6);

  EXPECT_EQ(IsProfileLocked(1), false);
  EXPECT_EQ(IsProfileLocked(4), false);
  EXPECT_EQ(IsProfileLocked(5), true);
  EXPECT_EQ(IsProfileLocked(6), false);
}

TEST_F(FaceAuthTest, ValidProfileUserIDTest) {
  fa_token_t token;
  uint64_t session_id = 0xFACE000088880000ull;
  session_id++;
  token = MakeToken(1);
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, 1),
      MakeTask(session_id, 0, FACEAUTH_CMD_ENROLL), MakeEmbedding(0x0), &token);

  for (int i = 1; i <= 6; ++i) {
    session_id++;
    token = MakeToken(i);
    Run(MakeResult(session_id, FACEAUTH_SUCCESS, i),
        MakeTask(session_id, (i % 2) ? i : 0, FACEAUTH_CMD_ENROLL),
        MakeEmbedding(0x0), &token);
  }

  session_id++;
  token = MakeToken(2);
  Run(MakeResult(session_id, FACEAUTH_ERR_INVALID_TOKEN),
      MakeTask(session_id, 3, FACEAUTH_CMD_ENROLL), MakeEmbedding(0x0), &token);
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_ERR_SFS_FULL),
      MakeTask(session_id, 0, FACEAUTH_CMD_ENROLL));
}

TEST_F(FaceAuthTest, InvalidCommandTest) {
  uint64_t session_id = 0xFACE000099990000ull;
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_ERR_INVALID_ARGS),
      MakeTask(session_id, 0x1, 0x0));
}

TEST_F(FaceAuthTest, SimpleFeatureTest) {
  uint64_t session_id = 0xFACE0000AAAA0000ull;
  uint32_t index = 0;
  uint32_t feature_msk[MAX_NUM_PROFILES] = {0};

  for (int k = 0; k < 5; ++k) {
    for (int i = 1; i <= MAX_NUM_PROFILES; ++i) {
      session_id++;
      Run(MakeResult(session_id, FACEAUTH_SUCCESS, feature_msk[i - 1]),
          MakeTask(session_id, i, FACEAUTH_CMD_GET_USER_INFO));
    }

    for (int i = 1; i <= MAX_NUM_PROFILES; ++i) {
      session_id++;
      Run(MakeResult(session_id, FACEAUTH_SUCCESS),
          MakeTask(session_id, i, FACEAUTH_CMD_SET_FEATURE, (1 << index)));
      feature_msk[i - 1] |= (1 << index);
      index++;
    }
  }

  index = 0;

  for (int k = 0; k < 5; ++k) {
    for (int i = 1; i <= MAX_NUM_PROFILES; ++i) {
      session_id++;
      Run(MakeResult(session_id, FACEAUTH_SUCCESS, feature_msk[i - 1]),
          MakeTask(session_id, i, FACEAUTH_CMD_GET_USER_INFO));
    }

    for (int i = 1; i <= MAX_NUM_PROFILES; ++i) {
      session_id++;
      Run(MakeResult(session_id, FACEAUTH_SUCCESS),
          MakeTask(session_id, i, FACEAUTH_CMD_CLR_FEATURE, (1 << index)));
      feature_msk[i - 1] &= ~(1 << index);
      index++;
    }
  }
}

TEST_F(FaceAuthTest, EmbeddingVersionTest) {
  uint64_t session_id = 0xFACE0000BBBB0000ull;
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, 1),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_ENROLL), MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_MATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP), MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_ERR_RECALIBRATE, FACEAUTH_NOMATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP), MakeEmbedding(0x11, 0x2));
}

TEST_F(FaceAuthTest, FirmwareVersionTest) {
  uint64_t session_id = 0xFACE0000CCCC0000ull;
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, 1),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_ENROLL), MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_ERR_VERSION, FACEAUTH_NOMATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP, 0, 0, 0x1),
      MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_ERR_VERSION, FACEAUTH_NOMATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP, 0, 0,
               FACEAUTH_MIN_ABH_VERSION - 0x100),
      MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_MATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP, 0, 0,
               FACEAUTH_MIN_ABH_VERSION),
      MakeEmbedding(0x11));
  session_id++;
  Run(MakeResult(session_id, FACEAUTH_SUCCESS, FACEAUTH_MATCH),
      MakeTask(session_id, 0x1, FACEAUTH_CMD_COMP, 0, 0,
               FACEAUTH_MIN_ABH_VERSION + 0x100),
      MakeEmbedding(0x11));
}

TEST_F(FaceAuthTest, ExhaustiveMatchMismatchTest) {
  FullMatchMismatchTest(1, 6, 0, 19);
  FullMatchMismatchTest(2, 5, 1, 18);
  FullMatchMismatchTest(3, 4, 2, 17);
  SetUp();
  FullMatchMismatchTest(2, 4, 3, 16);
  FullMatchMismatchTest(1, 5, 4, 15);
  FullMatchMismatchTest(3, 6, 5, 14);
  SetUp();
  FullMatchMismatchTest(3, 5, 6, 13);
  FullMatchMismatchTest(1, 4, 7, 12);
  FullMatchMismatchTest(2, 6, 8, 11);
  SetUp();
  FullMatchMismatchTest(3, 6, 9, 10);
}
}

