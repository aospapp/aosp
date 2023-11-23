/******************************************************************************
 *
 *  Copyright 2022,2023 NXP
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include <stdint.h>

#include <string>
#ifndef NXP_FEATURES_H
#define NXP_FEATURES_H

#define STRMAX_2 100
#define FW_MOBILE_MAJOR_NUMBER_PN553 0x01
#define FW_MOBILE_MAJOR_NUMBER_PN551 0x05
#define FW_MOBILE_MAJOR_NUMBER_PN48AD 0x01
#define FW_MOBILE_MAJOR_NUMBER_PN81A 0x02
#define FW_MOBILE_MAJOR_NUMBER_PN557 0x01
#define FW_MOBILE_MAJOR_NUMBER_SN100U 0x010
#define FW_MOBILE_MAJOR_NUMBER_SN220U 0x01

/*Including T4T NFCEE by incrementing 1*/
#define NFA_EE_MAX_EE_SUPPORTED 5

#define JCOP_VER_3_3 3
#define JCOP_VER_4_0 4
#ifndef FW_LIB_ROOT_DIR
#if (defined(__arm64__) || defined(__aarch64__) || defined(_M_ARM64))
#define FW_LIB_ROOT_DIR "/vendor/lib64/"
#else
#define FW_LIB_ROOT_DIR "/vendor/lib/"
#endif
#endif
#ifndef FW_BIN_ROOT_DIR
#define FW_BIN_ROOT_DIR "/vendor/firmware/"
#endif
#ifndef FW_LIB_EXTENSION
#define FW_LIB_EXTENSION ".so"
#endif
#ifndef FW_BIN_EXTENSION
#define FW_BIN_EXTENSION ".bin"
#endif
using namespace std;
typedef enum {
  NFCC_DWNLD_WITH_VEN_RESET,
  NFCC_DWNLD_WITH_NCI_CMD
} tNFCC_DnldType;

typedef enum {
  DEFAULT_CHIP_TYPE = 0x00,
  pn547C2 = 0x01,
  pn65T,
  pn548C2,
  pn66T,
  pn551,
  pn67T,
  pn553,
  pn80T,
  pn557,
  pn81T,
  sn100u,
  sn220u,
  pn560
} tNFC_chipType;

typedef struct {
  /*Flags common to all chip types*/
  uint8_t _NFCC_I2C_READ_WRITE_IMPROVEMENT : 1;
  uint8_t _NFCC_MIFARE_TIANJIN : 1;
  uint8_t _NFCC_SPI_FW_DOWNLOAD_SYNC : 1;
  uint8_t _NFCEE_REMOVED_NTF_RECOVERY : 1;
  uint8_t _NFCC_FORCE_FW_DOWNLOAD : 1;
  uint8_t _NFA_EE_MAX_EE_SUPPORTED : 3;
  uint8_t _NFCC_DWNLD_MODE : 1;
} tNfc_nfccFeatureList;

typedef struct {
  uint8_t nfcNxpEse : 1;
  tNFC_chipType chipType;
  std::string _FW_LIB_PATH;
  std::string _FW_BIN_PATH;
  uint16_t _PHDNLDNFC_USERDATA_EEPROM_OFFSET;
  uint16_t _PHDNLDNFC_USERDATA_EEPROM_LEN;
  uint8_t _FW_MOBILE_MAJOR_NUMBER;
  tNfc_nfccFeatureList nfccFL;
} tNfc_featureList;

extern tNfc_featureList nfcFL;
#define IS_CHIP_TYPE_GE(cType) (nfcFL.chipType >= cType)
#define IS_CHIP_TYPE_EQ(cType) (nfcFL.chipType == cType)
#define IS_CHIP_TYPE_LE(cType) (nfcFL.chipType <= cType)
#define IS_CHIP_TYPE_L(cType) (nfcFL.chipType < cType)
#define IS_CHIP_TYPE_NE(cType) (nfcFL.chipType != cType)

#define CONFIGURE_FEATURELIST(chipType)               \
  {                                                   \
    nfcFL.chipType = chipType;                        \
    switch (chipType) {                               \
      case pn81T:                                     \
        nfcFL.chipType = pn557;                       \
        nfcFL.nfcNxpEse = true;                       \
        CONFIGURE_FEATURELIST_NFCC_WITH_ESE(chipType) \
        break;                                        \
      case pn80T:                                     \
        nfcFL.chipType = pn553;                       \
        nfcFL.nfcNxpEse = true;                       \
        CONFIGURE_FEATURELIST_NFCC_WITH_ESE(chipType) \
        break;                                        \
      case pn67T:                                     \
        nfcFL.chipType = pn551;                       \
        nfcFL.nfcNxpEse = true;                       \
        CONFIGURE_FEATURELIST_NFCC_WITH_ESE(chipType) \
        break;                                        \
      case pn66T:                                     \
        nfcFL.chipType = pn548C2;                     \
        nfcFL.nfcNxpEse = true;                       \
        CONFIGURE_FEATURELIST_NFCC_WITH_ESE(chipType) \
        break;                                        \
      case pn65T:                                     \
        nfcFL.chipType = pn547C2;                     \
        nfcFL.nfcNxpEse = true;                       \
        CONFIGURE_FEATURELIST_NFCC_WITH_ESE(chipType) \
        break;                                        \
      case sn100u:                                    \
        nfcFL.chipType = sn100u;                      \
        nfcFL.nfcNxpEse = true;                       \
        CONFIGURE_FEATURELIST_NFCC_WITH_ESE(chipType) \
        break;                                        \
      case sn220u:                                    \
        nfcFL.chipType = sn220u;                      \
        nfcFL.nfcNxpEse = true;                       \
        CONFIGURE_FEATURELIST_NFCC_WITH_ESE(chipType) \
        break;                                        \
      default:                                        \
        nfcFL.nfcNxpEse = false;                      \
        CONFIGURE_FEATURELIST_NFCC(chipType)          \
    }                                                 \
  }

#define CONFIGURE_FEATURELIST_NFCC_WITH_ESE(chipType)   \
  {                                                     \
    switch (chipType) {                                 \
      case pn81T:                                       \
        CONFIGURE_FEATURELIST_NFCC(pn557)               \
        nfcFL.nfccFL._NFCC_SPI_FW_DOWNLOAD_SYNC = true; \
        nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED = 4;      \
        break;                                          \
      case sn100u:                                      \
        CONFIGURE_FEATURELIST_NFCC(sn100u)              \
        nfcFL.nfccFL._NFCC_SPI_FW_DOWNLOAD_SYNC = true; \
        nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED = 5;      \
        break;                                          \
      case sn220u:                                      \
        CONFIGURE_FEATURELIST_NFCC(sn220u)              \
        nfcFL.nfccFL._NFCC_SPI_FW_DOWNLOAD_SYNC = true; \
        nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED = 4;      \
        break;                                          \
      default:                                          \
        break;                                          \
    }                                                   \
  }

#define CONFIGURE_FEATURELIST_NFCC(chipType)                           \
  {                                                                    \
    nfcFL._PHDNLDNFC_USERDATA_EEPROM_OFFSET = 0x023CU;                 \
    nfcFL._PHDNLDNFC_USERDATA_EEPROM_LEN = 0x0C80U;                    \
    nfcFL._FW_MOBILE_MAJOR_NUMBER = FW_MOBILE_MAJOR_NUMBER_PN48AD;     \
    nfcFL.nfccFL._NFCC_DWNLD_MODE = NFCC_DWNLD_WITH_VEN_RESET;         \
    switch (chipType) {                                                \
      case pn557:                                                      \
        nfcFL.nfccFL._NFCC_I2C_READ_WRITE_IMPROVEMENT = true;          \
        STRCPY_FW("libpn557_fw")                                       \
        STRCPY_FW_BIN("pn557")                                         \
        break;                                                         \
      case sn100u:                                                     \
        nfcFL.nfccFL._NFCC_DWNLD_MODE = NFCC_DWNLD_WITH_NCI_CMD;       \
        nfcFL.nfccFL._NFCC_I2C_READ_WRITE_IMPROVEMENT = true;          \
        nfcFL.nfccFL._NFCC_MIFARE_TIANJIN = false;                     \
        nfcFL.nfccFL._NFCC_FORCE_FW_DOWNLOAD = true;                   \
        nfcFL._FW_MOBILE_MAJOR_NUMBER = FW_MOBILE_MAJOR_NUMBER_SN100U; \
        STRCPY_FW("libsn100u_fw")                                      \
        STRCPY_FW_BIN("sn100u")                                        \
        break;                                                         \
      case sn220u:                                                     \
        nfcFL.nfccFL._NFCC_DWNLD_MODE = NFCC_DWNLD_WITH_NCI_CMD;       \
        nfcFL.nfccFL._NFCC_I2C_READ_WRITE_IMPROVEMENT = true;          \
        nfcFL.nfccFL._NFCC_MIFARE_TIANJIN = false;                     \
        nfcFL.nfccFL._NFCC_FORCE_FW_DOWNLOAD = true;                   \
        nfcFL._FW_MOBILE_MAJOR_NUMBER = FW_MOBILE_MAJOR_NUMBER_SN220U; \
        STRCPY_FW("libsn220u_fw")                                      \
        STRCPY_FW_BIN("sn220u")                                        \
        break;                                                         \
      case pn560:                                                      \
        nfcFL.nfccFL._NFCC_DWNLD_MODE = NFCC_DWNLD_WITH_NCI_CMD;       \
        nfcFL.nfccFL._NFCC_I2C_READ_WRITE_IMPROVEMENT = true;          \
        nfcFL.nfccFL._NFCC_MIFARE_TIANJIN = false;                     \
        nfcFL.nfccFL._NFCC_FORCE_FW_DOWNLOAD = true;                   \
        nfcFL._FW_MOBILE_MAJOR_NUMBER = FW_MOBILE_MAJOR_NUMBER_SN220U; \
        STRCPY_FW("libpn560_fw")                                       \
        STRCPY_FW_BIN("pn560")                                         \
        break;                                                         \
      default:                                                         \
        nfcFL.nfccFL._NFCC_FORCE_FW_DOWNLOAD = true;                   \
        break;                                                         \
    }                                                                  \
  }

#define STRCPY_FW_BIN(str)                       \
  {                                              \
    nfcFL._FW_BIN_PATH.clear();                  \
    nfcFL._FW_BIN_PATH.append(FW_BIN_ROOT_DIR);  \
    nfcFL._FW_BIN_PATH.append(str);              \
    nfcFL._FW_BIN_PATH.append(FW_BIN_EXTENSION); \
  }
#define STRCPY_FW(str1)                          \
  {                                              \
    nfcFL._FW_LIB_PATH.clear();                  \
    nfcFL._FW_LIB_PATH.append(FW_LIB_ROOT_DIR);  \
    nfcFL._FW_LIB_PATH.append(str1);             \
    nfcFL._FW_LIB_PATH.append(FW_LIB_EXTENSION); \
  }
#endif
