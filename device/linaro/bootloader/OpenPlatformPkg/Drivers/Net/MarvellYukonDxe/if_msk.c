/**  <at> file
*  Support for PCIe Marvell Yukon gigabit ethernet adapter product family
*
*  Copyright (c) 2011-2016, ARM Limited. All rights reserved.
*
*  This program and the accompanying materials
*  are licensed and made available under the terms and conditions of the BSD License
*  which accompanies this distribution.  The full text of the license may be found at
*  http://opensource.org/licenses/bsd-license.php
*
*  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
*  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.
*
**/

/******************************************************************************
 *
 *  LICENSE:
 *  Copyright (C) Marvell International Ltd. and/or its affiliates
 *
 *  The computer program files contained in this folder ("Files")
 *  are provided to you under the BSD-type license terms provided
 *  below, and any use of such Files and any derivative works
 *  thereof created by you shall be governed by the following terms
 *  and conditions:
 *
 *  - Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  - Neither the name of Marvell nor the names of its contributors
 *    may be used to endorse or promote products derived from this
 *    software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *  "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *  LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *  FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *  COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *  BUT NOT LIMITED TO, PROCUREMENT OF  SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 *  HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 *  STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 *  ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 *  OF THE POSSIBILITY OF SUCH DAMAGE.
 *  /LICENSE
 *
 *****************************************************************************/

/*-
 * Copyright (c) 1997, 1998, 1999, 2000
 *  Bill Paul <wpaul <at> ctr.columbia.edu>.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. All advertising materials mentioning features or use of this software
 *    must display the following acknowledgement:
 *  This product includes software developed by Bill Paul.
 * 4. Neither the name of the author nor the names of any co-contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY Bill Paul AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL Bill Paul OR THE VOICES IN HIS HEAD
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */
/*-
 * Copyright (c) 2003 Nathan L. Binkert <binkertn <at> umich.edu>
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

/*
 * Device driver for the Marvell Yukon II Ethernet controller.
 * Due to lack of documentation, this driver is based on the code from
 * sk (4) and Marvell's myk (4) driver for FreeBSD 5.x.
 */

#include <Base.h>
#include <Library/IoLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/DebugLib.h>
#include <Library/NetLib.h>
#include <Library/PcdLib.h>
#include <Library/BaseLib.h>
#include <Library/TimerLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Protocol/PciIo.h>
#include <IndustryStandard/Pci.h>
#include <IndustryStandard/Acpi.h>
#include "miivar.h"
#include "if_media.h"
#include "if_mskreg.h"
#include "if_msk.h"

#define MSK_CSUM_FEATURES  (CSUM_TCP | CSUM_UDP)

/*
 * Devices supported by this driver.
 */
static struct msk_product {
  UINT16  msk_vendorid;
  UINT16  msk_deviceid;
  const CHAR8  *msk_name;
} msk_products[] = {
{ VENDORID_SK,      DEVICEID_SK_YUKON2,       "SK-9Sxx Gigabit Ethernet" },
{ VENDORID_SK,      DEVICEID_SK_YUKON2_EXPR,  "SK-9Exx Gigabit Ethernet"},
{ VENDORID_MARVELL, DEVICEID_MRVL_8021CU,     "Marvell Yukon 88E8021CU Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8021X,      "Marvell Yukon 88E8021 SX/LX Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8022CU,     "Marvell Yukon 88E8022CU Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8022X,      "Marvell Yukon 88E8022 SX/LX Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8061CU,     "Marvell Yukon 88E8061CU Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8061X,      "Marvell Yukon 88E8061 SX/LX Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8062CU,     "Marvell Yukon 88E8062CU Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8062X,      "Marvell Yukon 88E8062 SX/LX Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8035,       "Marvell Yukon 88E8035 Fast Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8036,       "Marvell Yukon 88E8036 Fast Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8038,       "Marvell Yukon 88E8038 Fast Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8039,       "Marvell Yukon 88E8039 Fast Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8040,       "Marvell Yukon 88E8040 Fast Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8040T,      "Marvell Yukon 88E8040T Fast Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8042,       "Marvell Yukon 88E8042 Fast Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_8048,       "Marvell Yukon 88E8048 Fast Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_4361,       "Marvell Yukon 88E8050 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_4360,       "Marvell Yukon 88E8052 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_4362,       "Marvell Yukon 88E8053 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_4363,       "Marvell Yukon 88E8055 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_4364,       "Marvell Yukon 88E8056 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_4365,       "Marvell Yukon 88E8070 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_436A,       "Marvell Yukon 88E8058 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_436B,       "Marvell Yukon 88E8071 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_436C,       "Marvell Yukon 88E8072 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_4380,       "Marvell Yukon 88E8057 Gigabit Ethernet" },
{ VENDORID_MARVELL, DEVICEID_MRVL_4381,       "Marvell Yukon 88E8059 Gigabit Ethernet" },
{ VENDORID_DLINK,   DEVICEID_DLINK_DGE550SX,  "D-Link 550SX Gigabit Ethernet" },
{ VENDORID_DLINK,   DEVICEID_DLINK_DGE560SX,  "D-Link 560SX Gigabit Ethernet" },
{ VENDORID_DLINK,   DEVICEID_DLINK_DGE560T,   "D-Link 560T Gigabit Ethernet" }
};

#ifndef MDEPKG_NDEBUG
static const CHAR8 *model_name[] = {
  "Yukon XL",
  "Yukon EC Ultra",
  "Yukon EX",
  "Yukon EC",
  "Yukon FE",
  "Yukon FE+",
  "Yukon Supreme",
  "Yukon Ultra 2",
  "Yukon Unknown",
  "Yukon Optima",
};
#endif

//
// Forward declarations
//
STATIC VOID mskc_setup_rambuffer (struct msk_softc *);
STATIC VOID mskc_reset (struct msk_softc *);

EFI_STATUS mskc_attach_if (struct msk_if_softc *, UINTN);
VOID mskc_detach_if (struct msk_if_softc *);

static VOID mskc_tick (IN EFI_EVENT, IN VOID*);
STATIC VOID msk_intr (struct msk_softc *);
static VOID msk_intr_phy (struct msk_if_softc *);
static VOID msk_intr_gmac (struct msk_if_softc *);
static __inline VOID msk_rxput (struct msk_if_softc *);
STATIC INTN msk_handle_events (struct msk_softc *);
static VOID msk_handle_hwerr (struct msk_if_softc *, UINT32);
STATIC VOID msk_intr_hwerr (struct msk_softc *);
static VOID msk_rxeof (struct msk_if_softc *, UINT32, UINT32, INTN);
static VOID msk_txeof (struct msk_if_softc *, INTN);
static EFI_STATUS msk_encap (struct msk_if_softc *, MSK_SYSTEM_BUF *);
STATIC VOID msk_start (struct msk_if_softc *);
STATIC VOID msk_set_prefetch (struct msk_if_softc *, INTN, EFI_PHYSICAL_ADDRESS, UINT32);
static VOID msk_set_rambuffer (struct msk_if_softc *);
static VOID msk_set_tx_stfwd (struct msk_if_softc *);
static EFI_STATUS msk_init (struct msk_if_softc *);
VOID mskc_stop_if (struct msk_if_softc *);
static VOID msk_phy_power (struct msk_softc *, INTN);
INTN msk_phy_readreg (struct msk_if_softc *, INTN);
INTN msk_phy_writereg (struct msk_if_softc *, INTN, INTN);
STATIC EFI_STATUS msk_status_dma_alloc (struct msk_softc *);
STATIC VOID msk_status_dma_free (struct msk_softc *);
static EFI_STATUS msk_txrx_dma_alloc (struct msk_if_softc *);
static VOID msk_txrx_dma_free (struct msk_if_softc *);
static EFI_STATUS msk_init_rx_ring (struct msk_if_softc *);
static VOID msk_init_tx_ring (struct msk_if_softc *);
static __inline VOID msk_discard_rxbuf (struct msk_if_softc *, INTN);
static EFI_STATUS msk_newbuf (struct msk_if_softc *, INTN);

static VOID msk_rxfilter (
    struct msk_if_softc         *sc_if,
    UINT32                      FilterFlags,
    UINTN                       MCastFilterCnt,
    EFI_MAC_ADDRESS             *MCastFilter
    );
static VOID msk_setvlan (struct msk_if_softc *);

static VOID msk_stats_clear (struct msk_if_softc *);
static VOID msk_stats_update (struct msk_if_softc *);
STATIC VOID clear_pci_errors (struct msk_softc *);

EFI_STATUS e1000_probe_and_attach (struct mii_data *, const struct msk_mii_data *, VOID *, VOID **);
VOID e1000phy_tick (VOID *);
VOID e1000phy_mediachg (VOID *);
EFI_STATUS e1000phy_detach (VOID *);

//
// Functions
//

INTN
msk_phy_readreg (
    struct msk_if_softc  *sc_if,
    INTN                 reg
    )
{
  INTN  i;
  INTN  val;
  INTN              port;
  struct msk_softc  *sc;

  sc = sc_if->msk_softc;
  port = sc_if->msk_md.port;

  GMAC_WRITE_2 (sc, port, GM_SMI_CTRL, GM_SMI_CT_PHY_AD (PHY_ADDR_MARV) | GM_SMI_CT_REG_AD (reg) | GM_SMI_CT_OP_RD);

  for (i = 0; i < MSK_TIMEOUT; i++) {
    gBS->Stall (1);
    val = GMAC_READ_2 (sc, port, GM_SMI_CTRL);
    if ((val & GM_SMI_CT_RD_VAL) != 0) {
      val = GMAC_READ_2 (sc, port, GM_SMI_DATA);
      break;
    }
  }

  if (i == MSK_TIMEOUT) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: phy failed to come ready\n"));
    val = 0;
  }

  return (val);
}

INTN
msk_phy_writereg (
    struct msk_if_softc  *sc_if,
    INTN  reg,
    INTN  val
    )
{
  INTN i;
  INTN              port;
  struct msk_softc  *sc;

  sc = sc_if->msk_softc;
  port = sc_if->msk_md.port;

  GMAC_WRITE_2 (sc, port, GM_SMI_DATA, val);
  GMAC_WRITE_2 (sc, port, GM_SMI_CTRL, GM_SMI_CT_PHY_AD (PHY_ADDR_MARV) | GM_SMI_CT_REG_AD (reg));
  for (i = 0; i < MSK_TIMEOUT; i++) {
    gBS->Stall (1);
    if ((GMAC_READ_2 (sc, port, GM_SMI_CTRL) & GM_SMI_CT_BUSY) == 0) {
      break;
    }
  }
  if (i == MSK_TIMEOUT) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: phy write timeout\n"));
  }

  return (0);
}

VOID
msk_miibus_statchg (
    struct msk_if_softc  *sc_if
    )
{
  struct mii_data       *mii;
  UINT32                gmac;
  UINTN                 port;
  struct msk_softc      *sc;

  sc = sc_if->msk_softc;
  port = sc_if->msk_md.port;
  mii = &sc_if->mii_d;
  sc_if->msk_flags &= ~MSK_FLAG_LINK;

  if ((mii->mii_media_status & (IFM_AVALID | IFM_ACTIVE)) == (IFM_AVALID | IFM_ACTIVE)) {

    DEBUG ((EFI_D_NET, "Marvell Yukon: msk_miibus_statchg, phy is active\n"));
    switch (IFM_SUBTYPE (mii->mii_media_active)) {
      case IFM_10_T:
      case IFM_100_TX:
        sc_if->msk_flags |= MSK_FLAG_LINK;
        break;
      case IFM_1000_T:
      case IFM_1000_SX:
      case IFM_1000_LX:
      case IFM_1000_CX:
        if ((sc_if->msk_flags & MSK_FLAG_FASTETHER) == 0) {
          sc_if->msk_flags |= MSK_FLAG_LINK;
        }
        break;
      default:
        break;
    }
  }

  if ((sc_if->msk_flags & MSK_FLAG_LINK) != 0) {
    // Enable Tx FIFO Underrun
    DEBUG ((EFI_D_NET, "Marvell Yukon: msk_miibus_statchg, link up\n"));

    CSR_WRITE_1 (sc, MR_ADDR (port, GMAC_IRQ_MSK), GM_IS_TX_FF_UR | GM_IS_RX_FF_OR);
    //
    // Because mii(4) notify msk (4) that it detected link status
    // change, there is no need to enable automatic
    // speed/flow-control/duplex updates.
    //
    gmac = GM_GPCR_AU_ALL_DIS;
    switch (IFM_SUBTYPE (mii->mii_media_active)) {
      case IFM_1000_SX:
      case IFM_1000_T:
        gmac |= GM_GPCR_SPEED_1000;
        break;
      case IFM_100_TX:
        gmac |= GM_GPCR_SPEED_100;
        break;
      case IFM_10_T:
        break;
    }

    // Disable Rx flow control
    if ((IFM_OPTIONS (mii->mii_media_active) & IFM_FLAG0) == 0) {
      gmac |= GM_GPCR_FC_RX_DIS;
    }
    // Disable Tx flow control
    if ((IFM_OPTIONS (mii->mii_media_active) & IFM_FLAG1) == 0) {
      gmac |= GM_GPCR_FC_TX_DIS;
    }
    if ((IFM_OPTIONS (mii->mii_media_active) & IFM_FDX) != 0) {
      gmac |= GM_GPCR_DUP_FULL;
    } else {
      gmac |= GM_GPCR_FC_RX_DIS | GM_GPCR_FC_TX_DIS;
    }
    gmac |= GM_GPCR_RX_ENA | GM_GPCR_TX_ENA;
    GMAC_WRITE_2 (sc, port, GM_GP_CTRL, gmac);
    // Read again to ensure writing
    GMAC_READ_2 (sc, port, GM_GP_CTRL);
    gmac = GMC_PAUSE_OFF;
    if ((IFM_OPTIONS (mii->mii_media_active) & IFM_FDX) != 0) {
      if ((IFM_OPTIONS (mii->mii_media_active) & IFM_FLAG0) != 0) {
        gmac = GMC_PAUSE_ON;
      }
    }
    CSR_WRITE_4 (sc, MR_ADDR (port, GMAC_CTRL), gmac);

    // Enable PHY interrupt for FIFO underrun/overflow
    msk_phy_writereg (sc_if, PHY_MARV_INT_MASK, PHY_M_IS_FIFO_ERROR);
  } else {
    //
    // Link state changed to down.
    // Disable PHY interrupts.
    //
    DEBUG ((EFI_D_NET, "Marvell Yukon: msk_miibus_statchg, link down\n"));
    msk_phy_writereg (sc_if, PHY_MARV_INT_MASK, 0);
    // Disable Rx/Tx MAC
    gmac = GMAC_READ_2 (sc, port, GM_GP_CTRL);
    if ((GM_GPCR_RX_ENA | GM_GPCR_TX_ENA) != 0) {
      gmac &= ~(GM_GPCR_RX_ENA | GM_GPCR_TX_ENA);
      GMAC_WRITE_2 (sc, port, GM_GP_CTRL, gmac);
      // Read again to ensure writing
      GMAC_READ_2 (sc, port, GM_GP_CTRL);
    }
  }
}

UINT32
ether_crc32_be (
    const UINT8   *buf,
    UINTN         len
    )
{
  UINTN     i;
  UINT32    crc;
  UINT32    carry;
  INTN      bit;
  UINT8     data;

  crc = 0xffffffff; // initial value

  for (i = 0; i < len; i++) {
    for (data = *buf++, bit = 0; bit < 8; bit++, data >>= 1) {
      carry = ((crc & 0x80000000) ? 1 : 0) ^ (data & 0x01);
      crc <<= 1;
      if (carry) {
        crc = (crc ^ ETHER_CRC_POLY_BE) | carry;
      }
    }
  }

  return crc;
}

VOID
mskc_rxfilter (
    struct msk_if_softc         *sc_if,
    UINT32                      FilterFlags,
    UINTN                       MCastFilterCnt,
    EFI_MAC_ADDRESS             *MCastFilter
    )
{
  msk_rxfilter (sc_if, FilterFlags, MCastFilterCnt, MCastFilter);
}

static VOID
msk_rxfilter (
    struct msk_if_softc         *sc_if,
    UINT32                      FilterFlags,
    UINTN                       MCastFilterCnt,
    EFI_MAC_ADDRESS             *MCastFilter
    )
{
  UINT32  mchash[2];
  UINT32  crc;
  UINT16  mode;
  INTN              port;
  struct msk_softc  *sc;

  sc = sc_if->msk_softc;
  port = sc_if->msk_md.port;

  gBS->SetMem (mchash, sizeof (mchash), 0);
  mode = GMAC_READ_2 (sc, port, GM_RX_CTRL);
  if ((FilterFlags & EFI_SIMPLE_NETWORK_RECEIVE_PROMISCUOUS) != 0) {
    mode &= ~(GM_RXCR_UCF_ENA | GM_RXCR_MCF_ENA);
  }
  else if ((FilterFlags & EFI_SIMPLE_NETWORK_RECEIVE_PROMISCUOUS_MULTICAST) != 0) {
    mode |= GM_RXCR_UCF_ENA | GM_RXCR_MCF_ENA;
    mchash[0] = 0xffff;
    mchash[1] = 0xffff;
  } else {
    mode |= GM_RXCR_UCF_ENA;
    while (MCastFilterCnt-- > 0) {
      crc = ether_crc32_be (MCastFilter[MCastFilterCnt].Addr, NET_ETHER_ADDR_LEN);
      /* Just want the 6 least significant bits. */
      crc &= 0x3f;
      /* Set the corresponding bit in the hash table. */
      mchash[crc >> 5] |= 1 << (crc & 0x1f);
    }
    if (mchash[0] != 0 || mchash[1] != 0) {
      mode |= GM_RXCR_MCF_ENA;
    }
  }

  GMAC_WRITE_2 (sc, port, GM_MC_ADDR_H1,  mchash[0]        & 0xffff  );
  GMAC_WRITE_2 (sc, port, GM_MC_ADDR_H2, (mchash[0] >> 16) & 0xffff  );
  GMAC_WRITE_2 (sc, port, GM_MC_ADDR_H3,  mchash[1]        & 0xffff  );
  GMAC_WRITE_2 (sc, port, GM_MC_ADDR_H4, (mchash[1] >> 16) & 0xffff  );
  GMAC_WRITE_2 (sc, port, GM_RX_CTRL,    mode                        );
}

static
VOID
msk_setvlan (
    struct msk_if_softc   *sc_if
    )
{
  //
  // Disable automatic VLAN tagging/stripping
  //
  CSR_WRITE_4 (sc_if->msk_softc, MR_ADDR (sc_if->msk_md.port, RX_GMF_CTRL_T), RX_VLAN_STRIP_OFF);
  CSR_WRITE_4 (sc_if->msk_softc, MR_ADDR (sc_if->msk_md.port, TX_GMF_CTRL_T), TX_VLAN_TAG_OFF);
}

static
EFI_STATUS
msk_init_rx_ring (
    struct msk_if_softc   *sc_if
    )
{
  struct msk_ring_data  *rd;
  struct msk_rxdesc     *rxd;
  INTN                  i;
  INTN                  prod;
  INTN                  nbuf;
  EFI_STATUS            Status;

  sc_if->msk_cdata.msk_rx_cons = 0;
  sc_if->msk_cdata.msk_rx_prod = 0;
  sc_if->msk_cdata.msk_rx_putwm = MSK_PUT_WM;

  rd = &sc_if->msk_rdata;
  gBS->SetMem (rd->msk_rx_ring, MSK_RX_RING_SZ, 0);
  for (i = prod = 0; i < MSK_RX_RING_CNT; i++) {
    rxd = &sc_if->msk_cdata.msk_rxdesc[prod];
    gBS->SetMem (&rxd->rx_m, sizeof (MSK_DMA_BUF), 0);
    rxd->rx_le = &rd->msk_rx_ring[prod];
    MSK_INC (prod, MSK_RX_RING_CNT);
  }
  nbuf = MSK_RX_BUF_CNT;
  prod = 0;

  for (i = 0; i < nbuf; i++) {
     Status = msk_newbuf (sc_if, prod);
     if (EFI_ERROR (Status)) {
       return Status;
     }
     MSK_RX_INC(prod, MSK_RX_RING_CNT);
   }

  // Update prefetch unit.
  sc_if->msk_cdata.msk_rx_prod = MSK_RX_RING_CNT - 1;
  CSR_WRITE_2 (sc_if->msk_softc, Y2_PREF_Q_ADDR (sc_if->msk_rxq, PREF_UNIT_PUT_IDX_REG), sc_if->msk_cdata.msk_rx_prod);

  return EFI_SUCCESS;
}

STATIC
VOID
msk_init_tx_ring (
    struct msk_if_softc   *sc_if
    )
{
  struct msk_ring_data  *rd;
  struct msk_txdesc     *txd;
  INTN                  i;

  sc_if->msk_cdata.msk_tx_prod = 0;
  sc_if->msk_cdata.msk_tx_cons = 0;
  sc_if->msk_cdata.msk_tx_cnt = 0;
  sc_if->msk_cdata.msk_tx_high_addr = 0;

  rd = &sc_if->msk_rdata;
  gBS->SetMem (rd->msk_tx_ring, sizeof (struct msk_tx_desc) * MSK_TX_RING_CNT, 0);
  for (i = 0; i < MSK_TX_RING_CNT; i++) {
    txd = &sc_if->msk_cdata.msk_txdesc[i];
    gBS->SetMem (&(txd->tx_m), sizeof (MSK_DMA_BUF), 0);
    txd->tx_le = &rd->msk_tx_ring[i];
  }
}

static
__inline
VOID
msk_discard_rxbuf (
    struct msk_if_softc   *sc_if,
    INTN                  idx
    )
{
  struct msk_rx_desc  *rx_le;
  struct msk_rxdesc   *rxd;
  MSK_DMA_BUF         *DmaBuffer;

  DEBUG ((EFI_D_NET, "Marvell Yukon: discard rxbuf\n"));

#ifdef MSK_64BIT_DMA
  rxd = &sc_if->msk_cdata.msk_rxdesc[idx];
  rx_le = rxd->rx_le;
  rx_le->msk_control = htole32(OP_ADDR64 | HW_OWNER);
  MSK_INC(idx, MSK_RX_RING_CNT);
#endif

  rxd = &sc_if->msk_cdata.msk_rxdesc[idx];
  DmaBuffer = &rxd->rx_m;
  rx_le = rxd->rx_le;
  rx_le->msk_control = htole32 (DmaBuffer->Length | OP_PACKET | HW_OWNER);
}

static
EFI_STATUS
msk_newbuf (
    IN struct msk_if_softc    *sc_if,
    IN INTN                   idx
    )
{
  struct msk_rx_desc    *rx_le;
  struct msk_rxdesc     *rxd;
  UINTN                 Length;
  VOID                  *Buffer;
  VOID                  *Mapping;
  EFI_PHYSICAL_ADDRESS  PhysAddr;
  EFI_PCI_IO_PROTOCOL   *PciIo;
  EFI_STATUS            Status;

  PciIo = sc_if->msk_softc->PciIo;
  Length = MAX_SUPPORTED_PACKET_SIZE;

  rxd = &sc_if->msk_cdata.msk_rxdesc[idx];

  Status = gBS->AllocatePool (EfiBootServicesData, Length, &Buffer);
  if (EFI_ERROR (Status)) {
    return Status;
  }
  gBS->SetMem (Buffer, Length, 0);

  Status = PciIo->Map (PciIo, EfiPciIoOperationBusMasterWrite, Buffer, &Length, &PhysAddr, &Mapping);
  if (EFI_ERROR (Status)) {
    gBS->FreePool (Buffer);
    return Status;
  }

#ifdef MSK_64BIT_DMA
  rx_le = rxd->rx_le;
  rx_le->msk_addr = htole32(MSK_ADDR_HI(PhysAddr));
  rx_le->msk_control = htole32(OP_ADDR64 | HW_OWNER);
  MSK_INC(idx, MSK_RX_RING_CNT);
  rxd = &sc_if->msk_cdata.msk_rxdesc[idx];
#endif

  rxd->rx_m.DmaMapping = Mapping;
  rxd->rx_m.Buf = Buffer;
  rxd->rx_m.Length = Length;
  rx_le = rxd->rx_le;
  rx_le->msk_addr = htole32 (MSK_ADDR_LO (PhysAddr));
  rx_le->msk_control = htole32 (Length | OP_PACKET | HW_OWNER);

  return EFI_SUCCESS;
}

EFI_STATUS
mskc_probe (
    EFI_PCI_IO_PROTOCOL *PciIo
    )
{
  struct msk_product  *mp;
  UINT16              vendor;
  UINT16              devid;
  UINT32              PciID;
  INTN                i;
  EFI_STATUS          Status;

  Status = PciIo->Pci.Read (
        PciIo,
        EfiPciIoWidthUint32,
        PCI_VENDOR_ID_OFFSET,
        1,
        &PciID
        );
  if (EFI_ERROR (Status)) {
    return EFI_UNSUPPORTED;
  }

  vendor = PciID & 0xFFFF;
  devid = PciID >> 16;
  mp = msk_products;
  for (i = 0; i < sizeof (msk_products)/sizeof (msk_products[0]); i++, mp++) {
    if (vendor == mp->msk_vendorid && devid == mp->msk_deviceid) {
      DEBUG ((EFI_D_NET, "Marvell Yukon: Probe found device %a\n", mp->msk_name));
      return EFI_SUCCESS;
    }
  }
  return EFI_UNSUPPORTED;
}

static
VOID
mskc_setup_rambuffer (
    struct msk_softc  *sc
    )
{
  INTN next;
  INTN i;

  /* Get adapter SRAM size. */
  sc->msk_ramsize = CSR_READ_1 (sc, B2_E_0) * 4;
  DEBUG ((DEBUG_NET, "Marvell Yukon: RAM buffer size : %dKB\n", sc->msk_ramsize));
  if (sc->msk_ramsize == 0) {
    return;
  }

  sc->msk_pflags |= MSK_FLAG_RAMBUF;
  /*
   * Give receiver 2/3 of memory and round down to the multiple
   * of 1024. Tx/Rx RAM buffer size of Yukon II shoud be multiple
   * of 1024.
   */
  sc->msk_rxqsize = (((sc->msk_ramsize * 1024 * 2) / 3) / 1024) * 1024;
  sc->msk_txqsize = (sc->msk_ramsize * 1024) - sc->msk_rxqsize;
  for (i = 0, next = 0; i < sc->msk_num_port; i++) {
    sc->msk_rxqstart[i] = next;
    sc->msk_rxqend[i] = next + sc->msk_rxqsize - 1;
    next = sc->msk_rxqend[i] + 1;
    sc->msk_txqstart[i] = next;
    sc->msk_txqend[i] = next + sc->msk_txqsize - 1;
    next = sc->msk_txqend[i] + 1;
    DEBUG ((EFI_D_NET, "Marvell Yukon: Port %d : Rx Queue %dKB(0x%08x:0x%08x)\n", i,
            sc->msk_rxqsize / 1024, sc->msk_rxqstart[i], sc->msk_rxqend[i]));
    DEBUG ((EFI_D_NET, "Marvell Yukon: Port %d : Tx Queue %dKB(0x%08x:0x%08x)\n", i,
            sc->msk_txqsize / 1024, sc->msk_txqstart[i], sc->msk_txqend[i]));
  }
}

static
VOID
msk_phy_power (
    struct msk_softc  *sc,
    INTN              mode
    )
{
  UINT32  our;
  UINT32  val;
  INTN    i;

  switch (mode) {
    case MSK_PHY_POWERUP:
      // Switch power to VCC (WA for VAUX problem)
      CSR_WRITE_1 (sc, B0_POWER_CTRL, PC_VAUX_ENA | PC_VCC_ENA | PC_VAUX_OFF | PC_VCC_ON);

      // Disable Core Clock Division, set Clock Select to 0
      CSR_WRITE_4 (sc, B2_Y2_CLK_CTRL, Y2_CLK_DIV_DIS);

      val = 0;
      if (sc->msk_hw_id == CHIP_ID_YUKON_XL && sc->msk_hw_rev > CHIP_REV_YU_XL_A1) {
        // Enable bits are inverted
        val = Y2_PCI_CLK_LNK1_DIS | Y2_COR_CLK_LNK1_DIS |
            Y2_CLK_GAT_LNK1_DIS | Y2_PCI_CLK_LNK2_DIS |
            Y2_COR_CLK_LNK2_DIS | Y2_CLK_GAT_LNK2_DIS;
      }
      //
      // Enable PCI & Core Clock, enable clock gating for both Links.
      //
      CSR_WRITE_1 (sc, B2_Y2_CLK_GATE, val);

      val = CSR_PCI_READ_4 (sc, PCI_OUR_REG_1);
      val &= ~(PCI_Y2_PHY1_POWD | PCI_Y2_PHY2_POWD);
      if (sc->msk_hw_id == CHIP_ID_YUKON_XL) {
        if (sc->msk_hw_rev > CHIP_REV_YU_XL_A1) {
          // Deassert Low Power for 1st PHY
          val |= PCI_Y2_PHY1_COMA;
          if (sc->msk_num_port > 1) {
            val |= PCI_Y2_PHY2_COMA;
          }
        }
      }
      // Release PHY from PowerDown/COMA mode
      CSR_PCI_WRITE_4 (sc, PCI_OUR_REG_1, val);

      switch (sc->msk_hw_id) {
        case CHIP_ID_YUKON_EC_U:
        case CHIP_ID_YUKON_EX:
        case CHIP_ID_YUKON_FE_P:
        case CHIP_ID_YUKON_UL_2:
        case CHIP_ID_YUKON_OPT:
          CSR_WRITE_2 (sc, B0_CTST, Y2_HW_WOL_OFF);

          // Enable all clocks
          CSR_PCI_WRITE_4 (sc, PCI_OUR_REG_3, 0);
          our = CSR_PCI_READ_4 (sc, PCI_OUR_REG_4);
          our &= (PCI_FORCE_ASPM_REQUEST | PCI_ASPM_GPHY_LINK_DOWN | PCI_ASPM_INT_FIFO_EMPTY | PCI_ASPM_CLKRUN_REQUEST);
          // Set all bits to 0 except bits 15..12
          CSR_PCI_WRITE_4 (sc, PCI_OUR_REG_4, our);
          our = CSR_PCI_READ_4 (sc, PCI_OUR_REG_5);
          our &= PCI_CTL_TIM_VMAIN_AV_MSK;
          CSR_PCI_WRITE_4 (sc, PCI_OUR_REG_5, our);
          CSR_PCI_WRITE_4 (sc, PCI_CFG_REG_1, 0);
          //
          // Disable status race, workaround for
          // Yukon EC Ultra & Yukon EX.
          //
          val = CSR_READ_4 (sc, B2_GP_IO);
          val |= GLB_GPIO_STAT_RACE_DIS;
          CSR_WRITE_4 (sc, B2_GP_IO, val);
          CSR_READ_4 (sc, B2_GP_IO);
          break;
        default:
          break;
      }
      for (i = 0; i < sc->msk_num_port; i++) {
        CSR_WRITE_2 (sc, MR_ADDR (i, GMAC_LINK_CTRL), GMLC_RST_SET);
        CSR_WRITE_2 (sc, MR_ADDR (i, GMAC_LINK_CTRL), GMLC_RST_CLR);
      }
      break;
    case MSK_PHY_POWERDOWN:
      val = CSR_PCI_READ_4 (sc, PCI_OUR_REG_1);
      val |= PCI_Y2_PHY1_POWD | PCI_Y2_PHY2_POWD;
      if (sc->msk_hw_id == CHIP_ID_YUKON_XL && sc->msk_hw_rev > CHIP_REV_YU_XL_A1) {
        val &= ~PCI_Y2_PHY1_COMA;
        if (sc->msk_num_port > 1) {
          val &= ~PCI_Y2_PHY2_COMA;
        }
      }
      CSR_PCI_WRITE_4 (sc, PCI_OUR_REG_1, val);

      val = Y2_PCI_CLK_LNK1_DIS | Y2_COR_CLK_LNK1_DIS |
          Y2_CLK_GAT_LNK1_DIS | Y2_PCI_CLK_LNK2_DIS |
          Y2_COR_CLK_LNK2_DIS | Y2_CLK_GAT_LNK2_DIS;
      if (sc->msk_hw_id == CHIP_ID_YUKON_XL && sc->msk_hw_rev > CHIP_REV_YU_XL_A1) {
        // Enable bits are inverted
        val = 0;
      }
      //
      // Disable PCI & Core Clock, disable clock gating for
      // both Links.
      //
      CSR_WRITE_1 (sc, B2_Y2_CLK_GATE, val);
      CSR_WRITE_1 (sc, B0_POWER_CTRL, PC_VAUX_ENA | PC_VCC_ENA | PC_VAUX_ON | PC_VCC_OFF);
      break;
    default:
      break;
  }
}

static
VOID
clear_pci_errors (
    struct msk_softc  *sc
    )
{
  EFI_STATUS  Status;
  UINT16      val;
  EFI_PCI_IO_PROTOCOL  *PciIo;

  PciIo = sc->PciIo;

  // Clear all error bits in the PCI status register.
  Status = PciIo->Pci.Read (
        PciIo,
        EfiPciIoWidthUint16,
        PCI_PRIMARY_STATUS_OFFSET,
        1,
        &val
        );
  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: Warning - Reading PCI Status failed: %r", Status));
  }
  CSR_WRITE_1 (sc, B2_TST_CTRL1, TST_CFG_WRITE_ON);
  val |= PCIM_STATUS_PERR | PCIM_STATUS_SERR | PCIM_STATUS_RMABORT |
      PCIM_STATUS_RTABORT | PCIM_STATUS_PERRREPORT;
  Status = PciIo->Pci.Write (
        PciIo,
        EfiPciIoWidthUint16,
        PCI_PRIMARY_STATUS_OFFSET,
        1,
        &val
        );
  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: Warning - Writing PCI Status failed: %r", Status));
  }
  CSR_WRITE_2 (sc, B0_CTST, CS_MRST_CLR);
}

static
VOID
mskc_reset (
    struct msk_softc  *sc
    )
{
  EFI_STATUS            Status;
  EFI_PHYSICAL_ADDRESS  PhysAddr;
  UINT16                status;
  UINT32                val;
  INTN                  i;
  EFI_PCI_IO_PROTOCOL   *PciIo;

  PciIo = sc->PciIo;

  CSR_WRITE_2 (sc, B0_CTST, CS_RST_CLR);

  // Disable ASF
  if (sc->msk_hw_id == CHIP_ID_YUKON_EX) {
    status = CSR_READ_2 (sc, B28_Y2_ASF_HCU_CCSR);
    // Clear AHB bridge & microcontroller reset
    status &= ~(Y2_ASF_HCU_CCSR_AHB_RST | Y2_ASF_HCU_CCSR_CPU_RST_MODE);
    // Clear ASF microcontroller state
    status &= ~ Y2_ASF_HCU_CCSR_UC_STATE_MSK;
    CSR_WRITE_2 (sc, B28_Y2_ASF_HCU_CCSR, status);
  } else {
    CSR_WRITE_1 (sc, B28_Y2_ASF_STAT_CMD, Y2_ASF_RESET);
  }
  CSR_WRITE_2 (sc, B0_CTST, Y2_ASF_DISABLE);

  //
  // Since we disabled ASF, S/W reset is required for Power Management.
  //
  CSR_WRITE_2 (sc, B0_CTST, CS_RST_SET);
  CSR_WRITE_2 (sc, B0_CTST, CS_RST_CLR);

  clear_pci_errors (sc);
  switch (sc->msk_bustype) {
    case MSK_PEX_BUS:
      // Clear all PEX errors
      CSR_PCI_WRITE_4 (sc, PEX_UNC_ERR_STAT, 0xffffffff);
      val = CSR_PCI_READ_4 (sc, PEX_UNC_ERR_STAT);
      if ((val & PEX_RX_OV) != 0) {
        sc->msk_intrmask &= ~Y2_IS_HW_ERR;
        sc->msk_intrhwemask &= ~Y2_IS_PCI_EXP;
      }
      break;
    case MSK_PCI_BUS:
    case MSK_PCIX_BUS:
      // Set Cache Line Size to 2 (8bytes) if configured to 0
      Status = PciIo->Pci.Read (
            PciIo,
            EfiPciIoWidthUint8,
            PCI_CACHELINE_SIZE_OFFSET,
            1,
            &val
            );
      if (EFI_ERROR (Status)) {
        DEBUG ((EFI_D_ERROR, "Marvell Yukon: Warning - Reading PCI cache line size failed: %r", Status));
      }
      if (val == 0) {
        val = 2;
        Status = PciIo->Pci.Write (
              PciIo,
              EfiPciIoWidthUint8,
              PCI_CACHELINE_SIZE_OFFSET,
              1,
              &val
              );
        if (EFI_ERROR (Status)) {
          DEBUG ((EFI_D_ERROR, "Marvell Yukon: Warning - Writing PCI cache line size failed: %r", Status));
        }
      }
      if (sc->msk_bustype == MSK_PCIX_BUS) {
        Status = PciIo->Pci.Read (
              PciIo,
              EfiPciIoWidthUint32,
              PCI_OUR_REG_1,
              1,
              &val
              );
        if (EFI_ERROR (Status)) {
          DEBUG ((EFI_D_ERROR, "Marvell Yukon: Warning - Reading Our Reg 1 failed: %r", Status));
        }
        val |= PCI_CLS_OPT;
        Status = PciIo->Pci.Write (
              PciIo,
              EfiPciIoWidthUint32,
              PCI_OUR_REG_1,
              1,
              &val
              );
        if (EFI_ERROR (Status)) {
          DEBUG ((EFI_D_ERROR, "Marvell Yukon: Warning - Writing Our Reg 1 failed: %r", Status));
        }
      }
      break;
  }

  // Set PHY power state
  msk_phy_power (sc, MSK_PHY_POWERUP);

  // Reset GPHY/GMAC Control
  for (i = 0; i < sc->msk_num_port; i++) {
    // GPHY Control reset
    CSR_WRITE_4 (sc, MR_ADDR (i, GPHY_CTRL), GPC_RST_SET);
    CSR_WRITE_4 (sc, MR_ADDR (i, GPHY_CTRL), GPC_RST_CLR);
    if (sc->msk_hw_id == CHIP_ID_YUKON_UL_2) {
      // Magic value observed under Linux.
      CSR_WRITE_4 (sc, MR_ADDR (i, GPHY_CTRL), 0x00105226);
    }
    // GMAC Control reset
    CSR_WRITE_4 (sc, MR_ADDR (i, GMAC_CTRL), GMC_RST_SET);
    CSR_WRITE_4 (sc, MR_ADDR (i, GMAC_CTRL), GMC_RST_CLR);
    CSR_WRITE_4 (sc, MR_ADDR (i, GMAC_CTRL), GMC_F_LOOPB_OFF);
    if (sc->msk_hw_id == CHIP_ID_YUKON_EX) {
      CSR_WRITE_4 (sc, MR_ADDR (i, GMAC_CTRL), GMC_BYP_MACSECRX_ON | GMC_BYP_MACSECTX_ON | GMC_BYP_RETR_ON);
    }
  }
  if ((sc->msk_hw_id == CHIP_ID_YUKON_OPT) && (sc->msk_hw_rev == 0)) {
    // Disable PCIe PHY powerdown (reg 0x80, bit7)
    CSR_WRITE_4 (sc, Y2_PEX_PHY_DATA, (0x0080 << 16) | 0x0080);
  }
  CSR_WRITE_1 (sc, B2_TST_CTRL1, TST_CFG_WRITE_OFF);

  // LED On
  CSR_WRITE_2 (sc, B0_CTST, Y2_LED_STAT_ON);

  // Enable plug in go
  CSR_WRITE_2 (sc, B0_CTST, Y_ULTRA_2_PLUG_IN_GO_EN);

  // Clear TWSI IRQ
  CSR_WRITE_4 (sc, B2_I2C_IRQ, I2C_CLR_IRQ);

  // Turn off hardware timer
  CSR_WRITE_1 (sc, B2_TI_CTRL, TIM_STOP);
  CSR_WRITE_1 (sc, B2_TI_CTRL, TIM_CLR_IRQ);

  // Turn off descriptor polling
  CSR_WRITE_1 (sc, B28_DPT_CTRL, DPT_STOP);

  // Turn off time stamps
  CSR_WRITE_1 (sc, GMAC_TI_ST_CTRL, GMT_ST_STOP);
  CSR_WRITE_1 (sc, GMAC_TI_ST_CTRL, GMT_ST_CLR_IRQ);

  // Configure timeout values
  for (i = 0; i < sc->msk_num_port; i++) {
    CSR_WRITE_2 (sc, SELECT_RAM_BUFFER (i, B3_RI_CTRL),    RI_RST_SET);
    CSR_WRITE_2 (sc, SELECT_RAM_BUFFER (i, B3_RI_CTRL),    RI_RST_CLR);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_WTO_R1),  MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_WTO_XA1), MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_WTO_XS1), MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_RTO_R1),  MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_RTO_XA1), MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_RTO_XS1), MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_WTO_R2),  MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_WTO_XA2), MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_WTO_XS2), MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_RTO_R2),  MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_RTO_XA2), MSK_RI_TO_53);
    CSR_WRITE_1 (sc, SELECT_RAM_BUFFER (i, B3_RI_RTO_XS2), MSK_RI_TO_53);
  }

  // Disable all interrupts
  CSR_WRITE_4 (sc, B0_HWE_IMSK, 0);
  CSR_READ_4 (sc, B0_HWE_IMSK);
  CSR_WRITE_4 (sc, B0_IMSK, 0);
  CSR_READ_4 (sc, B0_IMSK);

  // Clear status list
  gBS->SetMem (sc->msk_stat_ring, sizeof (struct msk_stat_desc) * MSK_STAT_RING_CNT, 0);
  sc->msk_stat_cons = 0;
  CSR_WRITE_4 (sc, STAT_CTRL, SC_STAT_RST_SET);
  CSR_WRITE_4 (sc, STAT_CTRL, SC_STAT_RST_CLR);

  // Set the status list base address
  PhysAddr = sc->msk_stat_ring_paddr;
  CSR_WRITE_4 (sc, STAT_LIST_ADDR_LO, MSK_ADDR_LO (PhysAddr));
  CSR_WRITE_4 (sc, STAT_LIST_ADDR_HI, MSK_ADDR_HI (PhysAddr));

  // Set the status list last index
  CSR_WRITE_2 (sc, STAT_LAST_IDX, MSK_STAT_RING_CNT - 1);
  if ((sc->msk_hw_id == CHIP_ID_YUKON_EC) && (sc->msk_hw_rev == CHIP_REV_YU_EC_A1)) {
    // WA for dev. #4.3
    CSR_WRITE_2 (sc, STAT_TX_IDX_TH, ST_TXTH_IDX_MASK);
    // WA for dev. #4.18
    CSR_WRITE_1 (sc, STAT_FIFO_WM, 0x21);
    CSR_WRITE_1 (sc, STAT_FIFO_ISR_WM, 0x07);
  } else {
    CSR_WRITE_2 (sc, STAT_TX_IDX_TH, 0x0a);
    CSR_WRITE_1 (sc, STAT_FIFO_WM, 0x10);
    if ((sc->msk_hw_id == CHIP_ID_YUKON_XL) && (sc->msk_hw_rev == CHIP_REV_YU_XL_A0)) {
      CSR_WRITE_1 (sc, STAT_FIFO_ISR_WM, 0x04);
    } else {
      CSR_WRITE_1 (sc, STAT_FIFO_ISR_WM, 0x10);
    }
    CSR_WRITE_4 (sc, STAT_ISR_TIMER_INI, 0x0190);
  }
  //
  // Use default value for STAT_ISR_TIMER_INI, STAT_LEV_TIMER_INI.
  //
  CSR_WRITE_4 (sc, STAT_TX_TIMER_INI, MSK_USECS (sc, 1000));

  // Enable status unit
  CSR_WRITE_4 (sc, STAT_CTRL, SC_STAT_OP_ON);

  CSR_WRITE_1 (sc, STAT_TX_TIMER_CTRL, TIM_START);
  CSR_WRITE_1 (sc, STAT_LEV_TIMER_CTRL, TIM_START);
  CSR_WRITE_1 (sc, STAT_ISR_TIMER_CTRL, TIM_START);
}

EFI_STATUS
mskc_attach_if (
    struct msk_if_softc   *sc_if,
    UINTN                 Port
    )
{
  INTN                  i;
  EFI_STATUS            Status;

  sc_if->msk_md.port = Port;
  sc_if->msk_flags = sc_if->msk_softc->msk_pflags;

  // Setup Tx/Rx queue register offsets
  if (Port == MSK_PORT_A) {
    sc_if->msk_txq = Q_XA1;
    sc_if->msk_txsq = Q_XS1;
    sc_if->msk_rxq = Q_R1;
  } else {
    sc_if->msk_txq = Q_XA2;
    sc_if->msk_txsq = Q_XS2;
    sc_if->msk_rxq = Q_R2;
  }

  Status = msk_txrx_dma_alloc (sc_if);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  /*
   * Get station address for this interface. Note that
   * dual port cards actually come with three station
   * addresses: one for each port, plus an extra. The
   * extra one is used by the SysKonnect driver software
   * as a 'virtual' station address for when both ports
   * are operating in failover mode. Currently we don't
   * use this extra address.
   */
  for (i = 0; i < NET_ETHER_ADDR_LEN; i++) {
    sc_if->MacAddress.Addr[i] = CSR_READ_1 (sc_if->msk_softc, B2_MAC_1 + (Port * 8) + i);
  }

  DEBUG ((EFI_D_NET,"Marvell Yukon: Mac Address %02x:%02x:%02x:%02x:%02x:%02x\n",
          sc_if->MacAddress.Addr[0], sc_if->MacAddress.Addr[1], sc_if->MacAddress.Addr[2],
      sc_if->MacAddress.Addr[3], sc_if->MacAddress.Addr[4], sc_if->MacAddress.Addr[5]));

  Status = e1000_probe_and_attach (&sc_if->mii_d, &sc_if->msk_md, sc_if, &sc_if->phy_softc);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  InitializeListHead (&sc_if->TransmitQueueHead);
  InitializeListHead (&sc_if->TransmitFreeQueueHead);
  InitializeListHead (&sc_if->ReceiveQueueHead);
  sc_if->active = TRUE;

  return (Status);
}

/*
 * Attach the interface. Allocate softc structures, do ifmedia
 * setup and ethernet/BPF attach.
 */
EFI_STATUS
mskc_attach (
    IN  EFI_PCI_IO_PROTOCOL   *PciIo,
    OUT struct msk_softc      **ScData
    )
{
  struct msk_mii_data   *mmd;
  UINT64                Supports;
  UINT8                 *PciBarResources;
  EFI_STATUS            Status;
  struct msk_if_softc   *ScIf;
  struct msk_softc      *sc;

  Status = gBS->AllocatePool (EfiBootServicesData,
                              sizeof (struct msk_softc),
                              (VOID**) &sc);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  //
  // Save original PCI attributes
  //
  gBS->SetMem (sc, sizeof (struct msk_softc), 0);
  sc->PciIo = PciIo;
  Status = PciIo->Attributes (
        PciIo,
        EfiPciIoAttributeOperationGet,
        0,
        &sc->OriginalPciAttributes
        );
  if (EFI_ERROR (Status)) {
    gBS->FreePool (sc);
    return Status;
  }

  Status = PciIo->Attributes (
        PciIo,
        EfiPciIoAttributeOperationSupported,
        0,
        &Supports
        );
  if (!EFI_ERROR (Status)) {
    Supports &= EFI_PCI_DEVICE_ENABLE;
    Status = PciIo->Attributes (
          PciIo,
          EfiPciIoAttributeOperationEnable,
          Supports | EFI_PCI_IO_ATTRIBUTE_DUAL_ADDRESS_CYCLE,
          NULL
          );
  }
  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: Failed to enable NIC controller\n"));
    goto RESTORE_PCI_ATTRIBS;
  }

  Status = PciIo->GetBarAttributes (PciIo, 0, &Supports, (VOID**)&PciBarResources);
  if (!EFI_ERROR (Status) && (((EFI_ACPI_ADDRESS_SPACE_DESCRIPTOR *)PciBarResources)->Desc == ACPI_ADDRESS_SPACE_DESCRIPTOR)) {
    if (((EFI_ACPI_ADDRESS_SPACE_DESCRIPTOR *)PciBarResources)->ResType == ACPI_ADDRESS_SPACE_TYPE_MEM) {
      if (!(((EFI_ACPI_ADDRESS_SPACE_DESCRIPTOR *)PciBarResources)->SpecificFlag & ACPI_SPECFLAG_PREFETCHABLE)) {
        sc->RegBase = ((EFI_ACPI_ADDRESS_SPACE_DESCRIPTOR *)PciBarResources)->AddrRangeMin;
        // Should assert that Bar is 32 bits wide
        DEBUG ((DEBUG_NET, "Marvell Yukon: GlobalRegistersBase = 0x%x\n", sc->RegBase));
      } else {
        Status = EFI_NOT_FOUND;
      }
    } else {
      Status = EFI_NOT_FOUND;
    }
  }
  if (EFI_ERROR (Status)) {
    goto RESTORE_PCI_ATTRIBS;
  }

  // Clear Software Reset
  CSR_WRITE_2 (sc, B0_CTST, CS_RST_CLR);

  // Get Hardware ID & Revision
  sc->msk_hw_id = CSR_READ_1 (sc, B2_CHIP_ID);
  sc->msk_hw_rev = (CSR_READ_1 (sc, B2_MAC_CFG) >> 4) & 0x0f;

  // Bail out if chip is not recognized
  if (sc->msk_hw_id < CHIP_ID_YUKON_XL ||
      sc->msk_hw_id > CHIP_ID_YUKON_OPT ||
      sc->msk_hw_id == CHIP_ID_YUKON_SUPR ||
      sc->msk_hw_id == CHIP_ID_YUKON_UNKNOWN) {
    DEBUG ((DEBUG_NET, "Marvell Yukon: unknown device: id=0x%02x, rev=0x%02x\n", sc->msk_hw_id, sc->msk_hw_rev));
    Status = EFI_DEVICE_ERROR;
    goto RESTORE_PCI_ATTRIBS;
  }
  DEBUG ((EFI_D_NET, "Marvell Yukon: Marvell Technology Group Ltd. %a Id:0x%02x Rev:0x%02x\n",
          model_name[sc->msk_hw_id - CHIP_ID_YUKON_XL], sc->msk_hw_id, sc->msk_hw_rev));

  sc->msk_process_limit = MSK_PROC_DEFAULT;
  sc->msk_int_holdoff = MSK_INT_HOLDOFF_DEFAULT;

  // Check if MAC address is valid
  if ((CSR_READ_4 (sc, B2_MAC_1) == 0) && (CSR_READ_4 (sc, B2_MAC_1+4) == 0)) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: MAC address is invalid (00:00:00:00:00:00)\n"));
  }

  // Soft reset
  CSR_WRITE_2 (sc, B0_CTST, CS_RST_SET);
  CSR_WRITE_2 (sc, B0_CTST, CS_RST_CLR);
  sc->msk_pmd = CSR_READ_1 (sc, B2_PMD_TYP);

  // Check number of MACs
  sc->msk_num_port = 1;
  if ((CSR_READ_1 (sc, B2_Y2_HW_RES) & CFG_DUAL_MAC_MSK) == CFG_DUAL_MAC_MSK) {
    if (!(CSR_READ_1 (sc, B2_Y2_CLK_GATE) & Y2_STATUS_LNK2_INAC)) {
      sc->msk_num_port++;
    }
  }

  /* Check bus type. */
  sc->msk_bustype = MSK_PEX_BUS; /* Only support PCI Express */
  sc->msk_expcap = 1;

  switch (sc->msk_hw_id) {
    case CHIP_ID_YUKON_EC:
      sc->msk_clock = 125;  /* 125 MHz */
      sc->msk_pflags |= MSK_FLAG_JUMBO;
      break;
    case CHIP_ID_YUKON_EC_U:
      sc->msk_clock = 125;  /* 125 MHz */
      sc->msk_pflags |= MSK_FLAG_JUMBO | MSK_FLAG_JUMBO_NOCSUM;
      break;
    case CHIP_ID_YUKON_EX:
      sc->msk_clock = 125;  /* 125 MHz */
      sc->msk_pflags |= MSK_FLAG_JUMBO | MSK_FLAG_DESCV2 | MSK_FLAG_AUTOTX_CSUM;
      /*
     * Yukon Extreme seems to have silicon bug for
     * automatic Tx checksum calculation capability.
     */
      if (sc->msk_hw_rev == CHIP_REV_YU_EX_B0) {
        sc->msk_pflags &= ~MSK_FLAG_AUTOTX_CSUM;
      }
      /*
     * Yukon Extreme A0 could not use store-and-forward
     * for jumbo frames, so disable Tx checksum
     * offloading for jumbo frames.
     */
      if (sc->msk_hw_rev == CHIP_REV_YU_EX_A0) {
        sc->msk_pflags |= MSK_FLAG_JUMBO_NOCSUM;
      }
      break;
    case CHIP_ID_YUKON_FE:
      sc->msk_clock = 100;  /* 100 MHz */
      sc->msk_pflags |= MSK_FLAG_FASTETHER;
      break;
    case CHIP_ID_YUKON_FE_P:
      sc->msk_clock = 50;  /* 50 MHz */
      sc->msk_pflags |= MSK_FLAG_FASTETHER | MSK_FLAG_DESCV2 | MSK_FLAG_AUTOTX_CSUM;
      if (sc->msk_hw_rev == CHIP_REV_YU_FE_P_A0) {
        /*
       * XXX
       * FE+ A0 has status LE writeback bug so msk (4)
       * does not rely on status word of received frame
       * in msk_rxeof () which in turn disables all
       * hardware assistance bits reported by the status
       * word as well as validity of the recevied frame.
       * Just pass received frames to upper stack with
       * minimal test and let upper stack handle them.
       */
        sc->msk_pflags |= MSK_FLAG_NOHWVLAN | MSK_FLAG_NORXCHK | MSK_FLAG_NORX_CSUM;
      }
      break;
    case CHIP_ID_YUKON_XL:
      sc->msk_clock = 156;  /* 156 MHz */
      sc->msk_pflags |= MSK_FLAG_JUMBO;
      break;
    case CHIP_ID_YUKON_UL_2:
      sc->msk_clock = 125;  /* 125 MHz */
      sc->msk_pflags |= MSK_FLAG_JUMBO;
      break;
    case CHIP_ID_YUKON_OPT:
      sc->msk_clock = 125;  /* 125 MHz */
      sc->msk_pflags |= MSK_FLAG_JUMBO | MSK_FLAG_DESCV2;
      break;
    default:
      sc->msk_clock = 156;  /* 156 MHz */
      break;
  }

  Status = msk_status_dma_alloc (sc);
  if (EFI_ERROR (Status)) {
    goto fail;
  }

  // Set base interrupt mask
  sc->msk_intrmask = Y2_IS_HW_ERR | Y2_IS_STAT_BMU;
  sc->msk_intrhwemask = Y2_IS_TIST_OV | Y2_IS_MST_ERR | Y2_IS_IRQ_STAT | Y2_IS_PCI_EXP | Y2_IS_PCI_NEXP;

  // Reset the adapter
  mskc_reset (sc);

  mskc_setup_rambuffer (sc);

  Status = gBS->AllocatePool (EfiBootServicesData,
                              sizeof (struct msk_if_softc),
                              (VOID**) &ScIf);
  if (EFI_ERROR (Status)) {
    goto fail;
  }
  gBS->SetMem (ScIf, sizeof (struct msk_if_softc), 0);
  ScIf->msk_softc = sc;
  sc->msk_if[MSK_PORT_A] = ScIf;
  Status = mskc_attach_if (sc->msk_if[MSK_PORT_A], MSK_PORT_A);
  if (EFI_ERROR (Status)) {
    goto fail;
  }

  mmd = &ScIf->msk_md;
  mmd->port = MSK_PORT_A;
  mmd->pmd = sc->msk_pmd;
  if (sc->msk_pmd == 'L' || sc->msk_pmd == 'S' || sc->msk_pmd == 'P') {
    mmd->mii_flags |= MIIF_HAVEFIBER;
  }

  if (sc->msk_num_port > 1) {
    Status = gBS->AllocatePool (EfiBootServicesData,
                                sizeof (struct msk_if_softc),
                                (VOID**) &ScIf);
    if (EFI_ERROR (Status)) {
      goto fail;
    }
    gBS->SetMem (ScIf, sizeof (struct msk_if_softc), 0);
    ScIf->msk_softc = sc;
    sc->msk_if[MSK_PORT_B] = ScIf;
    Status = mskc_attach_if (sc->msk_if[MSK_PORT_B], MSK_PORT_B);
    if (EFI_ERROR (Status)) {
      goto fail;
    }

    mmd = &ScIf->msk_md;
    mmd->port = MSK_PORT_B;
    mmd->pmd = sc->msk_pmd;
    if (sc->msk_pmd == 'L' || sc->msk_pmd == 'S' || sc->msk_pmd == 'P') {
      mmd->mii_flags |= MIIF_HAVEFIBER;
    }
  }

  // Return new msk_softc structure
  *ScData = sc;

  // Create timer for tick
  Status = gBS->CreateEvent (
        EVT_NOTIFY_SIGNAL | EVT_TIMER,
        TPL_CALLBACK,
        mskc_tick,
        (VOID *)sc,
        &sc->Timer
        );
  if (EFI_ERROR (Status)) {
    goto fail;
  }

  Status = gBS->SetTimer (sc->Timer, TimerPeriodic, TICKS_PER_SECOND);
  if (EFI_ERROR (Status)) {
    goto fail;
  }

fail:
  if (EFI_ERROR (Status)) {
    mskc_detach (sc);
  }

  return (Status);

RESTORE_PCI_ATTRIBS:
  //
  // Restore original PCI attributes
  //
  PciIo->Attributes (
        PciIo,
        EfiPciIoAttributeOperationSet,
        sc->OriginalPciAttributes,
        NULL
        );
  gBS->FreePool (sc);
  return Status;
}

/*
 * Shutdown hardware and free up resources. This can be called any
 * time after the mutex has been initialized. It is called in both
 * the error case in attach and the normal detach case so it needs
 * to be careful about only freeing resources that have actually been
 * allocated.
 */
VOID
mskc_detach_if (
    struct msk_if_softc  *sc_if
    )
{
  if (sc_if->active) {
    mskc_stop_if (sc_if);
    msk_txrx_dma_free (sc_if);
    e1000phy_detach (sc_if->phy_softc);
    sc_if->phy_softc = NULL;
    sc_if->active = FALSE;
  }
}

VOID
mskc_detach (
    struct msk_softc  *sc
    )
{
  EFI_TPL OldTpl;
  EFI_PCI_IO_PROTOCOL *PciIo;

  if (sc == NULL) {
    return;
  }

  OldTpl = gBS->RaiseTPL (TPL_NOTIFY);

  PciIo = sc->PciIo;

  if (sc->msk_if[MSK_PORT_A] != NULL) {
    mskc_detach_if (sc->msk_if[MSK_PORT_A]);
    gBS->FreePool (sc->msk_if[MSK_PORT_A]);
    sc->msk_if[MSK_PORT_A] = NULL;
  }
  if (sc->msk_if[MSK_PORT_B] != NULL) {
    mskc_detach_if (sc->msk_if[MSK_PORT_B]);
    gBS->FreePool (sc->msk_if[MSK_PORT_B]);
    sc->msk_if[MSK_PORT_B] = NULL;
  }

  /* Disable all interrupts. */
  CSR_WRITE_4 (sc, B0_IMSK, 0);
  CSR_READ_4 (sc, B0_IMSK);
  CSR_WRITE_4 (sc, B0_HWE_IMSK, 0);
  CSR_READ_4 (sc, B0_HWE_IMSK);

  // LED Off.
  CSR_WRITE_2 (sc, B0_CTST, Y2_LED_STAT_OFF);

  // Put hardware reset.
  CSR_WRITE_2 (sc, B0_CTST, CS_RST_SET);

  msk_status_dma_free (sc);

  if (sc->Timer != NULL) {
    gBS->SetTimer (sc->Timer, TimerCancel, 0);
    gBS->CloseEvent (sc->Timer);

    sc->Timer = NULL;
  }
  //
  // Restore original PCI attributes
  //
  PciIo->Attributes (
        PciIo,
        EfiPciIoAttributeOperationSet,
        sc->OriginalPciAttributes,
        NULL
        );

  gBS->RestoreTPL (OldTpl);
}

/* Create status DMA region. */
static
EFI_STATUS
msk_status_dma_alloc (
    struct msk_softc  *sc
    )
{
  EFI_STATUS  Status;
  UINTN       Length;
  EFI_PCI_IO_PROTOCOL *PciIo;

  PciIo = sc->PciIo;

  Status = PciIo->AllocateBuffer (PciIo, AllocateAnyPages, EfiBootServicesData,
                                   EFI_SIZE_TO_PAGES (MSK_STAT_RING_SZ), (VOID**)&sc->msk_stat_ring, 0);

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: failed to allocate DMA'able memory for status ring\n"));
    return Status;
  }
  ASSERT (sc->msk_stat_ring != NULL);

  Length = MSK_STAT_RING_SZ;
  Status = PciIo->Map (PciIo, EfiPciIoOperationBusMasterCommonBuffer, sc->msk_stat_ring,
                        &Length, &sc->msk_stat_ring_paddr, &sc->msk_stat_map);
  ASSERT (Length == MSK_STAT_RING_SZ);

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: failed to map DMA'able memory for status ring\n"));
  }

  return Status;
}

static
VOID
msk_status_dma_free (
    struct msk_softc  *sc
    )
{
  EFI_PCI_IO_PROTOCOL *PciIo;

  PciIo = sc->PciIo;

  if (sc->msk_stat_map) {
    PciIo->Unmap (PciIo, sc->msk_stat_map);
    if (sc->msk_stat_ring) {
      PciIo->FreeBuffer (PciIo, EFI_SIZE_TO_PAGES (MSK_STAT_RING_SZ), sc->msk_stat_ring);
      sc->msk_stat_ring = NULL;
    }
    sc->msk_stat_map = NULL;
  }
}

static
EFI_STATUS
msk_txrx_dma_alloc (
    struct msk_if_softc   *sc_if
    )
{
  struct msk_txdesc   *txd;
  struct msk_rxdesc   *rxd;
  INTN                i;
  UINTN               Length;
  EFI_STATUS          Status;
  EFI_PCI_IO_PROTOCOL *PciIo;

  PciIo = sc_if->msk_softc->PciIo;

  Status = PciIo->AllocateBuffer (PciIo, AllocateAnyPages, EfiBootServicesData,
                                   EFI_SIZE_TO_PAGES (MSK_TX_RING_SZ), (VOID**)&sc_if->msk_rdata.msk_tx_ring, 0);

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: failed to allocate DMA'able memory for Tx ring\n"));
    goto fail;
  }
  ASSERT (sc_if->msk_rdata.msk_tx_ring != NULL);

  Length = MSK_TX_RING_SZ;
  Status = PciIo->Map (PciIo, EfiPciIoOperationBusMasterCommonBuffer, sc_if->msk_rdata.msk_tx_ring,
                        &Length, &sc_if->msk_rdata.msk_tx_ring_paddr, &sc_if->msk_cdata.msk_tx_ring_map);

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: failed to map DMA'able memory for Tx ring\n"));
    goto fail;
  }
  ASSERT (Length == MSK_TX_RING_SZ);

  Status = PciIo->AllocateBuffer (PciIo, AllocateAnyPages, EfiBootServicesData,
                                   EFI_SIZE_TO_PAGES (MSK_RX_RING_SZ), (VOID**)&sc_if->msk_rdata.msk_rx_ring, 0);

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: failed to allocate DMA'able memory for Rx ring\n"));
    goto fail;
  }
  ASSERT (sc_if->msk_rdata.msk_rx_ring != NULL);

  Length = MSK_RX_RING_SZ;
  Status = PciIo->Map (PciIo, EfiPciIoOperationBusMasterCommonBuffer, sc_if->msk_rdata.msk_rx_ring,
                        &Length, &sc_if->msk_rdata.msk_rx_ring_paddr, &sc_if->msk_cdata.msk_rx_ring_map);

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: failed to map DMA'able memory for Rx ring\n"));
    goto fail;
  }
  ASSERT (Length == MSK_RX_RING_SZ);

  // Create DMA maps for Tx buffers.
  for (i = 0; i < MSK_TX_RING_CNT; i++) {
    txd = &sc_if->msk_cdata.msk_txdesc[i];
    gBS->SetMem (&(txd->tx_m), sizeof (MSK_DMA_BUF), 0);
  }
  // Create DMA maps for Rx buffers.
  for (i = 0; i < MSK_RX_RING_CNT; i++) {
    rxd = &sc_if->msk_cdata.msk_rxdesc[i];
    gBS->SetMem (&(rxd->rx_m), sizeof (MSK_DMA_BUF), 0);
  }

fail:
  return (Status);
}

static
VOID
msk_txrx_dma_free (
    struct msk_if_softc *sc_if
    )
{
  struct msk_txdesc   *txd;
  struct msk_rxdesc   *rxd;
  INTN                i;
  EFI_PCI_IO_PROTOCOL *PciIo;

  PciIo = sc_if->msk_softc->PciIo;

  // Tx ring
  if (sc_if->msk_cdata.msk_tx_ring_map) {
    PciIo->Unmap (PciIo, sc_if->msk_cdata.msk_tx_ring_map);
    if (sc_if->msk_rdata.msk_tx_ring) {
      PciIo->FreeBuffer (PciIo, EFI_SIZE_TO_PAGES (MSK_TX_RING_SZ), sc_if->msk_rdata.msk_tx_ring);
      sc_if->msk_rdata.msk_tx_ring = NULL;
    }
    sc_if->msk_cdata.msk_tx_ring_map = NULL;
  }

  // Rx ring
  if (sc_if->msk_cdata.msk_rx_ring_map) {
    PciIo->Unmap (PciIo, sc_if->msk_cdata.msk_rx_ring_map);
    if (sc_if->msk_rdata.msk_rx_ring) {
      PciIo->FreeBuffer (PciIo, EFI_SIZE_TO_PAGES (MSK_RX_RING_SZ), sc_if->msk_rdata.msk_rx_ring);
      sc_if->msk_rdata.msk_rx_ring = NULL;
    }
    sc_if->msk_cdata.msk_rx_ring_map = NULL;
  }

  // Tx buffers
  for (i = 0; i < MSK_TX_RING_CNT; i++) {
    txd = &sc_if->msk_cdata.msk_txdesc[i];
    if (txd->tx_m.DmaMapping) {
      PciIo->Unmap (PciIo, txd->tx_m.DmaMapping);
      gBS->SetMem (&(txd->tx_m), sizeof (MSK_DMA_BUF), 0);
      // We don't own the transmit buffers so don't free them
    }
  }
  // Rx buffers
  for (i = 0; i < MSK_RX_RING_CNT; i++) {
    rxd = &sc_if->msk_cdata.msk_rxdesc[i];
    if (rxd->rx_m.DmaMapping) {
      PciIo->Unmap (PciIo, rxd->rx_m.DmaMapping);
      // Free Rx buffers as we own these
      if(rxd->rx_m.Buf != NULL) {
        gBS->FreePool (rxd->rx_m.Buf);
        rxd->rx_m.Buf = NULL;
      }
      gBS->SetMem (&(rxd->rx_m), sizeof (MSK_DMA_BUF), 0);
    }
  }
}

static
EFI_STATUS
msk_encap (
    struct msk_if_softc   *sc_if,
    MSK_SYSTEM_BUF        *m_head
    )
{
  struct msk_txdesc     *txd;
  struct msk_txdesc     *txd_last;
  struct msk_tx_desc    *tx_le;
  VOID                  *Mapping;
  EFI_PHYSICAL_ADDRESS  BusPhysAddr;
  UINTN                 BusLength;
  UINT32                control;
  UINT32                prod;
  UINT32                si;
  EFI_STATUS            Status;
  EFI_PCI_IO_PROTOCOL   *PciIo;

  PciIo = sc_if->msk_softc->PciIo;
  prod = sc_if->msk_cdata.msk_tx_prod;
  txd = &sc_if->msk_cdata.msk_txdesc[prod];
  txd_last = txd;
  BusLength = m_head->Length;
  Status = PciIo->Map (PciIo, EfiPciIoOperationBusMasterRead, m_head->Buf,
                        &BusLength, &BusPhysAddr, &txd->tx_m.DmaMapping);

  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: failed to map DMA'able memory for Tx buffer\n"));
    return Status;
  }
  ASSERT (BusLength == m_head->Length);

  control = 0;

#ifdef MSK_64BIT_DMA
  if (MSK_ADDR_HI(BusPhysAddr) !=
    sc_if->msk_cdata.msk_tx_high_addr) {
      sc_if->msk_cdata.msk_tx_high_addr =
          MSK_ADDR_HI(BusPhysAddr);
      tx_le = &sc_if->msk_rdata.msk_tx_ring[prod];
      tx_le->msk_addr = htole32(MSK_ADDR_HI(BusPhysAddr));
      tx_le->msk_control = htole32(OP_ADDR64 | HW_OWNER);
      sc_if->msk_cdata.msk_tx_cnt++;
      MSK_INC(prod, MSK_TX_RING_CNT);
  }
#endif

  si = prod;
  tx_le = &sc_if->msk_rdata.msk_tx_ring[prod];
  tx_le->msk_addr = htole32 (MSK_ADDR_LO (BusPhysAddr));
  tx_le->msk_control = htole32 (BusLength | control | OP_PACKET);
  sc_if->msk_cdata.msk_tx_cnt++;
  MSK_INC (prod, MSK_TX_RING_CNT);

  // Update producer index
  sc_if->msk_cdata.msk_tx_prod = prod;

  // Set EOP on the last descriptor
  prod = (prod + MSK_TX_RING_CNT - 1) % MSK_TX_RING_CNT;
  tx_le = &sc_if->msk_rdata.msk_tx_ring[prod];
  tx_le->msk_control |= htole32 (EOP);

  // Turn the first descriptor ownership to hardware
  tx_le = &sc_if->msk_rdata.msk_tx_ring[si];
  tx_le->msk_control |= htole32 (HW_OWNER);

  txd = &sc_if->msk_cdata.msk_txdesc[prod];
  Mapping = txd_last->tx_m.DmaMapping;
  txd_last->tx_m.DmaMapping = txd->tx_m.DmaMapping;
  txd->tx_m.DmaMapping = Mapping;
  txd->tx_m.Buf = m_head->Buf;
  txd->tx_m.Length = m_head->Length;

  return EFI_SUCCESS;
}

EFI_STATUS
mskc_transmit (
    struct msk_if_softc  *sc_if,
    UINTN   BufferSize,
    VOID    *Buffer
    )
{
  MSK_LINKED_SYSTEM_BUF   *LinkedSystemBuf;
  EFI_STATUS               Status;

  Status = gBS->AllocatePool (EfiBootServicesData,
                              sizeof (MSK_LINKED_SYSTEM_BUF),
                              (VOID**) &LinkedSystemBuf);
  if (EFI_ERROR (Status)) {
    return Status;
  }
  gBS->SetMem (LinkedSystemBuf, sizeof (MSK_LINKED_SYSTEM_BUF), 0);
  LinkedSystemBuf->Signature = TX_MBUF_SIGNATURE;
  //
  // Add the passed Buffer to the transmit queue. Don't copy.
  //
  LinkedSystemBuf->SystemBuf.Buf = Buffer;
  LinkedSystemBuf->SystemBuf.Length = BufferSize;
  InsertTailList (&sc_if->TransmitQueueHead, &LinkedSystemBuf->Link);
  msk_start (sc_if);
  return EFI_SUCCESS;
}

void
mskc_getstatus (
    struct msk_if_softc  *sc_if,
    OUT UINT32                     *InterruptStatus, OPTIONAL
    OUT VOID                       **TxBuf           OPTIONAL
    )
{
  MSK_LINKED_SYSTEM_BUF *m_head;

  // Interrupt status is not read from the device when InterruptStatus is NULL
  if (InterruptStatus != NULL) {
    // Check the interrupt lines
    msk_intr (sc_if->msk_softc);
  }

  // The transmit buffer status is not read when TxBuf is NULL
  if (TxBuf != NULL) {
    *((UINT8 **) TxBuf) = (UINT8 *) 0;
    if (!IsListEmpty (&sc_if->TransmitFreeQueueHead))
    {
      m_head = CR (GetFirstNode (&sc_if->TransmitFreeQueueHead), MSK_LINKED_SYSTEM_BUF, Link, TX_MBUF_SIGNATURE);
      if(m_head != NULL) {
        *TxBuf = m_head->SystemBuf.Buf;
        RemoveEntryList (&m_head->Link);
        gBS->FreePool (m_head);
      }
    }
  }
}

static
VOID
msk_start (
    struct msk_if_softc  *sc_if
    )
{
  EFI_STATUS            Status;
  MSK_LINKED_SYSTEM_BUF *m_head;
  INTN                  enq;

  for (enq = 0; !IsListEmpty (&sc_if->TransmitQueueHead) &&
       sc_if->msk_cdata.msk_tx_cnt < (MSK_TX_RING_CNT - MSK_RESERVED_TX_DESC_CNT); )
  {

    m_head = CR (GetFirstNode (&sc_if->TransmitQueueHead), MSK_LINKED_SYSTEM_BUF, Link, TX_MBUF_SIGNATURE);
    if (m_head == NULL) {
      break;
    }
    //
    // Pack the data into the transmit ring. If we
    // don't have room, set the OACTIVE flag and wait
    // for the NIC to drain the ring.
    //
    Status = msk_encap (sc_if, &m_head->SystemBuf);
    if (EFI_ERROR (Status)) {
      break;
    }

    RemoveEntryList (&m_head->Link);
    InsertTailList (&sc_if->TransmitFreeQueueHead, &m_head->Link);
    enq++;
  }

  if (enq > 0) {
    // Transmit
    CSR_WRITE_2 (sc_if->msk_softc, Y2_PREF_Q_ADDR (sc_if->msk_txq, PREF_UNIT_PUT_IDX_REG),
                 sc_if->msk_cdata.msk_tx_prod);
  }
}

VOID
mskc_shutdown (
    struct msk_softc  *sc
    )
{
  INTN i;
  EFI_TPL OldTpl;

  OldTpl = gBS->RaiseTPL (TPL_NOTIFY);

  for (i = 0; i < sc->msk_num_port; i++) {
    if (sc->msk_if[i] != NULL) {
      mskc_stop_if (sc->msk_if[i]);
    }
  }
  gBS->SetTimer (sc->Timer, TimerCancel, 0);

  /* Put hardware reset. */
  CSR_WRITE_2 (sc, B0_CTST, CS_RST_SET);

  gBS->RestoreTPL (OldTpl);
}

EFI_STATUS
mskc_receive (
    struct msk_if_softc  *sc_if,
    IN OUT UINTN  *BufferSize,
    OUT    VOID   *Buffer
    )
{
  MSK_LINKED_SYSTEM_BUF   *mBuf;

  msk_intr (sc_if->msk_softc); // check the interrupt lines

  if (IsListEmpty (&sc_if->ReceiveQueueHead)) {
    *BufferSize = 0;
    return EFI_NOT_READY;
  }

  mBuf = CR (GetFirstNode (&sc_if->ReceiveQueueHead), MSK_LINKED_SYSTEM_BUF, Link, RX_MBUF_SIGNATURE);
  if (mBuf->SystemBuf.Length > *BufferSize) {
    *BufferSize = mBuf->SystemBuf.Length;
    DEBUG ((EFI_D_NET, "Marvell Yukon: Receive buffer is too small: Provided = %d, Received = %d\n",
            *BufferSize, mBuf->SystemBuf.Length));
    return EFI_BUFFER_TOO_SMALL;
  }
  *BufferSize = mBuf->SystemBuf.Length;
  RemoveEntryList (&mBuf->Link);
  gBS->CopyMem (Buffer, mBuf->SystemBuf.Buf, *BufferSize);
  gBS->FreePool(mBuf->SystemBuf.Buf);
  gBS->FreePool (mBuf);
  return EFI_SUCCESS;
}

static VOID
msk_rxeof (
    struct msk_if_softc   *sc_if,
    UINT32                status,
    UINT32                control,
    INTN                  len
    )
{
  EFI_STATUS            Status;
  MSK_LINKED_SYSTEM_BUF *m_link;
  struct msk_rxdesc     *rxd;
  INTN                  cons;
  INTN                  rxlen;
  MSK_DMA_BUF           m;
  EFI_PCI_IO_PROTOCOL    *PciIo;

  DEBUG ((EFI_D_NET, "Marvell Yukon: rxeof\n"));

  PciIo = sc_if->msk_softc->PciIo;
  cons = sc_if->msk_cdata.msk_rx_cons;
  do {
    rxlen = status >> 16;
    if ((sc_if->msk_flags & MSK_FLAG_NORXCHK) != 0) {
      //
      // For controllers that returns bogus status code
      // just do minimal check and let upper stack
      // handle this frame.
      //
      if (len > MAX_SUPPORTED_PACKET_SIZE || len < NET_ETHER_ADDR_LEN) {
        msk_discard_rxbuf (sc_if, cons);
        break;
      }
    } else if (len > sc_if->msk_framesize ||
               ((status & GMR_FS_ANY_ERR) != 0) ||
               ((status & GMR_FS_RX_OK) == 0) || (rxlen != len)) {
      msk_discard_rxbuf (sc_if, cons);
      break;
    }

#ifdef MSK_64BIT_DMA
    rxd = &sc_if->msk_cdata.msk_rxdesc[(cons + 1) % MSK_RX_RING_CNT];
#else
    rxd = &sc_if->msk_cdata.msk_rxdesc[cons];
#endif

    m.Buf = rxd->rx_m.Buf;
    m.DmaMapping = rxd->rx_m.DmaMapping;
    m.Length = rxd->rx_m.Length;

    Status = msk_newbuf (sc_if, cons);
    if (EFI_ERROR (Status)) {
      // This is a dropped packet, but we aren't counting drops
      // Reuse old buffer
      msk_discard_rxbuf (sc_if, cons);
      break;
    }

    Status = PciIo->Flush (PciIo);
    if (EFI_ERROR (Status)) {
      DEBUG ((EFI_D_NET, "Marvell Yukon: failed to Flush DMA\n"));
    }

    Status = PciIo->Unmap (PciIo, rxd->rx_m.DmaMapping);
    if (EFI_ERROR (Status)) {
      DEBUG ((EFI_D_NET, "Marvell Yukon: failed to Unmap DMA\n"));
    }

    Status = gBS->AllocatePool (EfiBootServicesData,
                                sizeof (MSK_LINKED_SYSTEM_BUF),
                                (VOID**) &m_link);
    if (!EFI_ERROR (Status)) {
      gBS->SetMem (m_link, sizeof (MSK_LINKED_SYSTEM_BUF), 0);
      m_link->Signature = RX_MBUF_SIGNATURE;
      m_link->SystemBuf.Buf = m.Buf;
      m_link->SystemBuf.Length = len;

      InsertTailList (&sc_if->ReceiveQueueHead, &m_link->Link);
    } else {
      DEBUG ((EFI_D_NET, "Marvell Yukon: failed to allocate receive buffer link. Dropping Frame\n"));
      gBS->FreePool (m.Buf);
    }
  } while (0);

  MSK_RX_INC (sc_if->msk_cdata.msk_rx_cons, MSK_RX_RING_CNT);
  MSK_RX_INC (sc_if->msk_cdata.msk_rx_prod, MSK_RX_RING_CNT);
}

static
VOID
msk_txeof (
    struct msk_if_softc   *sc_if,
    INTN                  idx
    )
{
  struct msk_txdesc   *txd;
  struct msk_tx_desc  *cur_tx;
  UINT32              control;
  INTN                cons;
  INTN                prog;
  EFI_PCI_IO_PROTOCOL  *PciIo;

  DEBUG ((EFI_D_NET, "Marvell Yukon: txeof\n"));

  PciIo = sc_if->msk_softc->PciIo;

  //
  // Go through our tx ring and free mbufs for those
  // frames that have been sent.
  //
  cons = sc_if->msk_cdata.msk_tx_cons;
  prog = 0;
  for (; cons != idx; MSK_INC (cons, MSK_TX_RING_CNT)) {
    if (sc_if->msk_cdata.msk_tx_cnt <= 0) {
      break;
    }
    prog++;
    cur_tx = &sc_if->msk_rdata.msk_tx_ring[cons];
    control = le32toh (cur_tx->msk_control);
    sc_if->msk_cdata.msk_tx_cnt--;
    if ((control & EOP) == 0) {
      continue;
    }
    txd = &sc_if->msk_cdata.msk_txdesc[cons];
    PciIo->Unmap (PciIo, txd->tx_m.DmaMapping);
    gBS->SetMem (&(txd->tx_m), sizeof (MSK_DMA_BUF), 0);
    // We don't own the transmit buffers so don't free them
  }

  if (prog > 0) {
    sc_if->msk_cdata.msk_tx_cons = cons;
    // No need to sync LEs as we didn't update LEs.
  }
}

VOID
mskc_tick (
    IN EFI_EVENT  Event,
    IN VOID       *Context
    )
{
  EFI_TPL OldTpl;
  struct msk_softc    *sc;

  OldTpl = gBS->RaiseTPL (TPL_NOTIFY);

  sc = (struct msk_softc *)Context;

  if (sc->msk_if[MSK_PORT_A] != NULL && sc->msk_if[MSK_PORT_A]->active) {
    e1000phy_tick (sc->msk_if[MSK_PORT_A]->phy_softc);
  }
  if (sc->msk_if[MSK_PORT_B] != NULL && sc->msk_if[MSK_PORT_B]->active) {
    e1000phy_tick (sc->msk_if[MSK_PORT_B]->phy_softc);
  }

  msk_handle_events (sc);

  gBS->RestoreTPL (OldTpl);
}

static
VOID
msk_intr_phy (
    struct msk_if_softc   *sc_if
    )
{
  UINT16  status;

  msk_phy_readreg (sc_if, PHY_MARV_INT_STAT);
  status = msk_phy_readreg (sc_if, PHY_MARV_INT_STAT);

  // Handle FIFO Underrun/Overflow ?
  if ((status & PHY_M_IS_FIFO_ERROR)) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: PHY FIFO underrun/overflow.\n"));
  }
}

static
VOID
msk_intr_gmac (
    struct msk_if_softc   *sc_if
    )
{
  UINT8   status;
  struct msk_softc  *sc;

  sc = sc_if->msk_softc;

  status = CSR_READ_1 (sc, MR_ADDR (sc_if->msk_md.port, GMAC_IRQ_SRC));

  // GMAC Rx FIFO overrun.
  if ((status & GM_IS_RX_FF_OR) != 0) {
    CSR_WRITE_4 (sc, MR_ADDR (sc_if->msk_md.port, RX_GMF_CTRL_T), GMF_CLI_RX_FO);
  }
  // GMAC Tx FIFO underrun.
  if ((status & GM_IS_TX_FF_UR) != 0) {
    CSR_WRITE_4 (sc, MR_ADDR (sc_if->msk_md.port, TX_GMF_CTRL_T), GMF_CLI_TX_FU);
    //device_printf (sc_if->msk_if_dev, "Tx FIFO underrun!\n");*/
    DEBUG ((EFI_D_NET, "Marvell Yukon: Tx FIFO underrun!\n"));
    /*
     * XXX
     * In case of Tx underrun, we may need to flush/reset
     * Tx MAC but that would also require resynchronization
     * with status LEs. Reintializing status LEs would
     * affect other port in dual MAC configuration so it
     * should be aVOIDed as possible as we can.
     * Due to lack of documentation it's all vague guess but
     * it needs more investigation.
     */
  }
}

static
VOID
msk_handle_hwerr (
    struct msk_if_softc   *sc_if,
    UINT32                status
    )
{
  struct msk_softc  *sc;

  sc = sc_if->msk_softc;

  if ((status & Y2_IS_PAR_RD1) != 0) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: RAM buffer read parity error\n"));
    // Clear IRQ.
    CSR_WRITE_2 (sc, SELECT_RAM_BUFFER (sc_if->msk_md.port, B3_RI_CTRL), RI_CLR_RD_PERR);
  }
  if ((status & Y2_IS_PAR_WR1) != 0) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: RAM buffer write parity error\n"));
    // Clear IRQ
    CSR_WRITE_2 (sc, SELECT_RAM_BUFFER (sc_if->msk_md.port, B3_RI_CTRL), RI_CLR_WR_PERR);
  }
  if ((status & Y2_IS_PAR_MAC1) != 0) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: Tx MAC parity error\n"));
    // Clear IRQ
    CSR_WRITE_4 (sc, MR_ADDR (sc_if->msk_md.port, TX_GMF_CTRL_T), GMF_CLI_TX_PE);
  }
  if ((status & Y2_IS_PAR_RX1) != 0) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: Rx parity error\n"));
    // Clear IRQ
    CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_rxq, Q_CSR), BMU_CLR_IRQ_PAR);
  }
  if ((status & (Y2_IS_TCP_TXS1 | Y2_IS_TCP_TXA1)) != 0) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: TCP segmentation error\n"));
    // Clear IRQ
    CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR), BMU_CLR_IRQ_TCP);
  }
}

static
VOID
msk_intr_hwerr (
    struct msk_softc    *sc
    )
{
  UINT32  status;
  UINT32  tlphead[4];

  status = CSR_READ_4 (sc, B0_HWE_ISRC);

  // Time Stamp timer overflow.
  if ((status & Y2_IS_TIST_OV) != 0) {
    CSR_WRITE_1 (sc, GMAC_TI_ST_CTRL, GMT_ST_CLR_IRQ);
  }
  if ((status & Y2_IS_PCI_NEXP) != 0) {
    /*
     * PCI Express Error occured which is not described in PEX
     * spec.
     * This error is also mapped either to Master Abort (
     * Y2_IS_MST_ERR) or Target Abort (Y2_IS_IRQ_STAT) bit and
     * can only be cleared there.
     */
    DEBUG ((EFI_D_NET, "Marvell Yukon: PCI Express protocol violation error\n"));
  }

  if ((status & (Y2_IS_MST_ERR | Y2_IS_IRQ_STAT)) != 0) {

    if ((status & Y2_IS_MST_ERR) != 0) {
      DEBUG ((EFI_D_NET, "Marvell Yukon: unexpected IRQ Status error\n"));
    } else {
      DEBUG ((EFI_D_NET, "Marvell Yukon: unexpected IRQ Master error\n"));
    }
    // Reset all bits in the PCI status register
    clear_pci_errors (sc);
  }

  // Check for PCI Express Uncorrectable Error.
  if ((status & Y2_IS_PCI_EXP) != 0) {
    UINT32 v32;

    /*
     * On PCI Express bus bridges are called root complexes (RC).
     * PCI Express errors are recognized by the root complex too,
     * which requests the system to handle the problem. After
     * error occurrence it may be that no access to the adapter
     * may be performed any longer.
     */

    v32 = CSR_PCI_READ_4 (sc, PEX_UNC_ERR_STAT);
    if ((v32 & PEX_UNSUP_REQ) != 0) {
      // Ignore unsupported request error.
      DEBUG ((EFI_D_NET, "Marvell Yukon: Uncorrectable PCI Express error\n"));
    }
    if ((v32 & (PEX_FATAL_ERRORS | PEX_POIS_TLP)) != 0) {
      INTN i;

      // Get TLP header form Log Registers.
      for (i = 0; i < 4; i++) {
        tlphead[i] = CSR_PCI_READ_4 (sc, PEX_HEADER_LOG + i * 4);
      }
      // Check for vendor defined broadcast message.
      if (!(tlphead[0] == 0x73004001 && tlphead[1] == 0x7f)) {
        sc->msk_intrhwemask &= ~Y2_IS_PCI_EXP;
        CSR_WRITE_4 (sc, B0_HWE_IMSK, sc->msk_intrhwemask);
        CSR_READ_4 (sc, B0_HWE_IMSK);
      }
    }
    // Clear the interrupt
    CSR_WRITE_1 (sc, B2_TST_CTRL1, TST_CFG_WRITE_ON);
    CSR_PCI_WRITE_4 (sc, PEX_UNC_ERR_STAT, 0xffffffff);
    CSR_WRITE_1 (sc, B2_TST_CTRL1, TST_CFG_WRITE_OFF);
  }

  if ((status & Y2_HWE_L1_MASK) != 0 && sc->msk_if[MSK_PORT_A] != NULL) {
    msk_handle_hwerr (sc->msk_if[MSK_PORT_A], status);
  }
  if ((status & Y2_HWE_L2_MASK) != 0 && sc->msk_if[MSK_PORT_B] != NULL) {
    msk_handle_hwerr (sc->msk_if[MSK_PORT_B], status >> 8);
  }
}

static
__inline
VOID
msk_rxput (
    struct msk_if_softc   *sc_if
    )
{
  CSR_WRITE_2 (sc_if->msk_softc, Y2_PREF_Q_ADDR (sc_if->msk_rxq, PREF_UNIT_PUT_IDX_REG), sc_if->msk_cdata.msk_rx_prod);
}

static
INTN
msk_handle_events (
    struct msk_softc  *sc
    )
{
  INTN                  rxput[2];
  struct msk_stat_desc  *sd;
  UINT32                control;
  UINT32                status;
  INTN                  cons;
  INTN                  len;
  INTN                  port;
  INTN                  rxprog;
  struct msk_if_softc   *sc_if;

  if (sc->msk_stat_cons == CSR_READ_2 (sc, STAT_PUT_IDX)) {
    return (0);
  }

  rxput[MSK_PORT_A] = rxput[MSK_PORT_B] = 0;
  rxprog = 0;
  cons = sc->msk_stat_cons;
  for (;;) {
    sd = &sc->msk_stat_ring[cons];
    control = le32toh (sd->msk_control);
    if ((control & HW_OWNER) == 0) {
      break;
    }
    control &= ~HW_OWNER;
    sd->msk_control = htole32 (control);
    status = le32toh (sd->msk_status);
    len = control & STLE_LEN_MASK;
    port = (control >> 16) & 0x01;
    sc_if = sc->msk_if[port];
    if (sc_if == NULL) {
      DEBUG ((EFI_D_NET, "Marvell Yukon: invalid port opcode 0x%08x\n", control & STLE_OP_MASK));
      continue;
    }

    switch (control & STLE_OP_MASK) {
      case OP_RXSTAT:
        msk_rxeof (sc_if, status, control, len);
        rxprog++;
        //
        // Because there is no way to sync single Rx LE
        // put the DMA sync operation off until the end of
        // event processing.
        //
        rxput[port]++;
        // Update prefetch unit if we've passed water mark
        if (rxput[port] >= sc_if->msk_cdata.msk_rx_putwm) {
          msk_rxput (sc_if);
          rxput[port] = 0;
        }
        break;
      case OP_TXINDEXLE:
        if (sc->msk_if[MSK_PORT_A] != NULL) {
          msk_txeof (sc->msk_if[MSK_PORT_A], status & STLE_TXA1_MSKL);
        }
        if (sc->msk_if[MSK_PORT_B] != NULL) {
          msk_txeof (sc->msk_if[MSK_PORT_B],
                     ((status & STLE_TXA2_MSKL) >>
                      STLE_TXA2_SHIFTL) |
                     ((len & STLE_TXA2_MSKH) <<
                      STLE_TXA2_SHIFTH));
        }
        break;
      default:
        DEBUG ((EFI_D_NET, "Marvell Yukon: unhandled opcode 0x%08x\n", control & STLE_OP_MASK));
        break;
    }
    MSK_INC (cons, MSK_STAT_RING_CNT);
    if (rxprog > sc->msk_process_limit) {
      break;
    }
  }

  sc->msk_stat_cons = cons;

  if (rxput[MSK_PORT_A] > 0) {
    msk_rxput (sc->msk_if[MSK_PORT_A]);
  }
  if (rxput[MSK_PORT_B] > 0) {
    msk_rxput (sc->msk_if[MSK_PORT_B]);
  }

  return (sc->msk_stat_cons != CSR_READ_2 (sc, STAT_PUT_IDX));
}

STATIC
VOID
msk_intr (
    struct msk_softc  *sc
    )
{
  struct msk_if_softc   *sc_if0;
  struct msk_if_softc   *sc_if1;
  UINT32                Status;
  INTN                  domore;

  // Reading B0_Y2_SP_ISRC2 masks further interrupts
  Status = CSR_READ_4 (sc, B0_Y2_SP_ISRC2);
  if (Status == 0 || Status == 0xffffffff ||
      (sc->msk_pflags & MSK_FLAG_SUSPEND) != 0 ||
      (Status & sc->msk_intrmask) == 0)
  {
    // Leave ISR - Reenable interrupts
    CSR_WRITE_4 (sc, B0_Y2_SP_ICR, 2);
    return;
  }

  sc_if0 = sc->msk_if[MSK_PORT_A];
  sc_if1 = sc->msk_if[MSK_PORT_B];

  if ((Status & Y2_IS_IRQ_PHY1) != 0 && sc_if0 != NULL) {
    msk_intr_phy (sc_if0);
  }
  if ((Status & Y2_IS_IRQ_PHY2) != 0 && sc_if1 != NULL) {
    msk_intr_phy (sc_if1);
  }
  if ((Status & Y2_IS_IRQ_MAC1) != 0 && sc_if0 != NULL) {
    msk_intr_gmac (sc_if0);
  }
  if ((Status & Y2_IS_IRQ_MAC2) != 0 && sc_if1 != NULL) {
    msk_intr_gmac (sc_if1);
  }
  if ((Status & (Y2_IS_CHK_RX1 | Y2_IS_CHK_RX2)) != 0) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: Rx descriptor error\n"));
    sc->msk_intrmask &= ~(Y2_IS_CHK_RX1 | Y2_IS_CHK_RX2);
    CSR_WRITE_4 (sc, B0_IMSK, sc->msk_intrmask);
    CSR_READ_4 (sc, B0_IMSK);
  }
  if ((Status & (Y2_IS_CHK_TXA1 | Y2_IS_CHK_TXA2)) != 0) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: Tx descriptor error\n"));
    sc->msk_intrmask &= ~(Y2_IS_CHK_TXA1 | Y2_IS_CHK_TXA2);
    CSR_WRITE_4 (sc, B0_IMSK, sc->msk_intrmask);
    CSR_READ_4 (sc, B0_IMSK);
  }
  if ((Status & Y2_IS_HW_ERR) != 0) {
    msk_intr_hwerr (sc);
  }

  domore = msk_handle_events (sc);
  if ((Status & Y2_IS_STAT_BMU) != 0 && domore == 0) {
    CSR_WRITE_4 (sc, STAT_CTRL, SC_STAT_CLR_IRQ);
  }

  // Leave ISR - Reenable interrupts
  CSR_WRITE_4 (sc, B0_Y2_SP_ICR, 2);
}

static
VOID
msk_set_tx_stfwd (
    struct msk_if_softc   *sc_if
    )
{
  // Disable jumbo frames for Tx
  CSR_WRITE_4 (sc_if->msk_softc, MR_ADDR (sc_if->msk_md.port, TX_GMF_CTRL_T), TX_JUMBO_DIS | TX_STFW_ENA);
}

EFI_STATUS
mskc_init (
    struct msk_if_softc  *sc_if
    )
{
  EFI_STATUS  Status;

  Status = msk_init (sc_if);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  return Status;
}

static
EFI_STATUS
msk_init (
    IN struct msk_if_softc  *sc_if
    )
{
  UINT8       *eaddr;
  UINT16      gmac;
  UINT32      reg;
  EFI_STATUS  Status;
  INTN                 port;
  IN struct msk_softc  *sc;

  sc = sc_if->msk_softc;
  port = sc_if->msk_md.port;

  // Cancel pending I/O and free all Rx/Tx buffers.
  mskc_stop_if (sc_if);

  sc_if->msk_framesize = MAX_SUPPORTED_PACKET_SIZE;

  // GMAC Control reset.
  CSR_WRITE_4 (sc, MR_ADDR (port, GMAC_CTRL), GMC_RST_SET);
  CSR_WRITE_4 (sc, MR_ADDR (port, GMAC_CTRL), GMC_RST_CLR);
  CSR_WRITE_4 (sc, MR_ADDR (port, GMAC_CTRL), GMC_F_LOOPB_OFF);
  if (sc->msk_hw_id == CHIP_ID_YUKON_EX) {
    CSR_WRITE_4 (sc, MR_ADDR (port, GMAC_CTRL), GMC_BYP_MACSECRX_ON | GMC_BYP_MACSECTX_ON | GMC_BYP_RETR_ON);
  }

  //
  // Initialize GMAC first such that speed/duplex/flow-control
  // parameters are renegotiated when interface is brought up.
  //
  GMAC_WRITE_2 (sc, port, GM_GP_CTRL, 0);

  // Dummy read the Interrupt Source Register
  CSR_READ_1 (sc, MR_ADDR (port, GMAC_IRQ_SRC));

  // Clear MIB stats
  msk_stats_clear (sc_if);

  // Disable FCS
  GMAC_WRITE_2 (sc, port, GM_RX_CTRL, GM_RXCR_CRC_DIS);

  // Setup Transmit Control Register
  GMAC_WRITE_2 (sc, port, GM_TX_CTRL, TX_COL_THR (TX_COL_DEF));

  // Setup Transmit Flow Control Register
  GMAC_WRITE_2 (sc, port, GM_TX_FLOW_CTRL, 0xffff);

  // Setup Transmit Parameter Register
  GMAC_WRITE_2 (sc, port, GM_TX_PARAM,
                TX_JAM_LEN_VAL (TX_JAM_LEN_DEF) | TX_JAM_IPG_VAL (TX_JAM_IPG_DEF) |
                TX_IPG_JAM_DATA(TX_IPG_JAM_DEF) | TX_BACK_OFF_LIM(TX_BOF_LIM_DEF));

  gmac = DATA_BLIND_VAL (DATA_BLIND_DEF) | GM_SMOD_VLAN_ENA | IPG_DATA_VAL (IPG_DATA_DEF);

  GMAC_WRITE_2 (sc, port, GM_SERIAL_MODE, gmac);

  // Set station address
  eaddr = sc_if->MacAddress.Addr;
  GMAC_WRITE_2 (sc, port, GM_SRC_ADDR_1L, eaddr[0] | (eaddr[1] << 8));
  GMAC_WRITE_2 (sc, port, GM_SRC_ADDR_1M, eaddr[2] | (eaddr[3] << 8));
  GMAC_WRITE_2 (sc, port, GM_SRC_ADDR_1H, eaddr[4] | (eaddr[5] << 8));
  GMAC_WRITE_2 (sc, port, GM_SRC_ADDR_2L, eaddr[0] | (eaddr[1] << 8));
  GMAC_WRITE_2 (sc, port, GM_SRC_ADDR_2M, eaddr[2] | (eaddr[3] << 8));
  GMAC_WRITE_2 (sc, port, GM_SRC_ADDR_2H, eaddr[4] | (eaddr[5] << 8));

  // Disable interrupts for counter overflows
  GMAC_WRITE_2 (sc, port, GM_TX_IRQ_MSK, 0);
  GMAC_WRITE_2 (sc, port, GM_RX_IRQ_MSK, 0);
  GMAC_WRITE_2 (sc, port, GM_TR_IRQ_MSK, 0);

  // Configure Rx MAC FIFO
  CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_CTRL_T), GMF_RST_SET);
  CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_CTRL_T), GMF_RST_CLR);
  reg = GMF_OPER_ON | GMF_RX_F_FL_ON;
  if (sc->msk_hw_id == CHIP_ID_YUKON_FE_P || sc->msk_hw_id == CHIP_ID_YUKON_EX) {
    reg |= GMF_RX_OVER_ON;
  }
  CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_CTRL_T), reg);

  if (sc->msk_hw_id == CHIP_ID_YUKON_XL) {
    // Clear flush mask - HW bug
    CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_FL_MSK), 0);
  } else {
    // Flush Rx MAC FIFO on any flow control or error
    CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_FL_MSK), GMR_FS_ANY_ERR);
  }

  //
  // Set Rx FIFO flush threshold to 64 bytes + 1 FIFO word
  // due to hardware hang on receipt of pause frames.
  //
  reg = RX_GMF_FL_THR_DEF + 1;
  // Another magic for Yukon FE+ - From Linux
  if (sc->msk_hw_id == CHIP_ID_YUKON_FE_P && sc->msk_hw_rev == CHIP_REV_YU_FE_P_A0) {
    reg = 0x178;
  }
  CSR_WRITE_2 (sc, MR_ADDR (port, RX_GMF_FL_THR), reg);

  // Configure Tx MAC FIFO
  CSR_WRITE_4 (sc, MR_ADDR (port, TX_GMF_CTRL_T), GMF_RST_SET);
  CSR_WRITE_4 (sc, MR_ADDR (port, TX_GMF_CTRL_T), GMF_RST_CLR);
  CSR_WRITE_4 (sc, MR_ADDR (port, TX_GMF_CTRL_T), GMF_OPER_ON);

  // Configure hardware VLAN tag insertion/stripping
  msk_setvlan (sc_if);

  if ((sc_if->msk_flags & MSK_FLAG_RAMBUF) == 0) {
    // Set Rx Pause threshould.
    CSR_WRITE_2 (sc, MR_ADDR (port, RX_GMF_LP_THR), MSK_ECU_LLPP);
    CSR_WRITE_2 (sc, MR_ADDR (port, RX_GMF_UP_THR), MSK_ECU_ULPP);
    // Configure store-and-forward for Tx.
    msk_set_tx_stfwd (sc_if);
  }

  if (sc->msk_hw_id == CHIP_ID_YUKON_FE_P && sc->msk_hw_rev == CHIP_REV_YU_FE_P_A0) {
    // Disable dynamic watermark - from Linux
    reg = CSR_READ_4 (sc, MR_ADDR (port, TX_GMF_EA));
    reg &= ~0x03;
    CSR_WRITE_4 (sc, MR_ADDR (port, TX_GMF_EA), reg);
  }

  //
  // Disable Force Sync bit and Alloc bit in Tx RAM interface
  // arbiter as we don't use Sync Tx queue.
  //
  CSR_WRITE_1 (sc, MR_ADDR (port, TXA_CTRL), TXA_DIS_FSYNC | TXA_DIS_ALLOC | TXA_STOP_RC);
  // Enable the RAM Interface Arbiter
  CSR_WRITE_1 (sc, MR_ADDR (port, TXA_CTRL), TXA_ENA_ARB);

  // Setup RAM buffer
  msk_set_rambuffer (sc_if);

  // Disable Tx sync Queue
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_txsq, RB_CTRL), RB_RST_SET);

  // Setup Tx Queue Bus Memory Interface
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR), BMU_CLR_RESET);
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR), BMU_OPER_INIT);
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR), BMU_FIFO_OP_ON);
  CSR_WRITE_2 (sc, Q_ADDR (sc_if->msk_txq, Q_WM),  MSK_BMU_TX_WM);
  switch (sc->msk_hw_id) {
    case CHIP_ID_YUKON_EC_U:
      if (sc->msk_hw_rev == CHIP_REV_YU_EC_U_A0) {
        // Fix for Yukon-EC Ultra: set BMU FIFO level
        CSR_WRITE_2 (sc, Q_ADDR (sc_if->msk_txq, Q_AL), MSK_ECU_TXFF_LEV);
      }
      break;
    case CHIP_ID_YUKON_EX:
      //
      // Yukon Extreme seems to have silicon bug for
      // automatic Tx checksum calculation capability.
      //
      if (sc->msk_hw_rev == CHIP_REV_YU_EX_B0) {
        CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_txq, Q_F), F_TX_CHK_AUTO_OFF);
      }
      break;
  }

  // Setup Rx Queue Bus Memory Interface
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_rxq, Q_CSR), BMU_CLR_RESET);
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_rxq, Q_CSR), BMU_OPER_INIT);
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_rxq, Q_CSR), BMU_FIFO_OP_ON);
  CSR_WRITE_2 (sc, Q_ADDR (sc_if->msk_rxq, Q_WM),  MSK_BMU_RX_WM);
  if (sc->msk_hw_id == CHIP_ID_YUKON_EC_U && sc->msk_hw_rev >= CHIP_REV_YU_EC_U_A1) {
    // MAC Rx RAM Read is controlled by hardware
    CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_rxq, Q_F), F_M_RX_RAM_DIS);
  }

  // truncate too-large frames - from linux
  CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_TR_THR), 0x17a);
  CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_CTRL_T), RX_TRUNC_ON);

  msk_set_prefetch (sc_if, sc_if->msk_txq, sc_if->msk_rdata.msk_tx_ring_paddr, MSK_TX_RING_CNT - 1);
  msk_init_tx_ring (sc_if);

  // Disable Rx checksum offload and RSS hash
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_rxq, Q_CSR), BMU_DIS_RX_CHKSUM | BMU_DIS_RX_RSS_HASH);
  msk_set_prefetch (sc_if, sc_if->msk_rxq, sc_if->msk_rdata.msk_rx_ring_paddr, MSK_RX_RING_CNT - 1);
  Status = msk_init_rx_ring (sc_if);
  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "Marvell Yukon: Initialization failed: no memory for Rx buffers\n"));
    mskc_stop_if (sc_if);
    return Status;
  }

  if (sc->msk_hw_id == CHIP_ID_YUKON_EX) {
    // Disable flushing of non-ASF packets
    CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_CTRL_T), GMF_RX_MACSEC_FLUSH_OFF);
  }

  // Configure interrupt handling
  if (port == MSK_PORT_A) {
    sc->msk_intrmask |= Y2_IS_PORT_A;
    sc->msk_intrhwemask |= Y2_HWE_L1_MASK;
  } else {
    sc->msk_intrmask |= Y2_IS_PORT_B;
    sc->msk_intrhwemask |= Y2_HWE_L2_MASK;
  }
  // Configure IRQ moderation mask.
  CSR_WRITE_4 (sc, B2_IRQM_MSK, sc->msk_intrmask);
  if (sc->msk_int_holdoff > 0) {
    // Configure initial IRQ moderation timer value.
    CSR_WRITE_4 (sc, B2_IRQM_INI, MSK_USECS (sc, sc->msk_int_holdoff));
    CSR_WRITE_4 (sc, B2_IRQM_VAL, MSK_USECS (sc, sc->msk_int_holdoff));
    // Start IRQ moderation.
    CSR_WRITE_1 (sc, B2_IRQM_CTRL, TIM_START);
  }
  CSR_WRITE_4 (sc, B0_HWE_IMSK, sc->msk_intrhwemask);
  CSR_READ_4 (sc, B0_HWE_IMSK);
  CSR_WRITE_4 (sc, B0_IMSK, sc->msk_intrmask);
  CSR_READ_4 (sc, B0_IMSK);

  sc_if->msk_flags &= ~MSK_FLAG_LINK;
  e1000phy_mediachg (sc_if->phy_softc);

  return Status;
}

STATIC
VOID
msk_set_rambuffer (
    struct msk_if_softc *sc_if
    )
{
  INTN ltpp, utpp;
  INTN              port;
  struct msk_softc  *sc;

  sc = sc_if->msk_softc;
  port = sc_if->msk_md.port;

  if ((sc_if->msk_flags & MSK_FLAG_RAMBUF) == 0)
    return;

  // Setup Rx Queue
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_rxq, RB_CTRL), RB_RST_CLR);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_rxq, RB_START), sc->msk_rxqstart[port] / 8);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_rxq, RB_END), sc->msk_rxqend[port] / 8);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_rxq, RB_WP), sc->msk_rxqstart[port] / 8);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_rxq, RB_RP), sc->msk_rxqstart[port] / 8);

  utpp = (sc->msk_rxqend[port] + 1 - sc->msk_rxqstart[port] - MSK_RB_ULPP) / 8;
  ltpp = (sc->msk_rxqend[port] + 1 - sc->msk_rxqstart[port] - MSK_RB_LLPP_B) / 8;
  if (sc->msk_rxqsize < MSK_MIN_RXQ_SIZE) {
    ltpp += (MSK_RB_LLPP_B - MSK_RB_LLPP_S) / 8;
  }
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_rxq, RB_RX_UTPP), utpp);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_rxq, RB_RX_LTPP), ltpp);
  // Set Rx priority (RB_RX_UTHP/RB_RX_LTHP) thresholds?

  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_rxq, RB_CTRL), RB_ENA_OP_MD);
  CSR_READ_1 (sc, RB_ADDR (sc_if->msk_rxq, RB_CTRL));

  // Setup Tx Queue.
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_txq, RB_CTRL), RB_RST_CLR);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_txq, RB_START), sc->msk_txqstart[port] / 8);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_txq, RB_END), sc->msk_txqend[port] / 8);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_txq, RB_WP), sc->msk_txqstart[port] / 8);
  CSR_WRITE_4 (sc, RB_ADDR (sc_if->msk_txq, RB_RP), sc->msk_txqstart[port] / 8);

  // Enable Store & Forward for Tx side
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_txq, RB_CTRL), RB_ENA_STFWD);
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_txq, RB_CTRL), RB_ENA_OP_MD);
  CSR_READ_1 (sc, RB_ADDR (sc_if->msk_txq, RB_CTRL));
}

STATIC
VOID
msk_set_prefetch (
    struct msk_if_softc   *sc_if,
    INTN qaddr,
    EFI_PHYSICAL_ADDRESS addr,
    UINT32 count
    )
{
  struct msk_softc     *sc;

  sc = sc_if->msk_softc;

  // Reset the prefetch unit
  CSR_WRITE_4 (sc, Y2_PREF_Q_ADDR (qaddr, PREF_UNIT_CTRL_REG), PREF_UNIT_RST_SET);
  CSR_WRITE_4 (sc, Y2_PREF_Q_ADDR (qaddr, PREF_UNIT_CTRL_REG), PREF_UNIT_RST_CLR);
  // Set LE base address
  CSR_WRITE_4 (sc, Y2_PREF_Q_ADDR (qaddr, PREF_UNIT_ADDR_LOW_REG), MSK_ADDR_LO (addr));
  CSR_WRITE_4 (sc, Y2_PREF_Q_ADDR (qaddr, PREF_UNIT_ADDR_HI_REG), MSK_ADDR_HI (addr));

  // Set the list last index
  CSR_WRITE_2 (sc, Y2_PREF_Q_ADDR (qaddr, PREF_UNIT_LAST_IDX_REG), count);
  // Turn on prefetch unit
  CSR_WRITE_4 (sc, Y2_PREF_Q_ADDR (qaddr, PREF_UNIT_CTRL_REG), PREF_UNIT_OP_ON);
  // Dummy read to ensure write
  CSR_READ_4 (sc, Y2_PREF_Q_ADDR (qaddr, PREF_UNIT_CTRL_REG));
}

VOID
mskc_stop_if (
    struct msk_if_softc  *sc_if
    )
{
  struct msk_txdesc   *txd;
  struct msk_rxdesc   *rxd;
  UINT32              val;
  INTN                i;
  INTN                 port;
  EFI_PCI_IO_PROTOCOL  *PciIo;
  struct msk_softc     *sc;

  sc = sc_if->msk_softc;
  PciIo = sc->PciIo;
  port = sc_if->msk_md.port;

  // Disable interrupts
  if (port == MSK_PORT_A) {
    sc->msk_intrmask &= ~Y2_IS_PORT_A;
    sc->msk_intrhwemask &= ~Y2_HWE_L1_MASK;
  } else {
    sc->msk_intrmask &= ~Y2_IS_PORT_B;
    sc->msk_intrhwemask &= ~Y2_HWE_L2_MASK;
  }
  CSR_WRITE_4 (sc, B0_HWE_IMSK, sc->msk_intrhwemask);
  CSR_READ_4 (sc, B0_HWE_IMSK);
  CSR_WRITE_4 (sc, B0_IMSK, sc->msk_intrmask);
  CSR_READ_4 (sc, B0_IMSK);

  // Disable Tx/Rx MAC.
  val = GMAC_READ_2 (sc, port, GM_GP_CTRL);
  val &= ~(GM_GPCR_RX_ENA | GM_GPCR_TX_ENA);
  GMAC_WRITE_2 (sc, port, GM_GP_CTRL, val);
  // Read again to ensure writing.
  GMAC_READ_2 (sc, port, GM_GP_CTRL);
  // Update stats and clear counters
  msk_stats_update (sc_if);

  // Stop Tx BMU
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR), BMU_STOP);
  val = CSR_READ_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR));
  for (i = 0; i < MSK_TIMEOUT; i++) {
    if ((val & (BMU_STOP | BMU_IDLE)) == 0) {
      CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR), BMU_STOP);
      val = CSR_READ_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR));
    } else {
      break;
    }
    gBS->Stall (1);
  }
  if (i == MSK_TIMEOUT) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: Tx BMU stop failed\n"));
  }
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_txq, RB_CTRL), RB_RST_SET | RB_DIS_OP_MD);

  // Disable all GMAC interrupt.
  CSR_WRITE_1 (sc, MR_ADDR (port, GMAC_IRQ_MSK), 0);
  // Disable PHY interrupt. */
  msk_phy_writereg (sc_if, PHY_MARV_INT_MASK, 0);

  // Disable the RAM Interface Arbiter.
  CSR_WRITE_1 (sc, MR_ADDR (port, TXA_CTRL), TXA_DIS_ARB);

  // Reset the PCI FIFO of the async Tx queue
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_txq, Q_CSR), BMU_RST_SET | BMU_FIFO_RST);

  // Reset the Tx prefetch units
  CSR_WRITE_4 (sc, Y2_PREF_Q_ADDR (sc_if->msk_txq, PREF_UNIT_CTRL_REG), PREF_UNIT_RST_SET);

  // Reset the RAM Buffer async Tx queue
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_txq, RB_CTRL), RB_RST_SET);

  // Reset Tx MAC FIFO.
  CSR_WRITE_4 (sc, MR_ADDR (port, TX_GMF_CTRL_T), GMF_RST_SET);
  // Set Pause Off.
  CSR_WRITE_4 (sc, MR_ADDR (port, GMAC_CTRL), GMC_PAUSE_OFF);

  /*
   * The Rx Stop command will not work for Yukon-2 if the BMU does not
   * reach the end of packet and since we can't make sure that we have
   * incoming data, we must reset the BMU while it is not during a DMA
   * transfer. Since it is possible that the Rx path is still active,
   * the Rx RAM buffer will be stopped first, so any possible incoming
   * data will not trigger a DMA. After the RAM buffer is stopped, the
   * BMU is polled until any DMA in progress is ended and only then it
   * will be reset.
   */

  // Disable the RAM Buffer receive queue
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_rxq, RB_CTRL), RB_DIS_OP_MD);
  for (i = 0; i < MSK_TIMEOUT; i++) {
    if (CSR_READ_1 (sc, RB_ADDR (sc_if->msk_rxq, Q_RSL)) == CSR_READ_1 (sc, RB_ADDR (sc_if->msk_rxq, Q_RL))) {
      break;
    }
    gBS->Stall (1);
  }
  if (i == MSK_TIMEOUT) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: Rx BMU stop failed\n"));
  }
  CSR_WRITE_4 (sc, Q_ADDR (sc_if->msk_rxq, Q_CSR), BMU_RST_SET | BMU_FIFO_RST);
  // Reset the Rx prefetch unit.
  CSR_WRITE_4 (sc, Y2_PREF_Q_ADDR (sc_if->msk_rxq, PREF_UNIT_CTRL_REG), PREF_UNIT_RST_SET);
  // Reset the RAM Buffer receive queue.
  CSR_WRITE_1 (sc, RB_ADDR (sc_if->msk_rxq, RB_CTRL), RB_RST_SET);
  // Reset Rx MAC FIFO.
  CSR_WRITE_4 (sc, MR_ADDR (port, RX_GMF_CTRL_T), GMF_RST_SET);

  // Free Rx and Tx mbufs still in the queues
  for (i = 0; i < MSK_RX_RING_CNT; i++) {
    rxd = &sc_if->msk_cdata.msk_rxdesc[i];
    if (rxd->rx_m.Buf != NULL) {
      PciIo->Unmap (PciIo, rxd->rx_m.DmaMapping);
      if(rxd->rx_m.Buf != NULL) {
        gBS->FreePool (rxd->rx_m.Buf);
        rxd->rx_m.Buf = NULL;
      }
      gBS->SetMem (&(rxd->rx_m), sizeof (MSK_DMA_BUF), 0);
    }
  }

  for (i = 0; i < MSK_TX_RING_CNT; i++) {
    txd = &sc_if->msk_cdata.msk_txdesc[i];
    if (txd->tx_m.Buf != NULL) {
      PciIo->Unmap (PciIo, txd->tx_m.DmaMapping);
      gBS->SetMem (&(txd->tx_m), sizeof (MSK_DMA_BUF), 0);
      // We don't own the transmit buffers so don't free them
    }
  }

  /*
   * Mark the interface down.
   */
  sc_if->msk_flags &= ~MSK_FLAG_LINK;
}

/*
 * When GM_PAR_MIB_CLR bit of GM_PHY_ADDR is set, reading lower
 * counter clears high 16 bits of the counter such that accessing
 * lower 16 bits should be the last operation.
 */
#define  MSK_READ_MIB32(x, y)    (((UINT32)GMAC_READ_2 (sc, x, (y) + 4)) << 16) +  (UINT32)GMAC_READ_2 (sc, x, y)
#define  MSK_READ_MIB64(x, y)    (((UINT64)MSK_READ_MIB32 (x, (y) + 8)) << 32) + (UINT64)MSK_READ_MIB32 (x, y)

static
VOID
msk_stats_clear (
    struct msk_if_softc   *sc_if
    )
{
  UINT16      gmac;
  INTN        val;
  INTN        i;
  INTN              port;
  struct msk_softc  *sc;

  sc = sc_if->msk_softc;
  port = sc_if->msk_md.port;

  // Set MIB Clear Counter Mode.
  gmac = GMAC_READ_2 (sc, port, GM_PHY_ADDR);
  GMAC_WRITE_2 (sc, port, GM_PHY_ADDR, gmac | GM_PAR_MIB_CLR);
  // Read all MIB Counters with Clear Mode set
  for (i = GM_RXF_UC_OK; i <= GM_TXE_FIFO_UR; i += sizeof (UINT32)) {
    val = MSK_READ_MIB32 (port, i);
    if (val); //Workaround: to prevent the GCC error: 'value computed is not used'
  }
  // Clear MIB Clear Counter Mode
  gmac &= ~GM_PAR_MIB_CLR;
  GMAC_WRITE_2 (sc, port, GM_PHY_ADDR, gmac);
}

static
VOID
msk_stats_update (
    struct msk_if_softc   *sc_if
    )
{
  struct msk_hw_stats   *stats;
  UINT16                gmac;
  INTN                  val;
  INTN                 port;
  struct msk_softc     *sc;

  sc = sc_if->msk_softc;
  port = sc_if->msk_md.port;
  stats = &sc_if->msk_stats;
  /* Set MIB Clear Counter Mode. */
  gmac = GMAC_READ_2 (sc, port, GM_PHY_ADDR);
  GMAC_WRITE_2 (sc, port, GM_PHY_ADDR, gmac | GM_PAR_MIB_CLR);

  /* Rx stats. */
  stats->rx_ucast_frames    += MSK_READ_MIB32 (port, GM_RXF_UC_OK);
  stats->rx_bcast_frames    += MSK_READ_MIB32 (port, GM_RXF_BC_OK);
  stats->rx_pause_frames    += MSK_READ_MIB32 (port, GM_RXF_MPAUSE);
  stats->rx_mcast_frames    += MSK_READ_MIB32 (port, GM_RXF_MC_OK);
  stats->rx_crc_errs        += MSK_READ_MIB32 (port, GM_RXF_FCS_ERR);
  val = MSK_READ_MIB32 (port, GM_RXF_SPARE1);
  stats->rx_good_octets     += MSK_READ_MIB64 (port, GM_RXO_OK_LO);
  stats->rx_bad_octets      += MSK_READ_MIB64 (port, GM_RXO_ERR_LO);
  stats->rx_runts           += MSK_READ_MIB32 (port, GM_RXF_SHT);
  stats->rx_runt_errs       += MSK_READ_MIB32 (port, GM_RXE_FRAG);
  stats->rx_pkts_64         += MSK_READ_MIB32 (port, GM_RXF_64B);
  stats->rx_pkts_65_127     += MSK_READ_MIB32 (port, GM_RXF_127B);
  stats->rx_pkts_128_255    += MSK_READ_MIB32 (port, GM_RXF_255B);
  stats->rx_pkts_256_511    += MSK_READ_MIB32 (port, GM_RXF_511B);
  stats->rx_pkts_512_1023   += MSK_READ_MIB32 (port, GM_RXF_1023B);
  stats->rx_pkts_1024_1518  += MSK_READ_MIB32 (port, GM_RXF_1518B);
  stats->rx_pkts_1519_max   += MSK_READ_MIB32 (port, GM_RXF_MAX_SZ);
  stats->rx_pkts_too_long   += MSK_READ_MIB32 (port, GM_RXF_LNG_ERR);
  stats->rx_pkts_jabbers    += MSK_READ_MIB32 (port, GM_RXF_JAB_PKT);
  val = MSK_READ_MIB32 (port, GM_RXF_SPARE2);
  stats->rx_fifo_oflows     += MSK_READ_MIB32 (port, GM_RXE_FIFO_OV);
  val = MSK_READ_MIB32 (port, GM_RXF_SPARE3);

  /* Tx stats. */
  stats->tx_ucast_frames    += MSK_READ_MIB32 (port, GM_TXF_UC_OK);
  stats->tx_bcast_frames    += MSK_READ_MIB32 (port, GM_TXF_BC_OK);
  stats->tx_pause_frames    += MSK_READ_MIB32 (port, GM_TXF_MPAUSE);
  stats->tx_mcast_frames    += MSK_READ_MIB32 (port, GM_TXF_MC_OK);
  stats->tx_octets          += MSK_READ_MIB64 (port, GM_TXO_OK_LO);
  stats->tx_pkts_64         += MSK_READ_MIB32 (port, GM_TXF_64B);
  stats->tx_pkts_65_127     += MSK_READ_MIB32 (port, GM_TXF_127B);
  stats->tx_pkts_128_255    += MSK_READ_MIB32 (port, GM_TXF_255B);
  stats->tx_pkts_256_511    += MSK_READ_MIB32 (port, GM_TXF_511B);
  stats->tx_pkts_512_1023   += MSK_READ_MIB32 (port, GM_TXF_1023B);
  stats->tx_pkts_1024_1518  += MSK_READ_MIB32 (port, GM_TXF_1518B);
  stats->tx_pkts_1519_max   += MSK_READ_MIB32 (port, GM_TXF_MAX_SZ);
  val = MSK_READ_MIB32 (port, GM_TXF_SPARE1);
  stats->tx_colls           += MSK_READ_MIB32 (port, GM_TXF_COL);
  stats->tx_late_colls      += MSK_READ_MIB32 (port, GM_TXF_LAT_COL);
  stats->tx_excess_colls    += MSK_READ_MIB32 (port, GM_TXF_ABO_COL);
  stats->tx_multi_colls     += MSK_READ_MIB32 (port, GM_TXF_MUL_COL);
  stats->tx_single_colls    += MSK_READ_MIB32 (port, GM_TXF_SNG_COL);
  stats->tx_underflows      += MSK_READ_MIB32 (port, GM_TXE_FIFO_UR);

  if (val); //Workaround: to prevent the GCC error: 'value computed is not used'

  /* Clear MIB Clear Counter Mode. */
  gmac &= ~GM_PAR_MIB_CLR;
  GMAC_WRITE_2 (sc, port, GM_PHY_ADDR, gmac);
}

#undef MSK_READ_MIB32
#undef MSK_READ_MIB64
