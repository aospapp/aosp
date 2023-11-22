/********************************************************************************
Copyright (C) 2016 Marvell International Ltd.

Marvell BSD License Option

If you received this File from Marvell, you may opt to use, redistribute and/or
modify this File under the following licensing terms.
Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.

* Neither the name of Marvell nor the names of its contributors may be
  used to endorse or promote products derived from this software without
  specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*******************************************************************************/

#ifndef __COMPHY_H__
#define __COMPHY_H__

#include <Library/ArmLib.h>
#include <Library/ArmPlatformLib.h>
#include <Library/DebugLib.h>
#include <Library/PcdLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/IoLib.h>
#include <Library/TimerLib.h>
#include <Library/ParsePcdLib.h>

#define MAX_LANE_OPTIONS          10
#define MAX_CHIPS                 4

/***** Parsing PCD *****/
#define GET_TYPE_STRING(id)       PcdGetPtr(PcdChip##id##Compatible)
#define GET_LANE_TYPE(id)         PcdGetPtr(PcdChip##id##ComPhyTypes)
#define GET_LANE_SPEED(id)        PcdGetPtr(PcdChip##id##ComPhySpeeds)
#define GET_LANE_INV(id)          PcdGetPtr(PcdChip##id##ComPhyInvFlags)
#define GET_COMPHY_BASE_ADDR(id)  PcdGet64(PcdChip##id##ComPhyBaseAddress)
#define GET_HPIPE3_BASE_ADDR(id)  PcdGet64(PcdChip##id##Hpipe3BaseAddress)
#define GET_MUX_BIT_COUNT(id)     PcdGet32(PcdChip##id##ComPhyMuxBitCount)
#define GET_MAX_LANES(id)         PcdGet32(PcdChip##id##ComPhyMaxLanes)

#define FillLaneMap(chip_struct, lane_struct, id) { \
  ParsePcdString((CHAR16 *) GET_LANE_TYPE(id), chip_struct[id].LanesCount, NULL, lane_struct[id].TypeStr);     \
  ParsePcdString((CHAR16 *) GET_LANE_SPEED(id), chip_struct[id].LanesCount, lane_struct[id].SpeedValue, NULL); \
  ParsePcdString((CHAR16 *) GET_LANE_INV(id), chip_struct[id].LanesCount, lane_struct[id].InvFlag, NULL);      \
}

#define GetComPhyPcd(chip_struct, lane_struct, id) {               \
  chip_struct[id].ChipType = (CHAR16 *) GET_TYPE_STRING(id);       \
  chip_struct[id].ComPhyBaseAddr = GET_COMPHY_BASE_ADDR(id);       \
  chip_struct[id].Hpipe3BaseAddr = GET_HPIPE3_BASE_ADDR(id);       \
  chip_struct[id].MuxBitCount = GET_MUX_BIT_COUNT(id);             \
  chip_struct[id].LanesCount = GET_MAX_LANES(id);                  \
  FillLaneMap(chip_struct, lane_struct, id);                       \
}

/***** ComPhy *****/
#define PHY_SPEED_ERROR                           0
#define PHY_SPEED_1_25G                           1
#define PHY_SPEED_1_5G                            2
#define PHY_SPEED_2_5G                            3
#define PHY_SPEED_3G                              4
#define PHY_SPEED_3_125G                          5
#define PHY_SPEED_5G                              6
#define PHY_SPEED_6G                              7
#define PHY_SPEED_6_25G                           8
#define PHY_SPEED_10_3125G                        9
#define PHY_SPEED_MAX                             10
#define PHY_SPEED_INVALID                         0xff

#define PHY_TYPE_UNCONNECTED                      0
#define PHY_TYPE_PCIE0                            1
#define PHY_TYPE_PCIE1                            2
#define PHY_TYPE_PCIE2                            3
#define PHY_TYPE_PCIE3                            4
#define PHY_TYPE_SATA0                            5
#define PHY_TYPE_SATA1                            6
#define PHY_TYPE_SATA2                            7
#define PHY_TYPE_SATA3                            8
#define PHY_TYPE_SGMII0                           9
#define PHY_TYPE_SGMII1                           10
#define PHY_TYPE_SGMII2                           11
#define PHY_TYPE_SGMII3                           12
#define PHY_TYPE_QSGMII                           13
#define PHY_TYPE_USB3_HOST0                       14
#define PHY_TYPE_USB3_HOST1                       15
#define PHY_TYPE_USB3_DEVICE                      16
#define PHY_TYPE_XAUI0                            17
#define PHY_TYPE_XAUI1                            18
#define PHY_TYPE_XAUI2                            19
#define PHY_TYPE_XAUI3                            20
#define PHY_TYPE_RXAUI0                           21
#define PHY_TYPE_RXAUI1                           22
#define PHY_TYPE_KR                               23
#define PHY_TYPE_MAX                              24
#define PHY_TYPE_INVALID                          0xff

#define PHY_POLARITY_NO_INVERT                    0
#define PHY_POLARITY_TXD_INVERT                   1
#define PHY_POLARITY_RXD_INVERT                   2
#define PHY_POLARITY_ALL_INVERT                   (PHY_POLARITY_TXD_INVERT | PHY_POLARITY_RXD_INVERT)

/***** SerDes IP registers *****/
#define SD_EXTERNAL_CONFIG0_REG                   0
#define SD_EXTERNAL_CONFIG0_SD_PU_PLL_OFFSET      1
#define SD_EXTERNAL_CONFIG0_SD_PU_PLL_MASK        (1 << SD_EXTERNAL_CONFIG0_SD_PU_PLL_OFFSET)
#define SD_EXTERNAL_CONFIG0_SD_PHY_GEN_RX_OFFSET  3
#define SD_EXTERNAL_CONFIG0_SD_PHY_GEN_RX_MASK    (0xf << SD_EXTERNAL_CONFIG0_SD_PHY_GEN_RX_OFFSET)
#define SD_EXTERNAL_CONFIG0_SD_PHY_GEN_TX_OFFSET  7
#define SD_EXTERNAL_CONFIG0_SD_PHY_GEN_TX_MASK    (0xf << SD_EXTERNAL_CONFIG0_SD_PHY_GEN_TX_OFFSET)
#define SD_EXTERNAL_CONFIG0_SD_PU_RX_OFFSET       11
#define SD_EXTERNAL_CONFIG0_SD_PU_RX_MASK         (1 << SD_EXTERNAL_CONFIG0_SD_PU_RX_OFFSET)
#define SD_EXTERNAL_CONFIG0_SD_PU_TX_OFFSET       12
#define SD_EXTERNAL_CONFIG0_SD_PU_TX_MASK         (1 << SD_EXTERNAL_CONFIG0_SD_PU_TX_OFFSET)
#define SD_EXTERNAL_CONFIG0_HALF_BUS_MODE_OFFSET  14
#define SD_EXTERNAL_CONFIG0_HALF_BUS_MODE_MASK    (1 << SD_EXTERNAL_CONFIG0_HALF_BUS_MODE_OFFSET)

#define SD_EXTERNAL_CONFIG1_REG                   0x4
#define SD_EXTERNAL_CONFIG1_RESET_IN_OFFSET       3
#define SD_EXTERNAL_CONFIG1_RESET_IN_MASK         (0x1 << SD_EXTERNAL_CONFIG1_RESET_IN_OFFSET)
#define SD_EXTERNAL_CONFIG1_RX_INIT_OFFSET        4
#define SD_EXTERNAL_CONFIG1_RX_INIT_MASK          (0x1 << SD_EXTERNAL_CONFIG1_RX_INIT_OFFSET)
#define SD_EXTERNAL_CONFIG1_RESET_CORE_OFFSET     5
#define SD_EXTERNAL_CONFIG1_RESET_CORE_MASK       (0x1 << SD_EXTERNAL_CONFIG1_RESET_CORE_OFFSET)
#define SD_EXTERNAL_CONFIG1_RF_RESET_IN_OFFSET    6
#define SD_EXTERNAL_CONFIG1_RF_RESET_IN_MASK      (0x1 << SD_EXTERNAL_CONFIG1_RF_RESET_IN_OFFSET)


#define SD_EXTERNAL_STATUS0_REG                   0x18
#define SD_EXTERNAL_STATUS0_PLL_TX_OFFSET         2
#define SD_EXTERNAL_STATUS0_PLL_TX_MASK           (0x1 << SD_EXTERNAL_STATUS0_PLL_TX_OFFSET)
#define SD_EXTERNAL_STATUS0_PLL_RX_OFFSET         3
#define SD_EXTERNAL_STATUS0_PLL_RX_MASK           (0x1 << SD_EXTERNAL_STATUS0_PLL_RX_OFFSET)
#define SD_EXTERNAL_STATUS0_RX_INIT_OFFSET        4
#define SD_EXTERNAL_STATUS0_RX_INIT_MASK          (0x1 << SD_EXTERNAL_STATUS0_RX_INIT_OFFSET)

/***** HPIPE registers *****/
#define HPIPE_PWR_PLL_REG                         0x4
#define HPIPE_PWR_PLL_REF_FREQ_OFFSET             0
#define HPIPE_PWR_PLL_REF_FREQ_MASK               (0x1f << HPIPE_PWR_PLL_REF_FREQ_OFFSET)
#define HPIPE_PWR_PLL_PHY_MODE_OFFSET             5
#define HPIPE_PWR_PLL_PHY_MODE_MASK               (0x7 << HPIPE_PWR_PLL_PHY_MODE_OFFSET)

#define HPIPE_KVCO_CALIB_CTRL_REG                 0x8
#define HPIPE_KVCO_CALIB_CTRL_MAX_PLL_OFFSET      12
#define HPIPE_KVCO_CALIB_CTRL_MAX_PLL_MASK        (0x1 << HPIPE_KVCO_CALIB_CTRL_MAX_PLL_OFFSET)

#define HPIPE_SQUELCH_FFE_SETTING_REG             0x018

#define HPIPE_DFE_REG0                            0x01C
#define HPIPE_DFE_RES_FORCE_OFFSET                15
#define HPIPE_DFE_RES_FORCE_MASK                  (0x1 << HPIPE_DFE_RES_FORCE_OFFSET)


#define HPIPE_DFE_F3_F5_REG                       0x028
#define HPIPE_DFE_F3_F5_DFE_EN_OFFSET             14
#define HPIPE_DFE_F3_F5_DFE_EN_MASK               (0x1 << HPIPE_DFE_F3_F5_DFE_EN_OFFSET)
#define HPIPE_DFE_F3_F5_DFE_CTRL_OFFSET           15
#define HPIPE_DFE_F3_F5_DFE_CTRL_MASK             (0x1 << HPIPE_DFE_F3_F5_DFE_CTRL_OFFSET)

#define HPIPE_G1_SET_0_REG                        0x034
#define HPIPE_G1_SET_0_G1_TX_EMPH1_OFFSET         7
#define HPIPE_G1_SET_0_G1_TX_EMPH1_MASK           (0xf << HPIPE_G1_SET_0_G1_TX_EMPH1_OFFSET)

#define HPIPE_G1_SET_1_REG                        0x038
#define HPIPE_G1_SET_1_G1_RX_SELMUPI_OFFSET       0
#define HPIPE_G1_SET_1_G1_RX_SELMUPI_MASK         (0x7 << HPIPE_G1_SET_1_G1_RX_SELMUPI_OFFSET)
#define HPIPE_G1_SET_1_G1_RX_SELMUPP_OFFSET       3
#define HPIPE_G1_SET_1_G1_RX_SELMUPP_MASK         (0x7 << HPIPE_G1_SET_1_G1_RX_SELMUPP_OFFSET)
#define HPIPE_G1_SET_1_G1_RX_DFE_EN_OFFSET        10
#define HPIPE_G1_SET_1_G1_RX_DFE_EN_MASK          (0x1 << HPIPE_G1_SET_1_G1_RX_DFE_EN_OFFSET)

#define HPIPE_G2_SETTINGS_1_REG                   0x040

#define HPIPE_LOOPBACK_REG                        0x08c
#define HPIPE_LOOPBACK_SEL_OFFSET                 1
#define HPIPE_LOOPBACK_SEL_MASK                   (0x7 << HPIPE_LOOPBACK_SEL_OFFSET)

#define HPIPE_SYNC_PATTERN_REG                    0x090

#define HPIPE_INTERFACE_REG                       0x94
#define HPIPE_INTERFACE_GEN_MAX_OFFSET            10
#define HPIPE_INTERFACE_GEN_MAX_MASK              (0x3 << HPIPE_INTERFACE_GEN_MAX_OFFSET)
#define HPIPE_INTERFACE_LINK_TRAIN_OFFSET         14
#define HPIPE_INTERFACE_LINK_TRAIN_MASK           (0x1 << HPIPE_INTERFACE_LINK_TRAIN_OFFSET)

#define HPIPE_ISOLATE_MODE_REG                    0x98
#define HPIPE_ISOLATE_MODE_GEN_RX_OFFSET          0
#define HPIPE_ISOLATE_MODE_GEN_RX_MASK            (0xf << HPIPE_ISOLATE_MODE_GEN_RX_OFFSET)
#define HPIPE_ISOLATE_MODE_GEN_TX_OFFSET          4
#define HPIPE_ISOLATE_MODE_GEN_TX_MASK            (0xf << HPIPE_ISOLATE_MODE_GEN_TX_OFFSET)

#define HPIPE_VTHIMPCAL_CTRL_REG                  0x104

#define HPIPE_PCIE_REG0                           0x120
#define HPIPE_PCIE_IDLE_SYNC_OFFSET               12
#define HPIPE_PCIE_IDLE_SYNC_MASK                 (0x1 << HPIPE_PCIE_IDLE_SYNC_OFFSET)
#define HPIPE_PCIE_SEL_BITS_OFFSET                13
#define HPIPE_PCIE_SEL_BITS_MASK                  (0x3 << HPIPE_PCIE_SEL_BITS_OFFSET)

#define HPIPE_LANE_ALIGN_REG                      0x124
#define HPIPE_LANE_ALIGN_OFF_OFFSET               12
#define HPIPE_LANE_ALIGN_OFF_MASK                 (0x1 << HPIPE_LANE_ALIGN_OFF_OFFSET)

#define HPIPE_MISC_REG                            0x13C
#define HPIPE_MISC_CLK100M_125M_OFFSET            4
#define HPIPE_MISC_CLK100M_125M_MASK              (0x1 << HPIPE_MISC_CLK100M_125M_OFFSET)
#define HPIPE_MISC_TXDCLK_2X_OFFSET               6
#define HPIPE_MISC_TXDCLK_2X_MASK                 (0x1 << HPIPE_MISC_TXDCLK_2X_OFFSET)
#define HPIPE_MISC_CLK500_EN_OFFSET               7
#define HPIPE_MISC_CLK500_EN_MASK                 (0x1 << HPIPE_MISC_CLK500_EN_OFFSET)
#define HPIPE_MISC_REFCLK_SEL_OFFSET              10
#define HPIPE_MISC_REFCLK_SEL_MASK                (0x1 << HPIPE_MISC_REFCLK_SEL_OFFSET)

#define HPIPE_RX_CONTROL_1_REG                    0x140
#define HPIPE_RX_CONTROL_1_RXCLK2X_SEL_OFFSET     11
#define HPIPE_RX_CONTROL_1_RXCLK2X_SEL_MASK       (0x1 << HPIPE_RX_CONTROL_1_RXCLK2X_SEL_OFFSET)
#define HPIPE_RX_CONTROL_1_CLK8T_EN_OFFSET        12
#define HPIPE_RX_CONTROL_1_CLK8T_EN_MASK          (0x1 << HPIPE_RX_CONTROL_1_CLK8T_EN_OFFSET)

#define HPIPE_PWR_CTR_REG                         0x148
#define HPIPE_PWR_CTR_RST_DFE_OFFSET              0
#define HPIPE_PWR_CTR_RST_DFE_MASK                (0x1 << HPIPE_PWR_CTR_RST_DFE_OFFSET)
#define HPIPE_PWR_CTR_SFT_RST_OFFSET              10
#define HPIPE_PWR_CTR_SFT_RST_MASK                (0x1 << HPIPE_PWR_CTR_SFT_RST_OFFSET)

#define HPIPE_PLLINTP_REG1                        0x150

#define HPIPE_PWR_CTR_DTL_REG                     0x184
#define HPIPE_PWR_CTR_DTL_FLOOP_EN_OFFSET         0x2
#define HPIPE_PWR_CTR_DTL_FLOOP_EN_MASK           (0x1 << HPIPE_PWR_CTR_DTL_FLOOP_EN_OFFSET)

#define HPIPE_RX_REG3                             0x188

#define HPIPE_TX_TRAIN_CTRL_REG                   0x26C
#define HPIPE_TX_TRAIN_CTRL_G1_OFFSET             0
#define HPIPE_TX_TRAIN_CTRL_G1_MASK               (0x1 << HPIPE_TX_TRAIN_CTRL_G1_OFFSET)
#define HPIPE_TX_TRAIN_CTRL_GN1_OFFSET            1
#define HPIPE_TX_TRAIN_CTRL_GN1_MASK              (0x1 << HPIPE_TX_TRAIN_CTRL_GN1_OFFSET)
#define HPIPE_TX_TRAIN_CTRL_G0_OFFSET             2
#define HPIPE_TX_TRAIN_CTRL_G0_MASK               (0x1 << HPIPE_TX_TRAIN_CTRL_G0_OFFSET)

#define HPIPE_PCIE_REG1                           0x288
#define HPIPE_PCIE_REG3                           0x290

#define HPIPE_TX_TRAIN_REG                        0x31C
#define HPIPE_TX_TRAIN_CHK_INIT_OFFSET            4
#define HPIPE_TX_TRAIN_CHK_INIT_MASK              (0x1 << HPIPE_TX_TRAIN_CHK_INIT_OFFSET)
#define HPIPE_TX_TRAIN_COE_FM_PIN_PCIE3_OFFSET    7
#define HPIPE_TX_TRAIN_COE_FM_PIN_PCIE3_MASK      (0x1 << HPIPE_TX_TRAIN_COE_FM_PIN_PCIE3_OFFSET)

#define HPIPE_G1_SETTINGS_3_REG                   0x440
#define HPIPE_G1_SETTINGS_4_REG                   0x444
#define HPIPE_G2_SETTINGS_3_REG                   0x448
#define HPIPE_G2_SETTINGS_4_REG                   0x44C

#define HPIPE_DFE_CTRL_28_REG                     0x49C
#define HPIPE_DFE_CTRL_28_PIPE4_OFFSET            7
#define HPIPE_DFE_CTRL_28_PIPE4_MASK              (0x1 << HPIPE_DFE_CTRL_28_PIPE4_OFFSET)

#define HPIPE_LANE_CONFIG0_REG                    0x604
#define HPIPE_LANE_CONFIG0_MAX_PLL_OFFSET         9
#define HPIPE_LANE_CONFIG0_MAX_PLL_MASK           (0x1 << HPIPE_LANE_CONFIG0_MAX_PLL_OFFSET)
#define HPIPE_LANE_CONFIG0_GEN2_PLL_OFFSET        10
#define HPIPE_LANE_CONFIG0_GEN2_PLL_MASK          (0x1 << HPIPE_LANE_CONFIG0_GEN2_PLL_OFFSET)

#define HPIPE_LANE_STATUS0_REG                    0x60C
#define HPIPE_LANE_STATUS0_PCLK_EN_OFFSET         0
#define HPIPE_LANE_STATUS0_PCLK_EN_MASK           (0x1 << HPIPE_LANE_STATUS0_PCLK_EN_OFFSET)

#define HPIPE_LANE_CFG4_REG                       0x620
#define HPIPE_LANE_CFG4_DFE_CTRL_OFFSET           0
#define HPIPE_LANE_CFG4_DFE_CTRL_MASK             (0x7 << HPIPE_LANE_CFG4_DFE_CTRL_OFFSET)
#define HPIPE_LANE_CFG4_DFE_OVER_OFFSET           6
#define HPIPE_LANE_CFG4_DFE_OVER_MASK             (0x1 << HPIPE_LANE_CFG4_DFE_OVER_OFFSET)
#define HPIPE_LANE_CFG4_SSC_CTRL_OFFSET           7
#define HPIPE_LANE_CFG4_SSC_CTRL_MASK             (0x1 << HPIPE_LANE_CFG4_SSC_CTRL_OFFSET)

#define HPIPE_LANE_EQ_CFG1_REG                    0x6a0
#define HPIPE_CFG_UPDATE_POLARITY_OFFSET          12
#define HPIPE_CFG_UPDATE_POLARITY_MASK            (0x1 << HPIPE_CFG_UPDATE_POLARITY_OFFSET)

#define HPIPE_RST_CLK_CTRL_REG                    0x704
#define HPIPE_RST_CLK_CTRL_PIPE_RST_OFFSET        0
#define HPIPE_RST_CLK_CTRL_PIPE_RST_MASK          (0x1 << HPIPE_RST_CLK_CTRL_PIPE_RST_OFFSET)
#define HPIPE_RST_CLK_CTRL_FIXED_PCLK_OFFSET      2
#define HPIPE_RST_CLK_CTRL_FIXED_PCLK_MASK        (0x1 << HPIPE_RST_CLK_CTRL_FIXED_PCLK_OFFSET)
#define HPIPE_RST_CLK_CTRL_PIPE_WIDTH_OFFSET      3
#define HPIPE_RST_CLK_CTRL_PIPE_WIDTH_MASK        (0x1 << HPIPE_RST_CLK_CTRL_PIPE_WIDTH_OFFSET)
#define HPIPE_RST_CLK_CTRL_CORE_FREQ_SEL_OFFSET   9
#define HPIPE_RST_CLK_CTRL_CORE_FREQ_SEL_MASK     (0x1 << HPIPE_RST_CLK_CTRL_CORE_FREQ_SEL_OFFSET)

#define HPIPE_CLK_SRC_LO_REG                      0x70c
#define HPIPE_CLK_SRC_LO_PLL_RDY_DL_OFFSET        5
#define HPIPE_CLK_SRC_LO_PLL_RDY_DL_MASK          (0x7 << HPIPE_CLK_SRC_LO_PLL_RDY_DL_OFFSET)

#define HPIPE_CLK_SRC_HI_REG                      0x710
#define HPIPE_CLK_SRC_HI_LANE_STRT_OFFSET         0
#define HPIPE_CLK_SRC_HI_LANE_STRT_MASK           (0x1 << HPIPE_CLK_SRC_HI_LANE_STRT_OFFSET)
#define HPIPE_CLK_SRC_HI_LANE_BREAK_OFFSET        1
#define HPIPE_CLK_SRC_HI_LANE_BREAK_MASK          (0x1 << HPIPE_CLK_SRC_HI_LANE_BREAK_OFFSET)
#define HPIPE_CLK_SRC_HI_LANE_MASTER_OFFSET       2
#define HPIPE_CLK_SRC_HI_LANE_MASTER_MASK         (0x1 << HPIPE_CLK_SRC_HI_LANE_MASTER_OFFSET)
#define HPIPE_CLK_SRC_HI_MODE_PIPE_OFFSET         7
#define HPIPE_CLK_SRC_HI_MODE_PIPE_MASK           (0x1 << HPIPE_CLK_SRC_HI_MODE_PIPE_OFFSET)

#define HPIPE_GLOBAL_MISC_CTRL                    0x718
#define HPIPE_GLOBAL_PM_CTRL                      0x740
#define HPIPE_GLOBAL_PM_RXDLOZ_WAIT_OFFSET        0
#define HPIPE_GLOBAL_PM_RXDLOZ_WAIT_MASK          (0xFF << HPIPE_GLOBAL_PM_RXDLOZ_WAIT_OFFSET)

/***** COMPHY registers *****/
#define COMMON_PHY_CFG1_REG                       0x0
#define COMMON_PHY_CFG1_PWR_UP_OFFSET             1
#define COMMON_PHY_CFG1_PWR_UP_MASK               (0x1 << COMMON_PHY_CFG1_PWR_UP_OFFSET)
#define COMMON_PHY_CFG1_PIPE_SELECT_OFFSET        2
#define COMMON_PHY_CFG1_PIPE_SELECT_MASK          (0x1 << COMMON_PHY_CFG1_PIPE_SELECT_OFFSET)
#define COMMON_PHY_CFG1_PWR_ON_RESET_OFFSET       13
#define COMMON_PHY_CFG1_PWR_ON_RESET_MASK         (0x1 << COMMON_PHY_CFG1_PWR_ON_RESET_OFFSET)
#define COMMON_PHY_CFG1_CORE_RSTN_OFFSET          14
#define COMMON_PHY_CFG1_CORE_RSTN_MASK            (0x1 << COMMON_PHY_CFG1_CORE_RSTN_OFFSET)
#define COMMON_PHY_PHY_MODE_OFFSET                15
#define COMMON_PHY_PHY_MODE_MASK                  (0x1 << COMMON_PHY_PHY_MODE_OFFSET)

#define COMMON_PHY_CFG6_REG                       0x14
#define COMMON_PHY_CFG6_IF_40_SEL_OFFSET          18
#define COMMON_PHY_CFG6_IF_40_SEL_MASK            (0x1 << COMMON_PHY_CFG6_IF_40_SEL_OFFSET)

#define COMMON_SELECTOR_PHY_OFFSET                0x140
#define COMMON_SELECTOR_PIPE_OFFSET               0x144

/***** SATA registers *****/
#define SATA3_VENDOR_ADDRESS                      0xA0
#define SATA3_VENDOR_ADDR_OFSSET                  0
#define SATA3_VENDOR_ADDR_MASK                    (0xFFFFFFFF << SATA3_VENDOR_ADDR_OFSSET)
#define SATA3_VENDOR_DATA                         0xA4

#define SATA_CONTROL_REG                          0x0
#define SATA3_CTRL_SATA0_PD_OFFSET                6
#define SATA3_CTRL_SATA0_PD_MASK                  (1 << SATA3_CTRL_SATA0_PD_OFFSET)
#define SATA3_CTRL_SATA1_PD_OFFSET                14
#define SATA3_CTRL_SATA1_PD_MASK                  (1 << SATA3_CTRL_SATA1_PD_OFFSET)
#define SATA3_CTRL_SATA1_ENABLE_OFFSET            22
#define SATA3_CTRL_SATA1_ENABLE_MASK              (1 << SATA3_CTRL_SATA1_ENABLE_OFFSET)
#define SATA3_CTRL_SATA_SSU_OFFSET                23
#define SATA3_CTRL_SATA_SSU_MASK                  (1 << SATA3_CTRL_SATA_SSU_OFFSET)

#define SATA_MBUS_SIZE_SELECT_REG                 0x4
#define SATA_MBUS_REGRET_EN_OFFSET                7
#define SATA_MBUS_REGRET_EN_MASK                  (0x1 << SATA_MBUS_REGRET_EN_OFFSET)

/***************************/

typedef struct _CHIP_COMPHY_CONFIG CHIP_COMPHY_CONFIG;

typedef struct {
  UINT32 Type;
  UINT32 MuxValue;
} COMPHY_MUX_OPTIONS;

typedef struct {
  UINT32 MaxLaneValues;
  COMPHY_MUX_OPTIONS MuxValues[MAX_LANE_OPTIONS];
} COMPHY_MUX_DATA;

typedef struct {
  UINT32 Type;
  UINT32 Speed;
  UINT32 Invert;
} COMPHY_MAP;

typedef struct {
  CHAR16 *TypeStr[MAX_LANE_OPTIONS];
  UINTN SpeedValue[MAX_LANE_OPTIONS];
  UINTN InvFlag[MAX_LANE_OPTIONS];
} PCD_LANE_MAP;

typedef
EFI_STATUS
(*COMPHY_CHIP_INIT) (
  IN CHIP_COMPHY_CONFIG *PtrChipCfg
  );

struct _CHIP_COMPHY_CONFIG {
  CHAR16* ChipType;
  COMPHY_MAP MapData[MAX_LANE_OPTIONS];
  COMPHY_MUX_DATA *MuxData;
  EFI_PHYSICAL_ADDRESS ComPhyBaseAddr;
  EFI_PHYSICAL_ADDRESS Hpipe3BaseAddr;
  COMPHY_CHIP_INIT Init;
  UINT32 LanesCount;
  UINT32 MuxBitCount;
};

VOID
ComPhyMuxInit (
  IN CHIP_COMPHY_CONFIG *PtrChipCfg,
  IN COMPHY_MAP *ComPhyMapData,
  IN EFI_PHYSICAL_ADDRESS SelectorBase
  );

EFI_STATUS
ComPhyCp110Init (
  IN CHIP_COMPHY_CONFIG * First
  );

VOID
RegSet (
  IN EFI_PHYSICAL_ADDRESS Addr,
  IN UINT32 Data,
  IN UINT32 Mask
  );

VOID
RegSetSilent (
  IN EFI_PHYSICAL_ADDRESS Addr,
  IN UINT32 Data,
  IN UINT32 Mask
  );

VOID
RegSet16 (
  IN EFI_PHYSICAL_ADDRESS Addr,
  IN UINT16 Data,
  IN UINT16 Mask
  );

VOID
RegSetSilent16(
  IN EFI_PHYSICAL_ADDRESS Addr,
  IN UINT16 Data,
  IN UINT16 Mask
  );
#endif // __COMPHY_H__
