/**  <at> file
*  Media Independent Interface configuration definitions. Ported from FreeBSD.
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
 * Copyright (c) 1998, 1999 The NetBSD Foundation, Inc.
 * All rights reserved.
 *
 * This code is derived from software contributed to The NetBSD Foundation
 * by Jason R. Thorpe of the Numerical Aerospace Simulation Facility,
 * NASA Ames Research Center.
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
 *  This product includes software developed by the NetBSD
 *  Foundation, Inc. and its contributors.
 * 4. Neither the name of The NetBSD Foundation nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE NETBSD FOUNDATION, INC. AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * $FreeBSD: src/sys/dev/mii/miivar.h,v 1.21.10.1.4.1 2010/06/14 02:09:06 kensmith Exp $
 */

#ifndef _DEV_MII_MIIVAR_H_
#define  _DEV_MII_MIIVAR_H_

/*
 * A network interface driver has one of these structures in its softc.
 * It is the interface from the network interface driver to the MII
 * layer.
 */
struct mii_data {
  /*
   * PHY driver fills this in with active media status.
   */
  INTN mii_media_status;
  INTN mii_media_active;
};

/*
 * Requests that can be made to the downcall.
 */
#define  MII_TICK                    1  /* once-per-second tick */
#define  MII_MEDIACHG                2  /* user changed media; perform the switch */
#define  MII_POLLSTAT                3  /* user requested media status; fill it in */

/*
 * Each PHY driver's softc has one of these as the first member.
 * XXX This would be better named "phy_softc", but this is the name
 * XXX BSDI used, and we would like to have the same interface.
 */
struct mii_softc {
  struct mii_data *mii_pdata;  /* pointer to parent's mii_data */

  INTN mii_flags;           /* misc. flags; see below */
  INTN mii_capabilities;    /* capabilities from BMSR */
  INTN mii_extcapabilities; /* extended capabilities */
  INTN mii_ticks;           /* MII_TICK counter */
  INTN mii_anegticks;       /* ticks before retrying aneg */
  INTN mii_media_active;    /* last active media */
  INTN mii_media_status;    /* last active status */
};

/* mii_flags */
#define  MIIF_INITDONE              0x0001    /* has been initialized (mii_data) */
#define  MIIF_NOISOLATE             0x0002    /* do not isolate the PHY */
#define  MIIF_NOLOOP                0x0004    /* no loopback capability */
#define MIIF_AUTOTSLEEP             0x0010    /* use tsleep(), not callout() */
#define MIIF_HAVEFIBER              0x0020    /* from parent: has fiber interface */
#define  MIIF_HAVE_GTCR             0x0040    /* has 100base-T2/1000base-T CR */
#define  MIIF_IS_1000X              0x0080    /* is a 1000BASE-X device */
#define  MIIF_DOPAUSE               0x0100    /* advertise PAUSE capability */
#define  MIIF_IS_HPNA               0x0200    /* is a HomePNA device */
#define  MIIF_FORCEANEG             0x0400    /* force auto-negotiation */

/* Default mii_anegticks values */
#define  MII_ANEGTICKS              5
#define  MII_ANEGTICKS_GIGE         17

#define  MIIF_INHERIT_MASK          (MIIF_NOISOLATE|MIIF_NOLOOP|MIIF_AUTOTSLEEP)

#define MII_OUI_MARVELL             0x005043
#define MII_OUI_xxMARVELL           0x000ac2

#define MII_MODEL_MARVELL_E1000     0x0000
#define MII_MODEL_MARVELL_E1011     0x0002
#define MII_MODEL_MARVELL_E1000_3   0x0003
#define MII_MODEL_MARVELL_E1000S    0x0004
#define MII_MODEL_MARVELL_E1000_5   0x0005
#define MII_MODEL_MARVELL_E1000_6   0x0006
#define MII_MODEL_MARVELL_E3082     0x0008
#define MII_MODEL_MARVELL_E1112     0x0009
#define MII_MODEL_MARVELL_E1149     0x000b
#define MII_MODEL_MARVELL_E1111     0x000c
#define MII_MODEL_MARVELL_E1116     0x0021
#define MII_MODEL_MARVELL_E1116R    0x0024
#define MII_MODEL_MARVELL_E1118     0x0022
#define MII_MODEL_MARVELL_E3016     0x0026
#define MII_MODEL_MARVELL_PHYG65G   0x0027
#define MII_MODEL_xxMARVELL_E1000   0x0000
#define MII_MODEL_xxMARVELL_E1011   0x0002
#define MII_MODEL_xxMARVELL_E1000_3 0x0003
#define MII_MODEL_xxMARVELL_E1000_5 0x0005
#define MII_MODEL_xxMARVELL_E1111   0x000c

#define MII_STR_MARVELL_E1000       "Marvell 88E1000 Gigabit PHY"
#define MII_STR_MARVELL_E1011       "Marvell 88E1011 Gigabit PHY"
#define MII_STR_MARVELL_E1000_3     "Marvell 88E1000_3 Gigabit PHY"
#define MII_STR_MARVELL_E1000S      "Marvell 88E1000S Gigabit PHY"
#define MII_STR_MARVELL_E1000_5     "Marvell 88E1000_5 Gigabit PHY"
#define MII_STR_MARVELL_E1000_6     "Marvell 88E1000_6 Gigabit PHY"
#define MII_STR_MARVELL_E3082       "Marvell 88E3082 10/100 Fast Ethernet PHY"
#define MII_STR_MARVELL_E1112       "Marvell 88E1112 Gigabit PHY"
#define MII_STR_MARVELL_E1149       "Marvell 88E1149 Gigabit PHY"
#define MII_STR_MARVELL_E1111       "Marvell 88E1111 Gigabit PHY"
#define MII_STR_MARVELL_E1116       "Marvell 88E1116 Gigabit PHY"
#define MII_STR_MARVELL_E1116R      "Marvell 88E1116R Gigabit PHY"
#define MII_STR_MARVELL_E1118       "Marvell 88E1118 Gigabit PHY"
#define MII_STR_MARVELL_E3016       "Marvell 88E3016 10/100 Fast Ethernet PHY"
#define MII_STR_MARVELL_PHYG65G     "Marvell PHYG65G Gigabit PHY"
#define MII_STR_xxMARVELL_E1000     "Marvell 88E1000 Gigabit PHY"
#define MII_STR_xxMARVELL_E1011     "Marvell 88E1011 Gigabit PHY"
#define MII_STR_xxMARVELL_E1000_3   "Marvell 88E1000_3 Gigabit PHY"
#define MII_STR_xxMARVELL_E1000_5   "Marvell 88E1000_5 Gigabit PHY"
#define MII_STR_xxMARVELL_E1111     "Marvell 88E1111 Gigabit PHY"

/*
 * Used to attach a PHY to a parent.
 */
struct mii_attach_args {
  struct mii_data *mii_data;  /* pointer to parent data */
  INTN mii_id1;      /* PHY ID register 1 */
  INTN mii_id2;      /* PHY ID register 2 */
};

/*
 * Used to match a PHY.
 */
struct mii_phydesc {
  UINT32      mpd_oui;    /* the PHY's OUI */
  UINT32      mpd_model;  /* the PHY's model */
  const CHAR8 *mpd_name;  /* the PHY's name */
};
#define MII_PHY_DESC(a, b)          { MII_OUI_ ## a, MII_MODEL_ ## a ## _ ## b, MII_STR_ ## a ## _ ## b }
#define MII_PHY_END                 { 0, 0, NULL }

#define PHY_READ(p, r)           msk_phy_readreg ((p)->sc_if, (r))

#define PHY_WRITE(p, r, v)       msk_phy_writereg ((p)->sc_if, (r), (v))

struct msk_mii_data {
  INTN    port;
  UINT32  pmd;
  INTN    mii_flags;
};

#endif /* _DEV_MII_MIIVAR_H_ */
