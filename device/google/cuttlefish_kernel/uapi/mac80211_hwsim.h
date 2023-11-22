/****************************************************************************
 ****************************************************************************
 ***
 ***   This header was automatically generated from a Linux kernel header
 ***   of the same name, to make information necessary for userspace to
 ***   call into the kernel available to libc.  It contains only constants,
 ***   structures, and macros generated from the original header, and thus,
 ***   contains no copyrightable information.
 ***
 ***   To edit the content of this header, modify the corresponding
 ***   source file (e.g. under external/kernel-headers/original/) then
 ***   run bionic/libc/kernel/tools/update_all.py
 ***
 ***   Any manual change here will be lost the next time this script will
 ***   be run. You've been warned!
 ***
 ****************************************************************************
 ****************************************************************************/
#ifndef __MAC80211_HWSIM_H
#define __MAC80211_HWSIM_H
enum hwsim_tx_control_flags {
  HWSIM_TX_CTL_REQ_TX_STATUS = BIT(0),
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
  HWSIM_TX_CTL_NO_ACK = BIT(1),
  HWSIM_TX_STAT_ACK = BIT(2),
};
enum {
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
  HWSIM_CMD_UNSPEC,
  HWSIM_CMD_REGISTER,
  HWSIM_CMD_FRAME,
  HWSIM_CMD_TX_INFO_FRAME,
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
  HWSIM_CMD_NEW_RADIO,
  HWSIM_CMD_DEL_RADIO,
  HWSIM_CMD_GET_RADIO,
  __HWSIM_CMD_MAX,
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
};
#define HWSIM_CMD_MAX (_HWSIM_CMD_MAX - 1)
#define HWSIM_CMD_CREATE_RADIO HWSIM_CMD_NEW_RADIO
#define HWSIM_CMD_DESTROY_RADIO HWSIM_CMD_DEL_RADIO
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
enum {
  HWSIM_ATTR_UNSPEC,
  HWSIM_ATTR_ADDR_RECEIVER,
  HWSIM_ATTR_ADDR_TRANSMITTER,
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
  HWSIM_ATTR_FRAME,
  HWSIM_ATTR_FLAGS,
  HWSIM_ATTR_RX_RATE,
  HWSIM_ATTR_SIGNAL,
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
  HWSIM_ATTR_TX_INFO,
  HWSIM_ATTR_COOKIE,
  HWSIM_ATTR_CHANNELS,
  HWSIM_ATTR_RADIO_ID,
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
  HWSIM_ATTR_REG_HINT_ALPHA2,
  HWSIM_ATTR_REG_CUSTOM_REG,
  HWSIM_ATTR_REG_STRICT_REG,
  HWSIM_ATTR_SUPPORT_P2P_DEVICE,
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
  HWSIM_ATTR_USE_CHANCTX,
  HWSIM_ATTR_DESTROY_RADIO_ON_CLOSE,
  HWSIM_ATTR_RADIO_NAME,
  HWSIM_ATTR_NO_VIF,
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
  HWSIM_ATTR_FREQ,
  __HWSIM_ATTR_MAX,
};
#define HWSIM_ATTR_MAX (__HWSIM_ATTR_MAX - 1)
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
struct hwsim_tx_rate {
  s8 idx;
  u8 count;
} __packed;
/* WARNING: DO NOT EDIT, AUTO-GENERATED CODE - SEE TOP FOR INSTRUCTIONS */
#endif

