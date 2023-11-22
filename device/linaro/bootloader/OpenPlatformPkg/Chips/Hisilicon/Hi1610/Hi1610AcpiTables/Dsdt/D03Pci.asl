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
  // PCIe Root bus
  Device (PCI0)
  {
    Name (_HID, "HISI0080") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 0) // Segment of this Root complex
    Name(_BBN, 0) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0x0, // AddressMinimum - Minimum Bus Number
          0x1f, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x20 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0xb2000000, // Min Base Address pci address
          0xb7feffff, // Max Base Address
          0x0, // Translate
          0x5ff0000 // Length
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
          0xb7ff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)

    Device (RES0)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CRS, ResourceTemplate (){
        Memory32Fixed (ReadWrite, 0xa0090000 , 0x10000)
      })
    }

    OperationRegion(SCTR, SystemMemory, 0xa009131c, 4)
    Field(SCTR, AnyAcc, NoLock, Preserve) {
      LSTA, 32,
    }
    Method(_DSM, 0x4, Serialized) {
      If(LEqual(Arg0,ToUUID("6d30f553-836c-408e-b6ad-45bccc957949"))) {
        switch(ToInteger(Arg2))
        {
          // Function 0: Return LinkStatus
          case(0) {
              Store (0, Local0)
              Store (LSTA, Local0)
              Return (Local0)
          }
          default {
          }
        }
      }
      // If not one of the function identifiers we recognize, then return a buffer
      // with bit 0 set to 0 indicating no functions supported.
      return(Buffer(){0})
    }
  } // Device(PCI0)

  // PCIe Root bus
  Device (PCI1)
  {
    Name (_HID, "HISI0080") // PCI Express Root Bridge
    Name (_CID, "PNP0A03") // Compatible PCI Root Bridge
    Name(_SEG, 1) // Segment of this Root complex
    Name(_BBN, 0xe0) // Base Bus Number
    Name(_CCA, 1)
    Method (_CRS, 0, Serialized) { // Root complex resources
      Name (RBUF, ResourceTemplate () {
        WordBusNumber ( // Bus numbers assigned to this root
          ResourceProducer, MinFixed, MaxFixed, PosDecode,
          0, // AddressGranularity
          0xe0, // AddressMinimum - Minimum Bus Number
          0xff, // AddressMaximum - Maximum Bus Number
          0,   // AddressTranslation - Set to 0
          0x20 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0xb8000000, // Min Base Address pci address
          0xbdfeffff, // Max Base Address
          0x0, // Translate
          0x5ff0000 // Length
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
          0xbdff0000, // Translate
          0x10000 // Length
        )
      }) // Name(RBUF)
      Return (RBUF)
    } // Method(_CRS)

    Device (RES1)
    {
      Name (_HID, "HISI0081") // HiSi PCIe RC config base address
      Name (_CRS, ResourceTemplate (){
        Memory32Fixed (ReadWrite, 0xa0200000 , 0x10000)
      })
    }

    OperationRegion(SCTR, SystemMemory, 0xa020131c, 4)
    Field(SCTR, AnyAcc, NoLock, Preserve) {
      LSTA, 32,
    }
    Method(_DSM, 0x4, Serialized) {
      If(LEqual(Arg0,ToUUID("6d30f553-836c-408e-b6ad-45bccc957949"))) {

        switch(ToInteger(Arg2))
        {
          // Function 0: Return LinkStatus
          case(0) {
              Store (0, Local0)
              Store (LSTA, Local0)
              Return (Local0)
          }
          default {
          }
        }
      }
      // If not one of the function identifiers we recognize, then return a buffer
      // with bit 0 set to 0 indicating no functions supported.
      return(Buffer(){0})
    }
  } // Device(PCI1)

  // PCIe Root bus
  Device (PCI2)
  {
    Name (_HID, "HISI0080") // PCI Express Root Bridge
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
          0x9f, // AddressMaximum - Maximum Bus Number
          0, // AddressTranslation - Set to 0
          0x20 // RangeLength - Number of Busses
        )
        QWordMemory ( // 64-bit BAR Windows
          ResourceProducer,
          PosDecode,
          MinFixed,
          MaxFixed,
          Cacheable,
          ReadWrite,
          0x0, // Granularity
          0xaa000000, // Min Base Address
          0xaffeffff, // Max Base Address
          0x0, // Translate
          0x5ff0000 // Length
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
      Name (_CRS, ResourceTemplate (){
        Memory32Fixed (ReadWrite, 0xa00a0000, 0x10000)
      })
    }

    OperationRegion(SCTR, SystemMemory, 0xa00a131c, 4)
    Field(SCTR, AnyAcc, NoLock, Preserve) {
      LSTA, 32,
    }
    Method(_DSM, 0x4, Serialized) {
      If(LEqual(Arg0,ToUUID("6d30f553-836c-408e-b6ad-45bccc957949")))
      {
        switch(ToInteger(Arg2))
        {
          // Function 0: Return LinkStatus
          case(0) {
              Store (0, Local0)
              Store (LSTA, Local0)
              Return (Local0)
          }
          default {
          }
        }
      }
      // If not one of the function identifiers we recognize, then return a buffer
      // with bit 0 set to 0 indicating no functions supported.
      return(Buffer(){0})
    }
  } // Device(PCI2)
}

