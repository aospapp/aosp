/**  <at> file
*  Support for Marvell 88E1000 Series PHYs
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
/*-
 * Principal Author: Parag Patel
 * Copyright (c) 2001
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice unmodified, this list of conditions, and the following
 *    disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * Additonal Copyright (c) 2001 by Traakan Software under same licence.
 * Secondary Author: Matthew Jacob
 */

/*
 * driver for the Marvell 88E1000 series external 1000/100/10-BT PHY.
 */

/*
 * Support added for the Marvell 88E1011 (Alaska) 1000/100/10baseTX and
 * 1000baseSX PHY.
 * Nathan Binkert <nate <at> openbsd.org>
 * Jung-uk Kim <jkim <at> niksun.com>
 */

#include <Library/MemoryAllocationLib.h>
#include <Library/BaseLib.h>
#include <Library/DebugLib.h>
#include <Library/UefiBootServicesTableLib.h>

#include "if_media.h"

#include "miivar.h"

#include "e1000phyreg.h"

static EFI_STATUS e1000phy_probe (const struct mii_attach_args *ma);
STATIC VOID  e1000phy_attach (struct e1000phy_softc *, const struct mii_attach_args *ma);
STATIC VOID  e1000phy_service (struct e1000phy_softc *, INTN);
STATIC VOID  e1000phy_status (struct e1000phy_softc *);
STATIC VOID  e1000phy_reset (struct e1000phy_softc *);
STATIC VOID  e1000phy_mii_phy_auto (struct e1000phy_softc *);

INTN msk_phy_readreg (VOID *, INTN);
INTN msk_phy_writereg (VOID *, INTN, INTN);
VOID msk_miibus_statchg (VOID *);

static const struct mii_phydesc * mii_phy_match (const struct mii_attach_args *ma,
                                                 const struct mii_phydesc *mpd);
static const struct mii_phydesc * mii_phy_match_gen (const struct mii_attach_args *ma,
                                                     const struct mii_phydesc *mpd, UINTN endlen);
static EFI_STATUS mii_phy_dev_probe (const struct mii_attach_args *ma, const struct mii_phydesc *mpd);
STATIC VOID mii_phy_update (struct e1000phy_softc *, INTN);

static const struct mii_phydesc e1000phys[] = {
  MII_PHY_DESC (MARVELL, E1000),
  MII_PHY_DESC (MARVELL, E1011),
  MII_PHY_DESC (MARVELL, E1000_3),
  MII_PHY_DESC (MARVELL, E1000S),
  MII_PHY_DESC (MARVELL, E1000_5),
  MII_PHY_DESC (MARVELL, E1000_6),
  MII_PHY_DESC (MARVELL, E3082),
  MII_PHY_DESC (MARVELL, E1112),
  MII_PHY_DESC (MARVELL, E1149),
  MII_PHY_DESC (MARVELL, E1111),
  MII_PHY_DESC (MARVELL, E1116),
  MII_PHY_DESC (MARVELL, E1116R),
  MII_PHY_DESC (MARVELL, E1118),
  MII_PHY_DESC (MARVELL, E3016),
  MII_PHY_DESC (MARVELL, PHYG65G),
  MII_PHY_DESC (xxMARVELL, E1000),
  MII_PHY_DESC (xxMARVELL, E1011),
  MII_PHY_DESC (xxMARVELL, E1000_3),
  MII_PHY_DESC (xxMARVELL, E1000_5),
  MII_PHY_DESC (xxMARVELL, E1111),
  MII_PHY_END
};

EFI_STATUS
e1000_probe_and_attach (
    struct mii_data             *mii,
    const struct msk_mii_data   *mmd,
    VOID                        *sc_if,
    VOID                        **rsc_phy
    )
{
  struct mii_attach_args    ma;
  INTN                      bmsr;
  EFI_STATUS                Status;
  struct e1000phy_softc        *sc_phy;

  Status = gBS->AllocatePool (EfiBootServicesData,
                              sizeof (struct e1000phy_softc),
                              (VOID**) &sc_phy);
  if (EFI_ERROR (Status)) {
    return Status;
  }
  gBS->SetMem (sc_phy, sizeof (struct e1000phy_softc), 0);
  sc_phy->mmd = mmd;

  sc_phy->sc_if = sc_if;

  /*
   * Check to see if there is a PHY at this address.  Note,
   * many braindead PHYs report 0/0 in their ID registers,
   * so we test for media in the BMSR.
   */
  bmsr = PHY_READ (sc_phy, E1000_SR);
  if (bmsr == 0 || bmsr == 0xffff || (bmsr & (E1000_SR_EXTENDED_STATUS|E1000_SR_MEDIAMASK)) == 0) {
    /* Assume no PHY at this address. */
    gBS->FreePool (sc_phy);
    return EFI_DEVICE_ERROR;
  }

  /*
   * Extract the IDs.
   */
  ma.mii_id1 = PHY_READ (sc_phy, E1000_ID1);
  ma.mii_id2 = PHY_READ (sc_phy, E1000_ID2);

  ma.mii_data = mii;

  Status = e1000phy_probe (&ma);
  if (EFI_ERROR (Status)) {
    gBS->FreePool (sc_phy);
    return Status;
  }

  e1000phy_attach (sc_phy, &ma);

  *rsc_phy = sc_phy;

  return EFI_SUCCESS;
}

VOID
e1000phy_detach (
    struct e1000phy_softc *sc_phy
    )
{
  if (sc_phy != NULL) {
    gBS->FreePool (sc_phy);
  }
}

EFI_STATUS
e1000phy_probe (
    const struct mii_attach_args    *ma
    )
{
  return (mii_phy_dev_probe (ma, e1000phys));
}

static void
e1000phy_attach (
    struct e1000phy_softc        *sc_phy,
    const struct mii_attach_args *ma
    )
{
  struct mii_softc    *sc;

  sc = &sc_phy->mii_sc;
  sc->mii_pdata = ma->mii_data;
  sc->mii_anegticks = MII_ANEGTICKS_GIGE;

  sc_phy->mii_model = MII_MODEL (ma->mii_id2);

  if (sc_phy->mmd != NULL && (sc_phy->mmd->mii_flags & MIIF_HAVEFIBER) != 0) {
    sc->mii_flags |= MIIF_HAVEFIBER;
  }

  switch (sc_phy->mii_model) {
    case MII_MODEL_MARVELL_E1011:
    case MII_MODEL_MARVELL_E1112:
      if (PHY_READ (sc_phy, E1000_ESSR) & E1000_ESSR_FIBER_LINK) {
        sc->mii_flags |= MIIF_HAVEFIBER;
      }
      break;
    case MII_MODEL_MARVELL_E1149:
      /*
     * Some 88E1149 PHY's page select is initialized to
     * point to other bank instead of copper/fiber bank
     * which in turn resulted in wrong registers were
     * accessed during PHY operation. It is believed that
     * page 0 should be used for copper PHY so reinitialize
     * E1000_EADR to select default copper PHY. If parent
     * device know the type of PHY(either copper or fiber),
     * that information should be used to select default
     * type of PHY.
     */
      PHY_WRITE (sc_phy, E1000_EADR, 0);
      break;
  }

  e1000phy_reset (sc_phy);

  sc->mii_capabilities = PHY_READ (sc_phy, E1000_SR) & 0xFFFFFFFF;
  if (sc->mii_capabilities & E1000_SR_EXTENDED_STATUS) {
    sc->mii_extcapabilities = PHY_READ (sc_phy, E1000_ESR);
  }
}

static void
e1000phy_reset (
    struct e1000phy_softc  *sc_phy
    )
{
  UINT16  reg;
  UINT16  page;
  struct mii_softc *sc;

  sc = &sc_phy->mii_sc;

  reg = PHY_READ (sc_phy, E1000_SCR);
  if ((sc->mii_flags & MIIF_HAVEFIBER) != 0) {
    reg &= ~E1000_SCR_AUTO_X_MODE;
    PHY_WRITE (sc_phy, E1000_SCR, reg);
    if (sc_phy->mii_model == MII_MODEL_MARVELL_E1112) {
      // Select 1000BASE-X only mode.
      page = PHY_READ (sc_phy, E1000_EADR);
      PHY_WRITE (sc_phy, E1000_EADR, 2);
      reg = PHY_READ (sc_phy, E1000_SCR);
      reg &= ~E1000_SCR_MODE_MASK;
      reg |= E1000_SCR_MODE_1000BX;
      PHY_WRITE (sc_phy, E1000_SCR, reg);
      if (sc_phy->mmd != NULL && sc_phy->mmd->pmd == 'P') {
        // Set SIGDET polarity low for SFP module
        PHY_WRITE (sc_phy, E1000_EADR, 1);
        reg = PHY_READ (sc_phy, E1000_SCR);
        reg |= E1000_SCR_FIB_SIGDET_POLARITY;
        PHY_WRITE (sc_phy, E1000_SCR, reg);
      }
      PHY_WRITE (sc_phy, E1000_EADR, page);
    }
  } else {
    switch (sc_phy->mii_model) {
      case MII_MODEL_MARVELL_E1111:
      case MII_MODEL_MARVELL_E1112:
      case MII_MODEL_MARVELL_E1116:
      case MII_MODEL_MARVELL_E1118:
      case MII_MODEL_MARVELL_E1149:
      case MII_MODEL_MARVELL_PHYG65G:
        // Disable energy detect mode
        reg &= ~E1000_SCR_EN_DETECT_MASK;
        reg |= E1000_SCR_AUTO_X_MODE;
        if (sc_phy->mii_model == MII_MODEL_MARVELL_E1116)
          reg &= ~E1000_SCR_POWER_DOWN;
        reg |= E1000_SCR_ASSERT_CRS_ON_TX;
        break;
      case MII_MODEL_MARVELL_E3082:
        reg |= (E1000_SCR_AUTO_X_MODE >> 1);
        reg |= E1000_SCR_ASSERT_CRS_ON_TX;
        break;
      case MII_MODEL_MARVELL_E3016:
        reg |= E1000_SCR_AUTO_MDIX;
        reg &= ~(E1000_SCR_EN_DETECT |
                 E1000_SCR_SCRAMBLER_DISABLE);
        reg |= E1000_SCR_LPNP;
        // XXX Enable class A driver for Yukon FE+ A0
        PHY_WRITE (sc_phy, 0x1C, PHY_READ (sc_phy, 0x1C) | 0x0001);
        break;
      default:
        reg &= ~E1000_SCR_AUTO_X_MODE;
        reg |= E1000_SCR_ASSERT_CRS_ON_TX;
        break;
    }
    if (sc_phy->mii_model != MII_MODEL_MARVELL_E3016) {
      /* Auto correction for reversed cable polarity. */
      reg &= ~E1000_SCR_POLARITY_REVERSAL;
    }
    PHY_WRITE (sc_phy, E1000_SCR, reg);

    if (sc_phy->mii_model == MII_MODEL_MARVELL_E1116 ||
        sc_phy->mii_model == MII_MODEL_MARVELL_E1149) {
      PHY_WRITE (sc_phy, E1000_EADR, 2);
      reg = PHY_READ (sc_phy, E1000_SCR);
      reg |= E1000_SCR_RGMII_POWER_UP;
      PHY_WRITE (sc_phy, E1000_SCR, reg);
      PHY_WRITE (sc_phy, E1000_EADR, 0);
    }
  }

  switch (sc_phy->mii_model) {
    case MII_MODEL_MARVELL_E3082:
    case MII_MODEL_MARVELL_E1112:
    case MII_MODEL_MARVELL_E1118:
      break;
    case MII_MODEL_MARVELL_E1116:
      page = PHY_READ (sc_phy, E1000_EADR);
      /* Select page 3, LED control register. */
      PHY_WRITE (sc_phy, E1000_EADR, 3);
      PHY_WRITE (sc_phy, E1000_SCR,
                 E1000_SCR_LED_LOS (1) |  /* Link/Act */
                 E1000_SCR_LED_INIT (8) |  /* 10Mbps */
                 E1000_SCR_LED_STAT1 (7) |  /* 100Mbps */
                 E1000_SCR_LED_STAT0 (7));  /* 1000Mbps */
      /* Set blink rate. */
      PHY_WRITE (sc_phy, E1000_IER, E1000_PULSE_DUR (E1000_PULSE_170MS) | E1000_BLINK_RATE (E1000_BLINK_84MS));
      PHY_WRITE (sc_phy, E1000_EADR, page);
      break;
    case MII_MODEL_MARVELL_E3016:
      /* LED2 -> ACT, LED1 -> LINK, LED0 -> SPEED. */
      PHY_WRITE (sc_phy, 0x16, 0x0B << 8 | 0x05 << 4 | 0x04);
      /* Integrated register calibration workaround. */
      PHY_WRITE (sc_phy, 0x1D, 17);
      PHY_WRITE (sc_phy, 0x1E, 0x3F60);
      break;
    default:
      /* Force TX_CLK to 25MHz clock. */
      reg = PHY_READ (sc_phy, E1000_ESCR);
      reg |= E1000_ESCR_TX_CLK_25;
      PHY_WRITE (sc_phy, E1000_ESCR, reg);
      break;
  }

  /* Reset the PHY so all changes take effect. */
  reg = PHY_READ (sc_phy, E1000_CR);
  reg |= E1000_CR_RESET;
  PHY_WRITE (sc_phy, E1000_CR, reg);
}

static void
mii_phy_update (
    struct e1000phy_softc  *sc_phy,
    INTN                   cmd
    )
{
  struct mii_softc      *sc = &sc_phy->mii_sc;
  struct mii_data       *mii = sc->mii_pdata;

  if (sc->mii_media_active != mii->mii_media_active ||
      sc->mii_media_status != mii->mii_media_status ||
      cmd == MII_MEDIACHG)
  {
    msk_miibus_statchg (sc_phy->sc_if);
    sc->mii_media_active = mii->mii_media_active;
    sc->mii_media_status = mii->mii_media_status;
  }
}

void
e1000phy_tick (
    struct e1000phy_softc  *sc_phy
    )
{
  e1000phy_service (sc_phy, MII_TICK);
}

void
e1000phy_mediachg (
    struct e1000phy_softc  *sc_phy
    )
{
  struct mii_data *mii;

  mii = sc_phy->mii_sc.mii_pdata;

  mii->mii_media_status = 0;
  mii->mii_media_active = IFM_NONE;
  e1000phy_service (sc_phy, MII_MEDIACHG);
}

static void
e1000phy_service (
    struct e1000phy_softc  *sc_phy,
    INTN                   cmd
    )
{
  struct mii_softc    *sc;
  INTN                reg;

  sc = &sc_phy->mii_sc;

  switch (cmd) {
    case MII_POLLSTAT:
      break;

    case MII_MEDIACHG:
      //
      // Always try to auto-negotiate
      //
      e1000phy_mii_phy_auto (sc_phy);
      break;

    case MII_TICK:
      /*
     * check for link.
     * Read the status register twice; Link Status is latch-low.
     */
      reg = PHY_READ (sc_phy, E1000_SR) | PHY_READ (sc_phy, E1000_SR);
      if (reg & E1000_SR_LINK_STATUS) {
        sc->mii_ticks = 0;
        break;
      }

      /* Announce link loss right after it happens. */
      if (sc->mii_ticks++ == 0) {
        break;
      }
      if (sc->mii_ticks <= sc->mii_anegticks) {
        break;
      }

      //
      // Restart the auto-negotiation
      //
      sc->mii_ticks = 0;
      e1000phy_reset (sc_phy);
      e1000phy_mii_phy_auto (sc_phy);
      break;
  }

  /* Update the media status. */
  e1000phy_status (sc_phy);

  /* Callback if something changed. */
  mii_phy_update (sc_phy, cmd);
}

static void
e1000phy_status (
    struct e1000phy_softc  *sc_phy
    )
{
  struct mii_softc    *sc;
  struct mii_data     *mii;
  INTN                bmcr;
  INTN                bmsr;
  INTN                gsr;
  INTN                ssr;
  INTN                ar;
  INTN                lpar;

  sc = &sc_phy->mii_sc;
  mii = sc->mii_pdata;

  mii->mii_media_status = IFM_AVALID;
  mii->mii_media_active = IFM_ETHER;

  bmsr = PHY_READ (sc_phy, E1000_SR) | PHY_READ (sc_phy, E1000_SR);
  bmcr = PHY_READ (sc_phy, E1000_CR);
  ssr = PHY_READ (sc_phy, E1000_SSR);

  if (bmsr & E1000_SR_LINK_STATUS) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: e1000phy_status, link up\n"));
    mii->mii_media_status |= IFM_ACTIVE;
  }

  if (bmcr & E1000_CR_LOOPBACK) {
    mii->mii_media_active |= IFM_LOOP;
  }

  if ((bmcr & E1000_CR_AUTO_NEG_ENABLE) != 0 && (ssr & E1000_SSR_SPD_DPLX_RESOLVED) == 0) {
    /* Erg, still trying, I guess... */
    DEBUG ((EFI_D_NET, "Marvell Yukon: e1000phy_status, auto negotiation not complete\n"));
    mii->mii_media_active |= IFM_NONE;
    return;
  }

  if ((sc->mii_flags & MIIF_HAVEFIBER) == 0) {
    switch (ssr & E1000_SSR_SPEED) {
      case E1000_SSR_1000MBS:
        mii->mii_media_active |= IFM_1000_T;
        break;
      case E1000_SSR_100MBS:
        mii->mii_media_active |= IFM_100_TX;
        break;
      case E1000_SSR_10MBS:
        mii->mii_media_active |= IFM_10_T;
        break;
      default:
        mii->mii_media_active |= IFM_NONE;
        return;
    }
  } else {
    /*
     * Some fiber PHY(88E1112) does not seem to set resolved
     * speed so always assume we've got IFM_1000_SX.
     */
    mii->mii_media_active |= IFM_1000_SX;
  }

  if (ssr & E1000_SSR_DUPLEX) {
    mii->mii_media_active |= IFM_FDX;
  } else {
    mii->mii_media_active |= IFM_HDX;
  }

  if ((sc->mii_flags & MIIF_HAVEFIBER) == 0) {
    ar = PHY_READ (sc_phy, E1000_AR);
    lpar = PHY_READ (sc_phy, E1000_LPAR);
    /* FLAG0==rx-flow-control FLAG1==tx-flow-control */
    if ((ar & E1000_AR_PAUSE) && (lpar & E1000_LPAR_PAUSE)) {
      mii->mii_media_active |= IFM_FLAG0 | IFM_FLAG1;
    } else if (!(ar & E1000_AR_PAUSE) && (ar & E1000_AR_ASM_DIR) &&
               (lpar & E1000_LPAR_PAUSE) && (lpar & E1000_LPAR_ASM_DIR)) {
      mii->mii_media_active |= IFM_FLAG1;
    } else if ((ar & E1000_AR_PAUSE) && (ar & E1000_AR_ASM_DIR) &&
               !(lpar & E1000_LPAR_PAUSE) && (lpar & E1000_LPAR_ASM_DIR)) {
      mii->mii_media_active |= IFM_FLAG0;
    }
  }

  /* FLAG2 : local PHY resolved to MASTER */
  if ((IFM_SUBTYPE (mii->mii_media_active) == IFM_1000_T) ||
      (IFM_SUBTYPE (mii->mii_media_active) == IFM_1000_SX)) {
    PHY_READ (sc_phy, E1000_1GSR);
    gsr = PHY_READ (sc_phy, E1000_1GSR);
    if ((gsr & E1000_1GSR_MS_CONFIG_RES) != 0) {
      mii->mii_media_active |= IFM_FLAG2;
    }
  }
}

static void
e1000phy_mii_phy_auto (
    struct e1000phy_softc  *sc_phy
    )
{
  struct mii_softc    *sc;
  UINT16              reg;

  DEBUG ((EFI_D_NET, "Marvell Yukon: e1000phy_mii_phy_auto negotiation started\n"));
  sc = &sc_phy->mii_sc;
  if ((sc->mii_flags & MIIF_HAVEFIBER) == 0) {
    reg = PHY_READ (sc_phy, E1000_AR);
    reg |= E1000_AR_10T | E1000_AR_10T_FD |
        E1000_AR_100TX | E1000_AR_100TX_FD |
        E1000_AR_PAUSE | E1000_AR_ASM_DIR;
    PHY_WRITE (sc_phy, E1000_AR, reg | E1000_AR_SELECTOR_FIELD);
  } else {
    PHY_WRITE (sc_phy, E1000_AR, E1000_FA_1000X_FD | E1000_FA_1000X | E1000_FA_SYM_PAUSE | E1000_FA_ASYM_PAUSE);
  }

  if ((sc->mii_extcapabilities & (E1000_ESR_1000T_FD | E1000_ESR_1000T)) != 0) {
    PHY_WRITE (sc_phy, E1000_1GCR, E1000_1GCR_1000T_FD | E1000_1GCR_1000T);
  }

  PHY_WRITE (sc_phy, E1000_CR, E1000_CR_AUTO_NEG_ENABLE | E1000_CR_RESTART_AUTO_NEG);
}

//
// Generic helper functions
//

const struct mii_phydesc *
    mii_phy_match_gen (
    const struct mii_attach_args    *ma,
    const struct mii_phydesc        *mpd,
    UINTN                           len
    )
{

  for (; mpd->mpd_name != NULL;
       mpd = (const struct mii_phydesc *) ((const CHAR8 *) mpd + len)) {
    if (MII_OUI (ma->mii_id1, ma->mii_id2) == mpd->mpd_oui &&
        MII_MODEL (ma->mii_id2) == mpd->mpd_model) {
      return (mpd);
    }
  }
  return (NULL);
}

const struct mii_phydesc *
    mii_phy_match (
    const struct mii_attach_args    *ma,
    const struct mii_phydesc        *mpd
    )
{

  return (mii_phy_match_gen (ma, mpd, sizeof (struct mii_phydesc)));
}

EFI_STATUS
mii_phy_dev_probe (
    const struct mii_attach_args    *ma,
    const struct mii_phydesc        *mpd
    )
{

  mpd = mii_phy_match (ma, mpd);
  if (mpd != NULL) {
    DEBUG ((EFI_D_NET, "Marvell Yukon: Found PHY (%a)\n", mpd->mpd_name));
    return EFI_SUCCESS;
  }

  DEBUG ((DEBUG_NET, "Marvell Yukon: PHY not found (OUI=0x%x, MODEL=0x%x)\n",
         MII_OUI (ma->mii_id1, ma->mii_id2), MII_MODEL (ma->mii_id2)));
  return EFI_NOT_FOUND;
}
