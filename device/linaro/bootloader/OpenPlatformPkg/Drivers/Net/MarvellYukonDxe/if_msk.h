/**  <at> file
*  API to ported msk driver
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

#ifndef _IF_MSK_H_
#define _IF_MSK_H_

#include <Uefi.h>
#include <Protocol/PciIo.h>
#include "if_mskreg.h"
#include "miivar.h"

#define MAX_SUPPORTED_PACKET_SIZE   (1566) /* No jumbo frame size support */

EFI_STATUS mskc_probe (EFI_PCI_IO_PROTOCOL *PciIo);

EFI_STATUS mskc_attach (EFI_PCI_IO_PROTOCOL  *, struct msk_softc **);
EFI_STATUS mskc_attach_if (struct msk_if_softc *, UINTN);
VOID mskc_detach (struct msk_softc  *);
VOID mskc_detach_if (struct msk_if_softc *);

EFI_STATUS mskc_init (struct msk_if_softc *);
VOID mskc_shutdown (struct msk_softc  *);
VOID mskc_stop_if (struct msk_if_softc *);

void
mskc_rxfilter (
    IN struct msk_if_softc         *sc_if,
    IN UINT32                      FilterFlags,
    IN UINTN                       MCastFilterCnt,
    IN EFI_MAC_ADDRESS             *MCastFilter
    );

EFI_STATUS
mskc_transmit (
    IN struct msk_if_softc         *sc_if,
    IN UINTN                       BufferSize,
    IN VOID                        *Buffer
    );

EFI_STATUS
mskc_receive (
    IN struct msk_if_softc         *sc_if,
    IN OUT UINTN                   *BufferSize,
    OUT VOID                       *Buffer
    );

void
mskc_getstatus (
    IN struct msk_if_softc         *sc,
    OUT UINT32                     *InterruptStatus, OPTIONAL
    OUT VOID                       **TxBuf           OPTIONAL
    );

#endif /* _IF_MSK_H_ */
