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

#include "ComPhyLib.h"

CHAR16 * TypeStringTable [] = {L"unconnected", L"PCIE0", L"PCIE1", L"PCIE2",
                           L"PCIE3", L"SATA0", L"SATA1", L"SATA2", L"SATA3",
                           L"SGMII0", L"SGMII1", L"SGMII2", L"SGMII3",
                           L"QSGMII", L"USB3_HOST0", L"USB3_HOST1",
                           L"USB3_DEVICE", L"XAUI0", L"XAUI1", L"XAUI2",
                           L"XAUI3", L"RXAUI0", L"RXAUI1", L"KR"};

CHAR16 * SpeedStringTable [] = {L"-", L"1.25 Gbps", L"1.5 Gbps", L"2.5 Gbps",
                                L"3.0 Gbps", L"3.125 Gbps", L"5 Gbps",
                                L"6 Gbps", L"6.25 Gbps", L"10.31 Gbps"};

CHIP_COMPHY_CONFIG ChipCfgTbl[] = {
  {
    .ChipType = L"Cp110",
    .Init = ComPhyCp110Init
  }
};

VOID
RegSet (
  IN EFI_PHYSICAL_ADDRESS Addr,
  IN UINT32 Data,
  IN UINT32 Mask
  )
{
  DEBUG((DEBUG_INFO, "Write to address = %10x, data = %10x (mask = %10x)"
    "- ", Addr, Data, Mask));
  DEBUG((DEBUG_INFO, "old value = %10x ==> ", MmioRead32 (Addr)));
  RegSetSilent (Addr, Data, Mask);
  DEBUG((DEBUG_INFO, "new value %10x\n", MmioRead32 (Addr)));
}

VOID
RegSetSilent (
  IN EFI_PHYSICAL_ADDRESS Addr,
  IN UINT32 Data,
  IN UINT32 Mask
  )
{
  UINT32 RegData;

  RegData = MmioRead32 (Addr);
  RegData &= ~Mask;
  RegData |= Data;
  MmioWrite32 (Addr, RegData);
}

VOID
RegSet16 (
  IN EFI_PHYSICAL_ADDRESS Addr,
  IN UINT16 Data,
  IN UINT16 Mask
  )
{
  DEBUG((DEBUG_INFO, "Write to address = %#010lx, Data = %#06x (mask = %#06x)"
    "- ", Addr, Data, Mask));
  DEBUG((DEBUG_INFO, "old value = %#06x ==> ", MmioRead16 (Addr)));
  RegSetSilent16 (Addr, Data, Mask);
  DEBUG((DEBUG_INFO, "new value %#06x\n", MmioRead16 (Addr)));
}

VOID
RegSetSilent16(
  IN EFI_PHYSICAL_ADDRESS Addr,
  IN UINT16 Data,
  IN UINT16 Mask
  )
{
  UINT16 RegData;
  RegData = MmioRead16(Addr);
  RegData &= ~Mask;
  RegData |= Data;
  MmioWrite16 (Addr, RegData);
}

/* This function returns enum with SerDesType */
UINT32
ParseSerdesTypeString (
  CHAR16* String
  )
{
  UINT32 i;

  if (String == NULL)
    return PHY_TYPE_INVALID;

  for (i = 0; i < PHY_TYPE_MAX; i++) {
    if (StrCmp (String, TypeStringTable[i]) == 0) {
      return i;
    }
  }

  /* PCD string doesn't match any supported SerDes Type */
  return PHY_TYPE_INVALID;
}

/* This function converts SerDes speed in MHz to enum with SerDesSpeed */
UINT32
ParseSerdesSpeed (
  UINT32 Value
  )
{
  UINT32 i;
  UINT32 ValueTable [] = {0, 1250, 1500, 2500, 3000, 3125,
                          5000, 6000, 6250, 10310};

  for (i = 0; i < 10; i++) {
    if (Value == ValueTable[i]) {
      return i;
    }
  }

  /* PCD SerDes speed value doesn't match any supported SerDes speed */
  return PHY_SPEED_INVALID;
}

CHAR16 *
GetTypeString (
  UINT32 Type
  )
{

  if (Type < 0 || Type > PHY_TYPE_MAX) {
    return L"invalid";
  }

  return TypeStringTable[Type];
}

CHAR16 *
GetSpeedString (
  UINT32 Speed
  )
{

  if (Speed < 0 || Speed > 10) {
    return L"invalid";
  }

  return SpeedStringTable[Speed];
}

VOID
ComPhyPrint (
  IN CHIP_COMPHY_CONFIG *PtrChipCfg
  )
{
  UINT32 Lane;
  CHAR16 *SpeedStr, *TypeStr;

  for (Lane = 0; Lane < PtrChipCfg->LanesCount; Lane++) {
    SpeedStr = GetSpeedString(PtrChipCfg->MapData[Lane].Speed);
    TypeStr = GetTypeString(PtrChipCfg->MapData[Lane].Type);
    DEBUG((DEBUG_ERROR, "Comphy-%d: %-13s %-10s\n", Lane, TypeStr, SpeedStr));
  }

  DEBUG((DEBUG_ERROR, "\n"));
}

EFI_STATUS
GetChipComPhyInit (
  IN CHIP_COMPHY_CONFIG *PtrChipCfg
  )
{
  UINTN i, TblSize;


  TblSize = sizeof(ChipCfgTbl) / sizeof(ChipCfgTbl[0]);

  for (i = 0; i < TblSize ; i++) {
    if (StrCmp (PtrChipCfg->ChipType, ChipCfgTbl[i].ChipType) == 0) {
      PtrChipCfg->Init = ChipCfgTbl[i].Init;
      return EFI_SUCCESS;
    }
  }

  DEBUG((DEBUG_ERROR, "ComPhy: Empty ChipType string\n"));
  return EFI_D_ERROR;
}

STATIC
VOID
InitComPhyConfig (
  IN  OUT  CHIP_COMPHY_CONFIG *ChipConfig,
  IN  OUT  PCD_LANE_MAP       *LaneData
  )
{
  /*
   * Below macro contains variable name concatenation (used to form PCD's name)
   * and that's why invoking it cannot be automated, e.g. using for loop.
   * Currently up to 4 ComPhys might be configured.
   */
  GetComPhyPcd(ChipConfig, LaneData, 0);
  GetComPhyPcd(ChipConfig, LaneData, 1);
  GetComPhyPcd(ChipConfig, LaneData, 2);
  GetComPhyPcd(ChipConfig, LaneData, 3);
}

EFI_STATUS
MvComPhyInit (
  VOID
  )
{
  EFI_STATUS Status;
  CHIP_COMPHY_CONFIG ChipConfig[MAX_CHIPS], *PtrChipCfg;
  PCD_LANE_MAP LaneData[MAX_CHIPS];
  UINT32 Lane, ChipCount, i, MaxComphyCount;

  ChipCount = PcdGet32 (PcdComPhyChipCount);

  InitComPhyConfig(ChipConfig, LaneData);

  if (ChipCount <= 0 || ChipCount > MAX_CHIPS)
    return EFI_INVALID_PARAMETER;

  for (i = 0; i < ChipCount ; i++) {
    PtrChipCfg = &ChipConfig[i];

    /* Get the count of the SerDes of the specific chip */
    MaxComphyCount = PtrChipCfg->LanesCount;
    for (Lane = 0; Lane < MaxComphyCount; Lane++) {
      /* Parse PCD with string indicating SerDes Type */
      PtrChipCfg->MapData[Lane].Type =
        ParseSerdesTypeString (LaneData[i].TypeStr[Lane]);
      PtrChipCfg->MapData[Lane].Speed =
        ParseSerdesSpeed (LaneData[i].SpeedValue[Lane]);
      PtrChipCfg->MapData[Lane].Invert = (UINT32) LaneData[i].InvFlag[Lane];

      if ((PtrChipCfg->MapData[Lane].Speed == PHY_SPEED_INVALID) ||
          (PtrChipCfg->MapData[Lane].Speed == PHY_SPEED_ERROR) ||
          (PtrChipCfg->MapData[Lane].Type == PHY_TYPE_INVALID)) {
        DEBUG((DEBUG_ERROR, "ComPhy: No valid phy speed or type for lane %d, "
          "setting lane as unconnected\n", Lane + 1));
        PtrChipCfg->MapData[Lane].Type = PHY_TYPE_UNCONNECTED;
        PtrChipCfg->MapData[Lane].Speed = PHY_SPEED_INVALID;
      }
    };

    Status = GetChipComPhyInit (PtrChipCfg);
    if (EFI_ERROR(Status)) {
     DEBUG((DEBUG_ERROR, "ComPhy: Invalid Chip%dType name\n", i));
     return Status;
    }

    ComPhyPrint (PtrChipCfg);

    /* PHY power UP sequence */
    PtrChipCfg->Init (PtrChipCfg);
  }

  return EFI_SUCCESS;
}
