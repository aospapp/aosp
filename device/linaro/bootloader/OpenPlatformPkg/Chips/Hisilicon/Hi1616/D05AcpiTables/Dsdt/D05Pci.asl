/** @file
*
*  Copyright (c) 2011-2015, ARM Limited. All rights reserved.
*  Copyright (c) 2016, Hisilicon Limited. All rights reserved.
*  Copyright (c) 2016, Linaro Limited. All rights reserved.
*
*  This program and the accompanying materials
*  are licensed and made available under the terms and conditions of the BSD License
*  which accompanies this distribution.  The full text of the license may be found at
*  http://opensource.org/licenses/bsd-license.php
*
*  THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
*  WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.
*
*  Based on the files under ArmPlatformPkg/ArmJunoPkg/AcpiTables/
*
**/

//#include "ArmPlatform.h"
Scope(_SB)
{
  /* 0xD000E014:Hi1616 chip version reg[19:8], 0x102-after EC, 0x101/0-before EC. */
  OperationRegion (ECRA, SystemMemory, 0xD000E014, 0x4)
  Field (ECRA, AnyAcc, NoLock, Preserve)
  {
    VECA, 32,
  }

  /* RBYV:Return by chip version
   * the pcie device should be disable for chip's reason before EC,
   * and the pcie device should be enable after EC for OS */
  Method (RBYV)
  {
    Store(VECA, local0)
    And (local0, 0xFFF00, local1)
    If (LEqual (local1, 0x10200)) {
      Return (0xf)
    } Else {
      Return (0x0)
    }
  }

  // 1P NA PCIe2
  Device (PCI2)
  {
    Name (_HID, "PNP0A08") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 2) // Segment of this Root complex
    Name(_BBN, 0x80) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0x80, // AddressMinimum - Minimum Bus Number
          0x87, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x8 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0xa8800000, // Min Base Address
          0xaffeffff, // Max Base Address
          0x0, // Translate
          0x77f0000 // Length
        )
        QWordIO (
          ResourceProducer,
          MinFixed,
          MaxFixed,
          PosDecode,
          EntireRange,
          0x0, // Granularity
          0x0, // Min Base Address
          0xffff, // Max Base Address
          0xafff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)
    Device (RES2)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CID, "PNP0C02")  // Motherboard reserved resource
      Name (_CRS, ResourceTemplate (){
        Memory32Fixed (ReadWrite, 0xa00a0000 , 0x10000)
      })
    }
    Method (_STA, 0x0, NotSerialized)
    {
      Return (0xf)
    }

  } // Device(PCI2)
  // 1p NB PCIe0
  Device (PCI4)
  {
    Name (_HID, "PNP0A08") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 4) // Segment of this Root complex
    Name(_BBN, 0x88) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0x88, // AddressMinimum - Minimum Bus Number
          0x8f, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x8 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0xa9000000, // Min Base Address
          0xabfeffff, // Max Base Address
          0x800000000, // Translate
          0x2ff0000 // Length
        )
        QWordIO (
          ResourceProducer,
          MinFixed,
          MaxFixed,
          PosDecode,
          EntireRange,
          0x0, // Granularity
          0x0, // Min Base Address
          0xffff, // Max Base Address
          0x8abff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)
    Device (RES4)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CID, "PNP0C02")  // Motherboard reserved resource
      Name (_CRS, ResourceTemplate (){
        QwordMemory (
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          NonCacheable,
          ReadWrite,
          0x0, // Granularity
          0x8a0090000, // Min Base Address
          0x8a009ffff, // Max Base Address
          0x0, // Translate
          0x10000 // Length
       )
      })
    }
    Method (_STA, 0x0, NotSerialized)
    {
      Return (RBYV())
    }

  } // Device(PCI4)

  // 1P NB PCI1
  Device (PCI5)
  {
    Name (_HID, "PNP0A08") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 5) // Segment of this Root complex
    Name(_BBN, 0x0) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0x0, // AddressMinimum - Minimum Bus Number
          0x7, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x8 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0xb0800000, // Min Base Address
          0xb7feffff, // Max Base Address
          0x800000000, // Translate
          0x77f0000 // Length
        )
        QWordIO (
          ResourceProducer,
          MinFixed,
          MaxFixed,
          PosDecode,
          EntireRange,
          0x0, // Granularity
          0x0, // Min Base Address
          0xffff, // Max Base Address
          0x8b7ff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)
    Device (RES5)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CID, "PNP0C02")  // Motherboard reserved resource
      Name (_CRS, ResourceTemplate (){
        QwordMemory (
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          NonCacheable,
          ReadWrite,
          0x0, // Granularity
          0x8a0200000, // Min Base Address
          0x8a020ffff, // Max Base Address
          0x0, // Translate
          0x10000 // Length
       )
      })
    }
    Method (_STA, 0x0, NotSerialized)
    {
      Return (RBYV())
    }
  } // Device(PCI5)

  // 1P NB PCIe2
  Device (PCI6)
  {
    Name (_HID, "PNP0A08") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 0x6) // Segment of this Root complex
    Name(_BBN, 0xc0) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0xc0, // AddressMinimum - Minimum Bus Number
          0xc7, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x8 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0xac900000, // Min Base Address
          0xaffeffff, // Max Base Address
          0x800000000, // Translate
          0x36f0000 // Length
        )
        QWordIO (
          ResourceProducer,
          MinFixed,
          MaxFixed,
          PosDecode,
          EntireRange,
          0x0, // Granularity
          0x0, // Min Base Address
          0xffff, // Max Base Address
          0x8afff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)
    Device (RES6)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CID, "PNP0C02")  // Motherboard reserved resource
      Name (_CRS, ResourceTemplate (){
        QwordMemory (
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          NonCacheable,
          ReadWrite,
          0x0, // Granularity
          0x8a00a0000, // Min Base Address
          0x8a00affff, // Max Base Address
          0x0, // Translate
          0x10000 // Length
    )
     })
    }
    Method (_STA, 0x0, NotSerialized)
    {
      Return (RBYV())
    }
  } // Device(PCI6)
  // 1P NB PCIe3
  Device (PCI7)
  {
    Name (_HID, "PNP0A08") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 0x7) // Segment of this Root complex
    Name(_BBN, 0x90) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0x90, // AddressMinimum - Minimum Bus Number
          0x97, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x8 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0xb9800000, // Min Base Address
          0xbffeffff, // Max Base Address
          0x800000000, // Translate
          0x67f0000 // Length
        )
        QWordIO (
          ResourceProducer,
          MinFixed,
          MaxFixed,
          PosDecode,
          EntireRange,
          0x0, // Granularity
          0x0, // Min Base Address
          0xffff, // Max Base Address
          0x8bfff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)
    Device (RES7)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CID, "PNP0C02")  // Motherboard reserved resource
      Name (_CRS, ResourceTemplate (){
        QwordMemory (
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          NonCacheable,
          ReadWrite,
          0x0, // Granularity
          0x8a00b0000, // Min Base Address
          0x8a00bffff, // Max Base Address
          0x0, // Translate
          0x10000 // Length
        )
      })
    }
    Method (_STA, 0x0, NotSerialized)
    {
      Return (RBYV())
    }
  } // Device(PCI7)
  // 2P NA PCIe2
  Device (PCIa)
  {
    Name (_HID, "PNP0A08") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 0xa) // Segment of this Root complex
    Name(_BBN, 0x10) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0x10, // AddressMinimum - Minimum Bus Number
          0x1f, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x10 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0x20000000, // Min Base Address
          0xefffffff, // Max Base Address
          0x65000000000, // Translate
          0xd0000000 // Length
        )
        QWordIO (
          ResourceProducer,
          MinFixed,
          MaxFixed,
          PosDecode,
          EntireRange,
          0x0, // Granularity
          0x0, // Min Base Address
          0xffff, // Max Base Address
          0x67fffff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)
    Device (RESa)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CID, "PNP0C02")  // Motherboard reserved resource
      Name (_CRS, ResourceTemplate (){
        QwordMemory (
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          NonCacheable,
          ReadWrite,
          0x0, // Granularity
          0x600a00a0000, // Min Base Address
          0x600a00affff, // Max Base Address
          0x0, // Translate
          0x10000 // Length
        )
      })
    }
    Method (_STA, 0x0, NotSerialized)
    {
      Return (0xf)
    }
  } // Device(PCIa)
  // 2P NB PCIe0
  Device (PCIc)
  {
    Name (_HID, "PNP0A08") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 0xc) // Segment of this Root complex
    Name(_BBN, 0x20) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0x20, // AddressMinimum - Minimum Bus Number
          0x2f, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x10 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0x30000000, // Min Base Address
          0xefffffff, // Max Base Address
          0x75000000000, // Translate
          0xc0000000 // Length
        )
        QWordIO (
          ResourceProducer,
          MinFixed,
          MaxFixed,
          PosDecode,
          EntireRange,
          0x0, // Granularity
          0x0, // Min Base Address
          0xffff, // Max Base Address
          0x77fffff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)
    Device (RESc)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CID, "PNP0C02")  // Motherboard reserved resource
      Name (_CRS, ResourceTemplate (){
        QwordMemory (
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          NonCacheable,
          ReadWrite,
          0x0, // Granularity
          0x700a0090000, // Min Base Address
          0x700a009ffff, // Max Base Address
          0x0, // Translate
          0x10000 // Length
        )
      })
    }
    Method (_STA, 0x0, NotSerialized)
    {
      Return (RBYV())
    }
  } // Device(PCIc)

  //2P NB PCIe1
  Device (PCId)
  {
    Name (_HID, "PNP0A08") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 0xd) // Segment of this Root complex
    Name(_BBN, 0x30) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0x30, // AddressMinimum - Minimum Bus Number
          0x3f, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x10 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0x40000000, // Min Base Address
          0xefffffff, // Max Base Address
          0x79000000000, // Translate
          0xB0000000 // Length
        )
        QWordIO (
          ResourceProducer,
          MinFixed,
          MaxFixed,
          PosDecode,
          EntireRange,
          0x0, // Granularity
          0x0, // Min Base Address
          0xffff, // Max Base Address
          0x7bfffff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)
    Device (RESd)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CID, "PNP0C02")  // Motherboard reserved resource
      Name (_CRS, ResourceTemplate (){
        QwordMemory (
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          NonCacheable,
          ReadWrite,
          0x0, // Granularity
          0x700a0200000, // Min Base Address
          0x700a020ffff, // Max Base Address
          0x0, // Translate
          0x10000 // Length
        )
      })
    }
    Method (_STA, 0x0, NotSerialized)
    {
      Return (RBYV())
    }
  } // Device(PCId)
}

