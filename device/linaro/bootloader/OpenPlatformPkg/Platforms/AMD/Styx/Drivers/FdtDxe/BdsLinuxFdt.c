/** @file
*
*  Copyright (c) 2011-2014, ARM Limited. All rights reserved.<BR>
*  Copyright (c) 2014 - 2016, AMD Inc. All rights reserved.<BR>
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
/**

  Derived from:
   ArmPkg/Library/BdsLib/BdsLinuxFdt.c

**/

#include <Library/PcdLib.h>
#include <libfdt.h>

#include <Library/BdsLib/BdsInternal.h>

#include <Guid/ArmMpCoreInfo.h>
#include <Protocol/AmdMpCoreInfo.h>

#define LINUX_FDT_MAX_OFFSET      (PcdGet64 (PcdSystemMemoryBase) + PcdGet32(PcdArmLinuxFdtMaxOffset))


// Additional size that could be used for FDT entries added by the UEFI OS Loader
// Estimation based on: EDID (300bytes) + bootargs (200bytes) + initrd region (20bytes)
//                      + system memory region (20bytes) + mp_core entries (200 bytes)
#define FDT_ADDITIONAL_ENTRIES_SIZE     0x300


EFI_STATUS
GetSystemMemoryResources (
  IN  LIST_ENTRY *ResourceList
  );

VOID
DebugDumpFdt (
  IN VOID*                FdtBlob
  );

#define ALIGN(x, a)     (((x) + ((a) - 1)) & ~((a) - 1))
#define PALIGN(p, a)    ((void *)(ALIGN((unsigned long)(p), (a))))
#define GET_CELL(p)     (p += 4, *((const UINT32 *)(p-4)))

//
// PMU interrupts per core
//
#pragma pack(push, 1)
typedef struct {
  UINT32 Flag;          // 0 == SPI
  UINT32 IntId;         // GSIV == IntId+32
  UINT32 Type;          // 4 == Level-Sensitive, Active-High
} PMU_INTERRUPT;
#pragma pack(pop)

#define PMU_INT_FLAG_SPI        0
#define PMU_INT_TYPE_HIGH_LEVEL 4


typedef struct {
  UINTN   Base;
  UINTN   Size;
} FdtRegion;


STATIC
UINTN
cpu_to_fdtn (UINTN x) {
  if (sizeof (UINTN) == sizeof (UINT32)) {
    return cpu_to_fdt32 (x);
  } else {
    return cpu_to_fdt64 (x);
  }
}


STATIC
BOOLEAN
ClusterInRange(
  IN ARM_CORE_INFO *ArmCoreInfoTable,
  IN UINTN         ClusterId,
  IN UINTN         LowIndex,
  IN UINTN         HighIndex
  )
{
  do {
    if (ClusterId == ArmCoreInfoTable[LowIndex].ClusterId)
      return TRUE;
  } while (++LowIndex <= HighIndex);

  return FALSE;
}


STATIC
UINTN
NumberOfCoresInCluster(
  IN ARM_CORE_INFO *ArmCoreInfoTable,
  IN UINTN         NumberOfEntries,
  IN UINTN         ClusterId
  )
{
  UINTN Index, Cores;

  Cores = 0;
  for (Index = 0; Index < NumberOfEntries; ++Index) {
    if (ClusterId == ArmCoreInfoTable[Index].ClusterId)
      ++Cores;
  }

  return Cores;
}


STATIC
UINTN
NumberOfClustersInTable(
  IN ARM_CORE_INFO *ArmCoreInfoTable,
  IN UINTN         NumberOfEntries
  )
{
  UINTN Index, Cores, Clusters, ClusterId;

  Index = 0;
  Clusters = 0;
  Cores = NumberOfEntries;
  while (Cores) {
     ++Clusters;
     ClusterId = ArmCoreInfoTable[Index].ClusterId;
     Cores -= NumberOfCoresInCluster (ArmCoreInfoTable,
                                      NumberOfEntries,
                                      ClusterId);
     if (Cores) {
       do {
         ++Index;
       } while (ClusterInRange (ArmCoreInfoTable,
                                ArmCoreInfoTable[Index].ClusterId,
                                0, Index-1));
     }
  }

  return Clusters;
}


STATIC
int
fdt_alloc_phandle(
  IN VOID *blob
  )
{

  int offset, phandle = 0;

  for (offset = fdt_next_node(blob, -1, NULL); offset >= 0;
       offset = fdt_next_node(blob, offset, NULL)) {
       phandle = MAX(phandle, fdt_get_phandle(blob, offset));
  }

  return phandle + 1;
}


STATIC
BOOLEAN
IsLinuxReservedRegion (
  IN EFI_MEMORY_TYPE MemoryType
  )
{
  switch(MemoryType) {
  case EfiRuntimeServicesCode:
  case EfiRuntimeServicesData:
  case EfiUnusableMemory:
  case EfiACPIReclaimMemory:
  case EfiACPIMemoryNVS:
  case EfiReservedMemoryType:
    return TRUE;
  default:
    return FALSE;
  }
}

STATIC
VOID
SetDeviceStatus (
  IN VOID *fdt,
  IN CHAR8 *device,
  IN BOOLEAN enable
  )
{
  int node, subnode, rc;

  node = fdt_subnode_offset (fdt, 0, "smb");
  if (node >= 0) {
    subnode = fdt_subnode_offset (fdt, node, device);
    if (subnode >= 0) {
      rc = fdt_setprop_string(fdt, subnode, "status", enable ? "ok" : "disabled");
      if (rc) {
        DEBUG((EFI_D_ERROR,"%a: Could not set 'status' property for '%a' node\n",
            __FUNCTION__, device));
      }
    }
  }
}

#if DO_XGBE
STATIC
VOID
SetMacAddress (
  IN VOID *fdt,
  IN CHAR8 *device,
  IN UINT64 mac_addr
  )
{
  int node, subnode, rc;

  node = fdt_subnode_offset (fdt, 0, "smb");
  if (node >= 0) {
    subnode = fdt_subnode_offset (fdt, node, device);
    if (subnode >= 0) {
      rc = fdt_setprop(fdt, subnode, "mac-address", (void *)&mac_addr, 6);
      if (rc) {
        DEBUG((EFI_D_ERROR,"%a: Could not set 'mac-address' property for '%a' node\n",
            __FUNCTION__, device));
      }
    }
  }
}
#endif

VOID
SetSocIdStatus (
  IN VOID *fdt
  )
{
  UINT32                SocId;
  BOOLEAN               IsRevB1;

  SocId = PcdGet32 (PcdSocCpuId);
  IsRevB1 = (SocId & 0xFF0) && (SocId & 0x00F);

  SetDeviceStatus (fdt, "sata@e0d00000",
    IsRevB1 && FixedPcdGet8(PcdSata1PortCount) > 0);
  SetDeviceStatus (fdt, "gpio@e0020000", IsRevB1);
  SetDeviceStatus (fdt, "gpio@e0030000", IsRevB1);
  SetDeviceStatus (fdt, "gwdt@e0bb0000", IsRevB1);
#if DO_KCS
  SetDeviceStatus (fdt, "kcs@e0010000", IsRevB1);
#else
  SetDeviceStatus (fdt, "kcs@e0010000", FALSE);
#endif
}

VOID
SetXgbeStatus (
  IN VOID *fdt
  )
{
#if DO_XGBE
  SetDeviceStatus (fdt, "xgmac@e0700000", TRUE);
  SetDeviceStatus (fdt, "phy@e1240800", TRUE);
  SetDeviceStatus (fdt, "xgmac@e0900000", TRUE);
  SetDeviceStatus (fdt, "phy@e1240c00", TRUE);

  SetMacAddress (fdt, "xgmac@e0700000", PcdGet64 (PcdEthMacA));
  SetMacAddress (fdt, "xgmac@e0900000", PcdGet64 (PcdEthMacB));
#else
  SetDeviceStatus (fdt, "xgmac@e0700000", FALSE);
  SetDeviceStatus (fdt, "phy@e1240800", FALSE);
  SetDeviceStatus (fdt, "xgmac@e0900000", FALSE);
  SetDeviceStatus (fdt, "phy@e1240c00", FALSE);
#endif
}


/**
** Relocate the FDT blob to a more appropriate location for the Linux kernel.
** This function will allocate memory for the relocated FDT blob.
**
** @retval EFI_SUCCESS on success.
** @retval EFI_OUT_OF_RESOURCES or EFI_INVALID_PARAMETER on failure.
*/
STATIC
EFI_STATUS
RelocateFdt (
  EFI_PHYSICAL_ADDRESS   OriginalFdt,
  UINTN                  OriginalFdtSize,
  EFI_PHYSICAL_ADDRESS   *RelocatedFdt,
  UINTN                  *RelocatedFdtSize,
  EFI_PHYSICAL_ADDRESS   *RelocatedFdtAlloc
  )
{
  EFI_STATUS            Status;
  INTN                  Error;
  UINT64                FdtAlignment;

  *RelocatedFdtSize = OriginalFdtSize + FDT_ADDITIONAL_ENTRIES_SIZE;

  // If FDT load address needs to be aligned, allocate more space.
  FdtAlignment = PcdGet32 (PcdArmLinuxFdtAlignment);
  if (FdtAlignment != 0) {
    *RelocatedFdtSize += FdtAlignment;
  }

  // Try below a watermark address.
  Status = EFI_NOT_FOUND;
  if (PcdGet32 (PcdArmLinuxFdtMaxOffset) != 0) {
    *RelocatedFdt = LINUX_FDT_MAX_OFFSET;
    Status = gBS->AllocatePages (AllocateMaxAddress, EfiBootServicesData,
                    EFI_SIZE_TO_PAGES (*RelocatedFdtSize), RelocatedFdt);
    if (EFI_ERROR (Status)) {
      DEBUG ((EFI_D_WARN, "Warning: Failed to load FDT below address 0x%lX (%r). Will try again at a random address anywhere.\n", *RelocatedFdt, Status));
    }
  }

  // Try anywhere there is available space.
  if (EFI_ERROR (Status)) {
    Status = gBS->AllocatePages (AllocateAnyPages, EfiBootServicesData,
                    EFI_SIZE_TO_PAGES (*RelocatedFdtSize), RelocatedFdt);
    if (EFI_ERROR (Status)) {
      ASSERT_EFI_ERROR (Status);
      return EFI_OUT_OF_RESOURCES;
    } else {
      DEBUG ((EFI_D_WARN, "WARNING: Loaded FDT at random address 0x%lX.\nWARNING: There is a risk of accidental overwriting by other code/data.\n", *RelocatedFdt));
    }
  }

  *RelocatedFdtAlloc = *RelocatedFdt;
  if (FdtAlignment != 0) {
    *RelocatedFdt = ALIGN (*RelocatedFdt, FdtAlignment);
  }

  // Load the Original FDT tree into the new region
  Error = fdt_open_into ((VOID*)(UINTN) OriginalFdt,
            (VOID*)(UINTN)(*RelocatedFdt), *RelocatedFdtSize);
  if (Error) {
    DEBUG ((EFI_D_ERROR, "fdt_open_into(): %a\n", fdt_strerror (Error)));
    gBS->FreePages (*RelocatedFdtAlloc, EFI_SIZE_TO_PAGES (*RelocatedFdtSize));
    return EFI_INVALID_PARAMETER;
  }

  DEBUG_CODE_BEGIN();
    // DebugDumpFdt ((VOID*)(UINTN)(*RelocatedFdt));
  DEBUG_CODE_END();

  return EFI_SUCCESS;
}


EFI_STATUS
AmdStyxPrepareFdt (
  IN     CONST CHAR8*         CommandLineArguments,
  IN     EFI_PHYSICAL_ADDRESS InitrdImage,
  IN     UINTN                InitrdImageSize,
  IN OUT EFI_PHYSICAL_ADDRESS *FdtBlobBase,
  IN OUT UINTN                *FdtBlobSize
  )
{
  EFI_STATUS            Status;
  EFI_PHYSICAL_ADDRESS  NewFdtBlobBase;
  EFI_PHYSICAL_ADDRESS  NewFdtBlobAllocation;
  UINTN                 NewFdtBlobSize;
  VOID                 *fdt;
  int                   err;
  int                   node;
  int                   cpu_node;
  int                   lenp;
  CONST VOID           *BootArg;
  EFI_PHYSICAL_ADDRESS  InitrdImageStart;
  EFI_PHYSICAL_ADDRESS  InitrdImageEnd;
  FdtRegion             Region;
  UINTN                 Index;
  CHAR8                 Name[10];
  LIST_ENTRY            ResourceList;
  BDS_SYSTEM_MEMORY_RESOURCE  *Resource;
  ARM_CORE_INFO         *ArmCoreInfoTable;
  UINTN                 ArmCoreCount;
  UINT32                PrimaryClusterId;
  UINT32                PrimaryCoreId;
  UINTN                 MemoryMapSize;
  EFI_MEMORY_DESCRIPTOR *MemoryMap;
  EFI_MEMORY_DESCRIPTOR *MemoryMapPtr;
  UINTN                 MapKey;
  UINTN                 DescriptorSize;
  UINT32                DescriptorVersion;
  UINTN                 Pages;
  UINTN                 OriginalFdtSize;
  int                   map_node;
  int                   cluster_node;
  int                   pmu_node;
  PMU_INTERRUPT         PmuInt;
  int                   phandle[NUM_CORES];
  UINT32                ClusterIndex, CoreIndex;
  UINT32                ClusterCount, CoresInCluster;
  UINT32                ClusterId;
  UINTN                 MpId, MbAddr;
  AMD_MP_CORE_INFO_PROTOCOL *AmdMpCoreInfoProtocol;

  //
  // Sanity checks on the original FDT blob.
  //
  err = fdt_check_header ((VOID*)(UINTN)(*FdtBlobBase));
  if (err != 0) {
    Print (L"ERROR: Device Tree header not valid (err:%d)\n", err);
    return EFI_INVALID_PARAMETER;
  }

  // The original FDT blob might have been loaded partially.
  // Check that it is not the case.
  OriginalFdtSize = (UINTN)fdt_totalsize ((VOID*)(UINTN)(*FdtBlobBase));
  if (OriginalFdtSize > *FdtBlobSize) {
    Print (L"ERROR: Incomplete FDT. Only %d/%d bytes have been loaded.\n",
           *FdtBlobSize, OriginalFdtSize);
    return EFI_INVALID_PARAMETER;
  }

  //
  // Relocate the FDT to its final location.
  //
  NewFdtBlobAllocation = 0;
  Status = RelocateFdt (*FdtBlobBase, OriginalFdtSize,
             &NewFdtBlobBase, &NewFdtBlobSize, &NewFdtBlobAllocation);
  if (EFI_ERROR (Status)) {
    goto FAIL_RELOCATE_FDT;
  }
  fdt = (VOID*)(UINTN)NewFdtBlobBase;

  node = fdt_subnode_offset (fdt, 0, "chosen");
  if (node < 0) {
    // The 'chosen' node does not exist, create it
    node = fdt_add_subnode(fdt, 0, "chosen");
    if (node < 0) {
      DEBUG((EFI_D_ERROR,"Error on finding 'chosen' node\n"));
      Status = EFI_INVALID_PARAMETER;
      goto FAIL_COMPLETE_FDT;
    }
  }

  DEBUG_CODE_BEGIN();
    BootArg = fdt_getprop(fdt, node, "bootargs", &lenp);
    if (BootArg != NULL) {
      DEBUG((EFI_D_ERROR,"BootArg: %a\n",BootArg));
    }
  DEBUG_CODE_END();

  //
  // Set Linux CmdLine
  //
  if ((CommandLineArguments != NULL) && (AsciiStrLen (CommandLineArguments) > 0)) {
    err = fdt_setprop(fdt, node, "bootargs", CommandLineArguments, AsciiStrSize(CommandLineArguments));
    if (err) {
      DEBUG((EFI_D_ERROR,"Fail to set new 'bootarg' (err:%d)\n",err));
    }
  }

  //
  // Set Linux Initrd
  //
  if (InitrdImageSize != 0) {
    InitrdImageStart = cpu_to_fdt64 (InitrdImage);
    err = fdt_setprop(fdt, node, "linux,initrd-start", &InitrdImageStart, sizeof(EFI_PHYSICAL_ADDRESS));
    if (err) {
      DEBUG((EFI_D_ERROR,"Fail to set new 'linux,initrd-start' (err:%d)\n",err));
    }
    InitrdImageEnd = cpu_to_fdt64 (InitrdImage + InitrdImageSize);
    err = fdt_setprop(fdt, node, "linux,initrd-end", &InitrdImageEnd, sizeof(EFI_PHYSICAL_ADDRESS));
    if (err) {
      DEBUG((EFI_D_ERROR,"Fail to set new 'linux,initrd-start' (err:%d)\n",err));
    }
  }

  //
  // Set Physical memory setup if does not exist
  //
  node = fdt_subnode_offset(fdt, 0, "memory");
  if (node < 0) {
    // The 'memory' node does not exist, create it
    node = fdt_add_subnode(fdt, 0, "memory");
    if (node >= 0) {
      fdt_setprop_string(fdt, node, "name", "memory");
      fdt_setprop_string(fdt, node, "device_type", "memory");

      GetSystemMemoryResources (&ResourceList);
      Resource = (BDS_SYSTEM_MEMORY_RESOURCE*)ResourceList.ForwardLink;

      Region.Base = cpu_to_fdtn ((UINTN)Resource->PhysicalStart);
      Region.Size = cpu_to_fdtn ((UINTN)Resource->ResourceLength);

      err = fdt_setprop(fdt, node, "reg", &Region, sizeof(Region));
      if (err) {
        DEBUG((EFI_D_ERROR,"Fail to set new 'memory region' (err:%d)\n",err));
      }
    }
  }

  //
  // Add the memory regions reserved by the UEFI Firmware
  //

  // Retrieve the UEFI Memory Map
  MemoryMap = NULL;
  MemoryMapSize = 0;
  Status = gBS->GetMemoryMap (&MemoryMapSize, MemoryMap, &MapKey, &DescriptorSize, &DescriptorVersion);
  if (Status == EFI_BUFFER_TOO_SMALL) {
    // The UEFI specification advises to allocate more memory for the MemoryMap buffer between successive
    // calls to GetMemoryMap(), since allocation of the new buffer may potentially increase memory map size.
    Pages = EFI_SIZE_TO_PAGES (MemoryMapSize) + 1;
    MemoryMap = AllocatePages (Pages);
    if (MemoryMap == NULL) {
      Status = EFI_OUT_OF_RESOURCES;
      goto FAIL_COMPLETE_FDT;
    }
    Status = gBS->GetMemoryMap (&MemoryMapSize, MemoryMap, &MapKey, &DescriptorSize, &DescriptorVersion);
  }

  // Go through the list and add the reserved region to the Device Tree
  if (!EFI_ERROR(Status)) {
    MemoryMapPtr = MemoryMap;
    for (Index = 0; Index < (MemoryMapSize / DescriptorSize); Index++) {
      if (IsLinuxReservedRegion ((EFI_MEMORY_TYPE)MemoryMapPtr->Type)) {
        DEBUG((DEBUG_VERBOSE, "Reserved region of type %d [0x%lX, 0x%lX]\n",
            MemoryMapPtr->Type,
            (UINTN)MemoryMapPtr->PhysicalStart,
            (UINTN)(MemoryMapPtr->PhysicalStart + MemoryMapPtr->NumberOfPages * EFI_PAGE_SIZE)));
        err = fdt_add_mem_rsv(fdt, MemoryMapPtr->PhysicalStart, MemoryMapPtr->NumberOfPages * EFI_PAGE_SIZE);
        if (err != 0) {
          Print(L"Warning: Fail to add 'memreserve' (err:%d)\n", err);
        }
      }
      MemoryMapPtr = (EFI_MEMORY_DESCRIPTOR*)((UINTN)MemoryMapPtr + DescriptorSize);
    }
  }

  //
  // Setup Arm Mpcore Info if it is a multi-core or multi-cluster platforms.
  //
  // For 'cpus' and 'cpu' device tree nodes bindings, refer to this file
  // in the kernel documentation:
  // Documentation/devicetree/bindings/arm/cpus.txt
  //
  Status = gBS->LocateProtocol (
               &gAmdMpCoreInfoProtocolGuid,
               NULL,
               (VOID **)&AmdMpCoreInfoProtocol
               );
  ASSERT_EFI_ERROR (Status);

  // Get pointer to ARM core info table
  ArmCoreInfoTable = AmdMpCoreInfoProtocol->GetArmCoreInfoTable (&ArmCoreCount);
  ASSERT (ArmCoreInfoTable != NULL);
  ASSERT (ArmCoreCount <= NUM_CORES);

  // Get Id from primary CPU
  MpId = (UINTN) ArmReadMpidr ();
  PrimaryClusterId = GET_CLUSTER_ID((UINT32) MpId);
  PrimaryCoreId    = GET_CORE_ID((UINT32) MpId);

  // Remove existing 'pmu' node and create a new one
  pmu_node = fdt_subnode_offset (fdt, 0, "pmu");
  if (pmu_node >= 0) {
    fdt_del_node (fdt, pmu_node);
  }
  pmu_node = fdt_add_subnode(fdt, 0, "pmu");
  if (pmu_node >= 0) {
    // append PMU interrupts
    for (Index = 0; Index < ArmCoreCount; Index++) {
      MpId = (UINTN) GET_MPID (ArmCoreInfoTable[Index].ClusterId,
                               ArmCoreInfoTable[Index].CoreId);

      Status = AmdMpCoreInfoProtocol->GetPmuSpiFromMpId (MpId, &PmuInt.IntId);
      if (EFI_ERROR (Status)) {
        DEBUG ((EFI_D_ERROR, "FDT: Error getting PMU interrupt for MpId '0x%x'\n", MpId));
        goto FAIL_COMPLETE_FDT;
      }

      PmuInt.Flag = cpu_to_fdt32(PMU_INT_FLAG_SPI);
      PmuInt.IntId = cpu_to_fdt32(PmuInt.IntId);
      PmuInt.Type = cpu_to_fdt32(PMU_INT_TYPE_HIGH_LEVEL);
      fdt_appendprop(fdt, pmu_node, "interrupts", &PmuInt, sizeof(PmuInt));
    }
    fdt_setprop_string(fdt, pmu_node, "compatible", "arm,armv8-pmuv3");
  } else {
    DEBUG((EFI_D_ERROR,"FDT: Error creating 'pmu' node\n"));
    Status = EFI_INVALID_PARAMETER;
    goto FAIL_COMPLETE_FDT;
  }

  // Remove existing 'psci' node if feature not supported
  node = fdt_subnode_offset (fdt, 0, "psci");
  if (node >= 0) {
    if (!FixedPcdGetBool (PcdPsciOsSupport)) {
      fdt_del_node (fdt, node);
    }
  } else if (FixedPcdGetBool (PcdPsciOsSupport) &&
      FixedPcdGetBool (PcdTrustedFWSupport)) {
    // Add 'psci' node if not present
    node = fdt_add_subnode(fdt, 0, "psci");
    if (node >= 0) {
      fdt_setprop_string(fdt, node, "compatible", "arm,psci-0.2");
      fdt_appendprop_string(fdt, node, "compatible", "arm,psci");
      fdt_setprop_string(fdt, node, "method", "smc");
    } else {
      DEBUG((EFI_D_ERROR,"FDT: Error creating 'psci' node\n"));
      Status = EFI_INVALID_PARAMETER;
      goto FAIL_COMPLETE_FDT;
    }
  }

  // Remove existing 'cpus' node and create a new one
  node = fdt_subnode_offset (fdt, 0, "cpus");
  if (node >= 0) {
    fdt_del_node (fdt, node);
  }
  node = fdt_add_subnode(fdt, 0, "cpus");
  if (node >= 0) {
    // Configure the 'cpus' node
    fdt_setprop_string(fdt, node, "name", "cpus");
    fdt_setprop_cell (fdt, node, "#address-cells", sizeof (UINTN) / 4);
    fdt_setprop_cell(fdt, node, "#size-cells", 0);
  } else {
    DEBUG((EFI_D_ERROR,"FDT: Error creating 'cpus' node\n"));
    Status = EFI_INVALID_PARAMETER;
    goto FAIL_COMPLETE_FDT;
  }

  //
  // Walk the processor table in reverse order for proper listing in FDT
  //
  Index = ArmCoreCount;
  while (Index--) {
    // Create 'cpu' node
    AsciiSPrint (Name, sizeof(Name), "CPU%d", Index);
    cpu_node = fdt_add_subnode (fdt, node, Name);
    if (cpu_node < 0) {
      DEBUG ((EFI_D_ERROR, "FDT: Error on creating '%a' node\n", Name));
      Status = EFI_INVALID_PARAMETER;
      goto FAIL_COMPLETE_FDT;
    }
    phandle[Index] = fdt_alloc_phandle(fdt);
    fdt_setprop_cell (fdt, cpu_node, "phandle", phandle[Index]);
    fdt_setprop_cell (fdt, cpu_node, "linux,phandle", phandle[Index]);

    if (FixedPcdGetBool (PcdPsciOsSupport) &&
      FixedPcdGetBool (PcdTrustedFWSupport)) {
      fdt_setprop_string(fdt, cpu_node, "enable-method", "psci");
    } else {
      fdt_setprop_string(fdt, cpu_node, "enable-method", "spin-table");
      MbAddr = ArmCoreInfoTable[Index].MailboxSetAddress;
      MbAddr = cpu_to_fdtn (MbAddr);
      fdt_setprop (fdt, cpu_node, "cpu-release-addr", &MbAddr, sizeof (MbAddr));
    }
    MpId = (UINTN) GET_MPID (ArmCoreInfoTable[Index].ClusterId,
                                 ArmCoreInfoTable[Index].CoreId);
    MpId = cpu_to_fdtn (MpId);
    fdt_setprop (fdt, cpu_node, "reg", &MpId, sizeof (MpId));
    fdt_setprop_string(fdt, cpu_node, "compatible", "arm,armv8");
    fdt_setprop_string (fdt, cpu_node, "device_type", "cpu");

    // If it is not the primary core than the cpu should be disabled
    if (((ArmCoreInfoTable[Index].ClusterId != PrimaryClusterId) ||
         (ArmCoreInfoTable[Index].CoreId != PrimaryCoreId))) {
      fdt_setprop_string(fdt, cpu_node, "status", "disabled");
    }
  }

  // Remove existing 'cpu-map' node and create a new one
  map_node = fdt_subnode_offset (fdt, node, "cpu-map");
  if (map_node >= 0) {
    fdt_del_node (fdt, map_node);
  }
  map_node = fdt_add_subnode(fdt, node, "cpu-map");
  if (map_node >= 0) {
    ClusterIndex = ArmCoreCount - 1;
    ClusterCount = NumberOfClustersInTable (ArmCoreInfoTable,
                                            ArmCoreCount);
    while (ClusterCount--) {
      // Create 'cluster' node
      AsciiSPrint (Name, sizeof(Name), "cluster%d", ClusterCount);
      cluster_node = fdt_add_subnode (fdt, map_node, Name);
      if (cluster_node < 0) {
        DEBUG ((EFI_D_ERROR, "FDT: Error creating '%a' node\n", Name));
        Status = EFI_INVALID_PARAMETER;
        goto FAIL_COMPLETE_FDT;
      }

      ClusterId = ArmCoreInfoTable[ClusterIndex].ClusterId;
      CoreIndex = ClusterIndex;
      CoresInCluster = NumberOfCoresInCluster (ArmCoreInfoTable,
                                               ArmCoreCount,
                                               ClusterId);
      while (CoresInCluster--) {
        // Create 'core' node
        AsciiSPrint (Name, sizeof(Name), "core%d", CoresInCluster);
        cpu_node = fdt_add_subnode (fdt, cluster_node, Name);
        if (cpu_node < 0) {
          DEBUG ((EFI_D_ERROR, "FDT: Error creating '%a' node\n", Name));
          Status = EFI_INVALID_PARAMETER;
          goto FAIL_COMPLETE_FDT;
        }
        fdt_setprop_cell (fdt, cpu_node, "cpu", phandle[CoreIndex]);

        // iterate to next core in cluster
        if (CoresInCluster) {
          do {
             --CoreIndex;
          } while (ClusterId != ArmCoreInfoTable[CoreIndex].ClusterId);
        }
      }

      // iterate to next cluster
      if (ClusterCount) {
        do {
           --ClusterIndex;
        } while (ClusterInRange (ArmCoreInfoTable,
                                 ArmCoreInfoTable[ClusterIndex].ClusterId,
                                 ClusterIndex + 1,
                                 ArmCoreCount - 1));
      }
    }
  } else {
    DEBUG((EFI_D_ERROR,"FDT: Error creating 'cpu-map' node\n"));
    Status = EFI_INVALID_PARAMETER;
    goto FAIL_COMPLETE_FDT;
  }

  SetSocIdStatus (fdt);
  SetXgbeStatus (fdt);

  DEBUG_CODE_BEGIN();
    // DebugDumpFdt (fdt);
  DEBUG_CODE_END();

  // If we succeeded to generate the new Device Tree then free the old Device Tree
  gBS->FreePages (*FdtBlobBase, EFI_SIZE_TO_PAGES (*FdtBlobSize));

  // Update the real size of the Device Tree
  fdt_pack ((VOID*)(UINTN)(NewFdtBlobBase));

  *FdtBlobBase = NewFdtBlobBase;
  *FdtBlobSize = (UINTN)fdt_totalsize ((VOID*)(UINTN)(NewFdtBlobBase));
  return EFI_SUCCESS;

FAIL_COMPLETE_FDT:
  gBS->FreePages (NewFdtBlobAllocation, EFI_SIZE_TO_PAGES (NewFdtBlobSize));

FAIL_RELOCATE_FDT:
  *FdtBlobSize = (UINTN)fdt_totalsize ((VOID*)(UINTN)(*FdtBlobBase));
  // Return success even if we failed to update the FDT blob.
  // The original one is still valid.
  return EFI_SUCCESS;
}

