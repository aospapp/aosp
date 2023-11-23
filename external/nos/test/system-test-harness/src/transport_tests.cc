#include <memory>

#include "gmock/gmock.h"
#include "nos/device.h"
#include "nos/NuggetClient.h"
#include "application.h"
#include "app_transport_test.h"
#include "util.h"

using std::cout;
using std::string;
using std::unique_ptr;

/*
 * These test use the datagram protocol diretly to test that Citadel's transport
 * implementation works as expected.
 */
namespace {

static const uint16_t crc16_table[256] = {
  0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241,
  0xC601, 0x06C0, 0x0780, 0xC741, 0x0500, 0xC5C1, 0xC481, 0x0440,
  0xCC01, 0x0CC0, 0x0D80, 0xCD41, 0x0F00, 0xCFC1, 0xCE81, 0x0E40,
  0x0A00, 0xCAC1, 0xCB81, 0x0B40, 0xC901, 0x09C0, 0x0880, 0xC841,
  0xD801, 0x18C0, 0x1980, 0xD941, 0x1B00, 0xDBC1, 0xDA81, 0x1A40,
  0x1E00, 0xDEC1, 0xDF81, 0x1F40, 0xDD01, 0x1DC0, 0x1C80, 0xDC41,
  0x1400, 0xD4C1, 0xD581, 0x1540, 0xD701, 0x17C0, 0x1680, 0xD641,
  0xD201, 0x12C0, 0x1380, 0xD341, 0x1100, 0xD1C1, 0xD081, 0x1040,
  0xF001, 0x30C0, 0x3180, 0xF141, 0x3300, 0xF3C1, 0xF281, 0x3240,
  0x3600, 0xF6C1, 0xF781, 0x3740, 0xF501, 0x35C0, 0x3480, 0xF441,
  0x3C00, 0xFCC1, 0xFD81, 0x3D40, 0xFF01, 0x3FC0, 0x3E80, 0xFE41,
  0xFA01, 0x3AC0, 0x3B80, 0xFB41, 0x3900, 0xF9C1, 0xF881, 0x3840,
  0x2800, 0xE8C1, 0xE981, 0x2940, 0xEB01, 0x2BC0, 0x2A80, 0xEA41,
  0xEE01, 0x2EC0, 0x2F80, 0xEF41, 0x2D00, 0xEDC1, 0xEC81, 0x2C40,
  0xE401, 0x24C0, 0x2580, 0xE541, 0x2700, 0xE7C1, 0xE681, 0x2640,
  0x2200, 0xE2C1, 0xE381, 0x2340, 0xE101, 0x21C0, 0x2080, 0xE041,
  0xA001, 0x60C0, 0x6180, 0xA141, 0x6300, 0xA3C1, 0xA281, 0x6240,
  0x6600, 0xA6C1, 0xA781, 0x6740, 0xA501, 0x65C0, 0x6480, 0xA441,
  0x6C00, 0xACC1, 0xAD81, 0x6D40, 0xAF01, 0x6FC0, 0x6E80, 0xAE41,
  0xAA01, 0x6AC0, 0x6B80, 0xAB41, 0x6900, 0xA9C1, 0xA881, 0x6840,
  0x7800, 0xB8C1, 0xB981, 0x7940, 0xBB01, 0x7BC0, 0x7A80, 0xBA41,
  0xBE01, 0x7EC0, 0x7F80, 0xBF41, 0x7D00, 0xBDC1, 0xBC81, 0x7C40,
  0xB401, 0x74C0, 0x7580, 0xB541, 0x7700, 0xB7C1, 0xB681, 0x7640,
  0x7200, 0xB2C1, 0xB381, 0x7340, 0xB101, 0x71C0, 0x7080, 0xB041,
  0x5000, 0x90C1, 0x9181, 0x5140, 0x9301, 0x53C0, 0x5280, 0x9241,
  0x9601, 0x56C0, 0x5780, 0x9741, 0x5500, 0x95C1, 0x9481, 0x5440,
  0x9C01, 0x5CC0, 0x5D80, 0x9D41, 0x5F00, 0x9FC1, 0x9E81, 0x5E40,
  0x5A00, 0x9AC1, 0x9B81, 0x5B40, 0x9901, 0x59C0, 0x5880, 0x9841,
  0x8801, 0x48C0, 0x4980, 0x8941, 0x4B00, 0x8BC1, 0x8A81, 0x4A40,
  0x4E00, 0x8EC1, 0x8F81, 0x4F40, 0x8D01, 0x4DC0, 0x4C80, 0x8C41,
  0x4400, 0x84C1, 0x8581, 0x4540, 0x8701, 0x47C0, 0x4680, 0x8641,
  0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040
};

uint16_t crc16_update(const void *buf, uint32_t len, uint16_t crc) {
  uint32_t i;
  const uint8_t *bytes = reinterpret_cast<const uint8_t *>(buf);
  for (i = 0; i < len; ++i) {
    crc = crc16_table[((crc >> 8) ^ *bytes++) & 0xFF] ^ (crc << 8);
  }
  return crc;
}

uint16_t crc16(const void *buf, uint32_t len) {
  return crc16_update(buf, len, 0);
}

#define RETRY_COUNT 30
#define RETRY_WAIT_TIME_US 5000

/*
 * Read a datagram from the device, correctly handling retries.
 */
static int nos_device_read(const struct nos_device *dev, uint32_t command,
                           void *buf, uint32_t len) {
  int retries = RETRY_COUNT;
  while (retries--) {
    int err = dev->ops.read(dev->ctx, command, reinterpret_cast<uint8_t *>(buf), len);

    if (err == -EAGAIN) {
      /* Linux driver returns EAGAIN error if Citadel chip is asleep.
       * Give to the chip a little bit of time to awake and retry reading
       * status again. */
      usleep(RETRY_WAIT_TIME_US);
      continue;
    }
    return -err;
  }

  return ETIMEDOUT;
}

/*
 * Write a datagram to the device, correctly handling retries.
 */
static int nos_device_write(const struct nos_device *dev, uint32_t command,
                            const void *buf, uint32_t len) {
  int retries = RETRY_COUNT;
  while (retries--) {
    int err = dev->ops.write(dev->ctx, command, reinterpret_cast<const uint8_t *>(buf), len);

    if (err == -EAGAIN) {
      /* Linux driver returns EAGAIN error if Citadel chip is asleep.
       * Give to the chip a little bit of time to awake and retry reading
       * status again. */
      usleep(RETRY_WAIT_TIME_US);
      continue;
    }
    return -err;
  }

  return ETIMEDOUT;
}

/*
 * The transport protocol has 4 stages:
 *   1. Resetting the slave
 *   2. Sending a command to the slave
 *   3. Polling until the slave is done
 *   4. Fetching the result from the slave
 *
 * There are CRCs used on the messages to ensure integrity. If the CRC fails,
 * the the data transfer is retried. This can happen for the status message,
 * arguments and command message or the reply data.
 */
class TransportTest: public testing::Test {
 protected:
  static nos_device* dev;
  static unique_ptr<nos::NuggetClient> client;
  static unique_ptr<test_harness::TestHarness> uart_printer;

  static void SetUpTestCase();
  static void TearDownTestCase();

  virtual void SetUp();
};

nos_device* TransportTest::dev;
unique_ptr<nos::NuggetClient> TransportTest::client;
unique_ptr<test_harness::TestHarness> TransportTest::uart_printer;

void TransportTest::SetUpTestCase() {
  uart_printer = test_harness::TestHarness::MakeUnique();

  client = nugget_tools::MakeDirectNuggetClient();
  client->Open();
  EXPECT_TRUE(client->IsOpen()) << "Unable to connect";
  dev = client->Device();
}

void TransportTest::TearDownTestCase() {
  dev = nullptr;
  client->Close();
  client.reset();

  uart_printer = nullptr;
}

void TransportTest::SetUp() {
  // Reset and give it a bit of time to settle
  ASSERT_TRUE(nugget_tools::RebootNuggetUnchecked(client.get()));
  // Give a bit of time for the reboot to take effect
  usleep(30000);
}

bool StatusMatches(const transport_status& arg, uint32_t status, uint16_t flags,
                   uint8_t* reply, uint16_t reply_len) {
  bool ok = true;

  // v0 fields
  ok &= arg.status == status;
  ok &= arg.reply_len == reply_len;

  // v1 fields
  ok &= arg.length == sizeof(transport_status);
  ok &= arg.version == TRANSPORT_V1;
  ok &= arg.flags == flags;

  // Check the status is a valid length
  if (arg.length < STATUS_MIN_LENGTH || arg.length > STATUS_MAX_LENGTH) {
    return false;
  }

  // As of v1, the length shouldn\t be greater than transport_status
  if (arg.length > sizeof(transport_status)) {
    return false;
  }

  // Check the CRCs are valid
  transport_status st = arg;
  st.crc = 0;
  ok &= arg.crc == crc16(&st, st.length);
  ok &= arg.reply_crc == crc16(reply, reply_len);

  return ok;
}

MATCHER(IsIdle, "") {
  return StatusMatches(arg,
                       APP_STATUS_IDLE, 0,
                       nullptr, 0);
}

MATCHER(IsWorking, "") {
  return StatusMatches(arg,
                       APP_STATUS_IDLE, STATUS_FLAG_WORKING,
                       nullptr, 0);
}

MATCHER(IsTooMuch, "") {
  return StatusMatches(arg,
                       APP_STATUS_DONE | APP_ERROR_TOO_MUCH, 0,
                       nullptr, 0);
}

MATCHER(IsBadCrc, "") {
  return StatusMatches(arg,
                       APP_STATUS_DONE | APP_ERROR_CHECKSUM, 0,
                       nullptr, 0);
}

MATCHER(IsSuccess, "") {
  return StatusMatches(arg,
                       APP_STATUS_DONE | APP_SUCCESS, 0,
                       nullptr, 0);
}

MATCHER_P2(IsSuccessWithData, data, len, "") {
  return StatusMatches(arg,
                       APP_STATUS_DONE | APP_SUCCESS, 0,
                       data, len);
}

// Give the app time to complete rather than polling
void WaitForApp() {
  usleep(30000);
}

// Calculate and set the CRC for the command from data or the struct
void SetCommandInfoCrc(const void* arg, uint16_t arg_len, uint32_t command,
                       void* command_info, uint16_t command_info_len) {
    uint16_t crc = crc16(&arg_len, sizeof(arg_len));
    crc = crc16_update(arg, arg_len, crc);
    crc = crc16_update(&command, sizeof(command), crc);
    uint16_t* const info_crc
        = (uint16_t*)&((uint8_t*)command_info)[offsetof(transport_command_info, crc)];
    *info_crc = 0;
    *info_crc = crc16_update(command_info, command_info_len, crc);
}
void SetCommandInfoCrc(const void* arg, uint16_t arg_len, uint32_t command,
                       transport_command_info* info) {
  SetCommandInfoCrc(arg, arg_len, command, info, sizeof(*info));
}

/* The initial state is to be idle. */
TEST_F(TransportTest, ResetToIdle) {
  transport_status status;
  const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
  ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
  ASSERT_THAT(status, IsIdle());
}

/* The master will be polling so need to confirm app is still working. */
TEST_F(TransportTest, CommandImmediatelyTriggersWorking) {
  { // Send "go" command
    transport_command_info command_info = {};
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(0);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsWorking());
  }
}

TEST_F(TransportTest, CommandBadCrc) {
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(0);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsBadCrc());
  }
}

TEST_F(TransportTest, TooMuchData) {
  { // Send data
    uint8_t data[32] = {};
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_DATA | CMD_TRANSPORT;
    CMD_SET_PARAM(command, sizeof(data));
    ASSERT_EQ(nos_device_write(dev, command, data, sizeof(data)), 0);
  }
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(0);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsTooMuch());
  }
}

/* Until the app says it is done, it is working. */
TEST_F(TransportTest, HangKeepsWorking) {
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_HANG);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsWorking());
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsWorking());
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsWorking());
  }
}

/* While the app is working, the master can only wait and can't modify memory. */
TEST_F(TransportTest, CannotClearStatusWhileWorking) {
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_HANG);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  { // Attempt to clear status
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_write(dev, command, nullptr, 0), 0);
  }
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsWorking());
  }
}

/* Protect from any race conditions that could arise. */
TEST_F(TransportTest, CannotStartNewCommandWhileWorking) {
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_HANG);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_1234);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsWorking());
  }
}

TEST_F(TransportTest, CommandSuccess) {
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_NOP);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsSuccess());
  }
}

TEST_F(TransportTest, CommandSuccessWithData) {
  uint8_t data[4];
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    command_info.reply_len_hint = sizeof(data);
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_1234);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_EQ(status.reply_len, sizeof(data));

    const uint32_t data_command = CMD_ID(APP_ID_TRANSPORT_TEST)
                                | CMD_IS_READ | CMD_IS_DATA | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, data_command, data, sizeof(data)), 0);
    ASSERT_THAT(status, IsSuccessWithData(data, sizeof(data)));
  }
}

/* The crc is only calculated over the data the master will read. */
TEST_F(TransportTest, CommandSuccessReplyLenHintRespected) {
  constexpr uint16_t reply_len_hint = 2; // This is less than the actual response
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    command_info.reply_len_hint = reply_len_hint;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_1234);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    uint8_t data[4];
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_EQ(status.reply_len, reply_len_hint);

    const uint32_t data_command = CMD_ID(APP_ID_TRANSPORT_TEST)
                                | CMD_IS_READ | CMD_IS_DATA | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, data_command, data, reply_len_hint), 0);
    // crc is only calculated over the data the master will read
    ASSERT_THAT(status, IsSuccessWithData(data, reply_len_hint));
  }
}

/* If there was a transmission error, need to be able to re-read data. */
TEST_F(TransportTest, CommandSuccessRetryReadingData) {
  uint8_t data[4];
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    command_info.reply_len_hint = sizeof(data);
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_1234);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_EQ(status.reply_len, sizeof(data));

    // This could happen if there was a crc error
    for (int i = 0; i < 3; ++i) {
      const uint32_t data_command = CMD_ID(APP_ID_TRANSPORT_TEST)
                                  | CMD_IS_READ | CMD_IS_DATA | CMD_TRANSPORT;
      ASSERT_EQ(nos_device_read(dev, data_command, data, sizeof(data)), 0);
      ASSERT_THAT(status, IsSuccessWithData(data, sizeof(data)));
    }
  }
}

TEST_F(TransportTest, ClearStatusAfterSuccess) {
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_NOP);
    SetCommandInfoCrc(nullptr, 0, command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Clear status
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_write(dev, command, nullptr, 0), 0);
  }
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsIdle());
  }
}

TEST_F(TransportTest, ClearStatusAfterError) {
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(0);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Clear status
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_write(dev, command, nullptr, 0), 0);
  }
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsIdle());
  }
}

TEST_F(TransportTest, SendArgumentData) {
  const uint32_t data = 0x09080706;
  { // Send data
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_DATA | CMD_TRANSPORT;
    CMD_SET_PARAM(command, sizeof(data));
    ASSERT_EQ(nos_device_write(dev, command, &data, sizeof(data)), 0);
  }
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_9876);
    SetCommandInfoCrc(&data, sizeof(data), command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsSuccess());
  }
}

/* Setting CMD_MORE_TO_COME appends new data. */
TEST_F(TransportTest, SendArgumentDataInMultipleDatagrams) {
  const uint32_t data = 0x09080706;
  { // Send data1
    const uint16_t data1 = 0x0706;
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_DATA | CMD_TRANSPORT;
    CMD_SET_PARAM(command, sizeof(data1));
    ASSERT_EQ(nos_device_write(dev, command, &data1, sizeof(data1)), 0);
  }
  { // Send data2
    const uint16_t data2 = 0x0908;
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST)
                     | CMD_IS_DATA | CMD_TRANSPORT | CMD_MORE_TO_COME;
    CMD_SET_PARAM(command, sizeof(data2));
    ASSERT_EQ(nos_device_write(dev, command, &data2, sizeof(data2)), 0);
  }
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_9876);
    SetCommandInfoCrc(&data, sizeof(data), command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsSuccess());
  }
}

/* Not setting the CMD_MORE_TO_COME flag overwrites rather than appends. */
TEST_F(TransportTest, SendWrongArgumentDataByRestarting) {
  const uint32_t data = 0x09080706;
  { // Send data1
    const uint16_t data1 = 0x0706;
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_DATA | CMD_TRANSPORT;
    CMD_SET_PARAM(command, sizeof(data1));
    ASSERT_EQ(nos_device_write(dev, command, &data1, sizeof(data1)), 0);
  }
  { // Send data2, restarting the args
    const uint16_t data2 = 0x0908;
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_DATA | CMD_TRANSPORT;
    CMD_SET_PARAM(command, sizeof(data2));
    ASSERT_EQ(nos_device_write(dev, command, &data2, sizeof(data2)), 0);
  }
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_9876);
    SetCommandInfoCrc(&data, sizeof(data), command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status, bad crc as the args data is wrong
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsBadCrc());
  }
}

/* Not setting the CMD_MORE_TO_COME flag clears previous data. */
TEST_F(TransportTest, SendArgumentDataInMultipleDatagramsWithRestart) {
  const uint32_t data = 0x09080706;
  { // Send data1
    const uint8_t spam[6] = {12, 46, 123, 63, 23, 75};
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_DATA | CMD_TRANSPORT;
    CMD_SET_PARAM(command, sizeof(spam));
    ASSERT_EQ(nos_device_write(dev, command, spam, sizeof(spam)), 0);
  }
  { // Send data1, restarting the args
    const uint16_t data1 = 0x0706;
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_DATA | CMD_TRANSPORT;
    CMD_SET_PARAM(command, sizeof(data1));
    ASSERT_EQ(nos_device_write(dev, command, &data1, sizeof(data1)), 0);
  }
  { // Send data2
    const uint16_t data2 = 0x0908;
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST)
                     | CMD_IS_DATA | CMD_TRANSPORT | CMD_MORE_TO_COME;
    CMD_SET_PARAM(command, sizeof(data2));
    ASSERT_EQ(nos_device_write(dev, command, &data2, sizeof(data2)), 0);
  }
  { // Send "go" command
    transport_command_info command_info = {};
    command_info.length = sizeof(transport_command_info);
    command_info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_9876);
    SetCommandInfoCrc(&data, sizeof(data), command, &command_info);
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsSuccess());
  }
}

// Forward compatibility

/* New command info fields may be add in future versions. The crc is still
 * calculated over them to ensure data integrity but, otherwise, the values are
 * ignored. */
TEST_F(TransportTest, NewCommandInfoIsIgnored) {
  { // Send "go" command
    union {
      transport_command_info info;
      uint8_t buffer[COMMAND_INFO_MAX_LENGTH];
    } command_info = {};
    memset(command_info.buffer, 0x48, COMMAND_INFO_MAX_LENGTH);
    command_info.info.length = COMMAND_INFO_MAX_LENGTH;
    command_info.info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_NOP);
    // CRC is still calculated over all the data but new fields aren't used
    SetCommandInfoCrc(nullptr, 0, command, &command_info, sizeof(command_info));
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsSuccess());
  }
}

/* If the protocol adds more data to the command info datagram, it is not
 * included in the crc. */
TEST_F(TransportTest, CommandCrcOnlyCoversCommandInfoStruct) {
  { // Send "go" command
    union {
      transport_command_info info;
      /* The extra byte should not be included in the crc */
      uint8_t buffer[COMMAND_INFO_MAX_LENGTH + 1];
    } command_info = {};
    command_info.info.length = COMMAND_INFO_MAX_LENGTH;
    command_info.info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(0);
    SetCommandInfoCrc(nullptr, 0, command, &command_info, sizeof(command_info));
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsBadCrc());
  }
}

/* Future protocol updates may require more command info data which will be
 * added after the COMMAND_INFO_MAX_LENGTH bytes currently reserved for the
 * command info struct. The extra data should be ignored to allow for
 * compatibility with such an upgrade. */
TEST_F(TransportTest, NewCommandInfoStructIsIgnored) {
  { // Send "go" command
    union {
      transport_command_info info;
      struct {
        uint8_t info[COMMAND_INFO_MAX_LENGTH];
        uint8_t new_struct[48];
      } buffer;
    } command_info = {};
    memset(command_info.buffer.info, 0xB6, COMMAND_INFO_MAX_LENGTH);
    memset(command_info.buffer.new_struct, 0x19, sizeof(command_info.buffer.new_struct));
    command_info.info.length = COMMAND_INFO_MAX_LENGTH;
    command_info.info.version = TRANSPORT_V1;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_NOP);
    // CRC is still calculated over all the data but new fields aren't used
    SetCommandInfoCrc(nullptr, 0, command, &command_info, sizeof(command_info));
    ASSERT_EQ(nos_device_write(dev, command, &command_info, sizeof(command_info)), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsSuccess());
  }
}

// Backward compatibility

/* V0 does not send any checksums or command info */
TEST_F(TransportTest, CompatibleWithV0) {
  { // Send data
    uint8_t data[4] = {23, 54, 133, 249};
    uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_DATA | CMD_TRANSPORT;
    CMD_SET_PARAM(command, sizeof(data));
    ASSERT_EQ(nos_device_write(dev, command, &data, sizeof(data)), 0);
  }
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsIdle());
  }
  { // Send "go" command (without command info or crc)
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_PARAM(TRANSPORT_TEST_NOP);
    ASSERT_EQ(nos_device_write(dev, command, nullptr, 0), 0);
  }
  // Let app process command
  WaitForApp();
  { // Check status
    transport_status status;
    const uint32_t command = CMD_ID(APP_ID_TRANSPORT_TEST) | CMD_IS_READ | CMD_TRANSPORT;
    ASSERT_EQ(nos_device_read(dev, command, &status, sizeof(transport_status)), 0);
    ASSERT_THAT(status, IsSuccess());
  }
}

}  // namespace
