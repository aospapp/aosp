// Copyright 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "hci/hci_debug.h"

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "util/string_utils.h"

namespace netsim {
namespace hci {
namespace {

/**
 * HCI LE Meta events
 *
 * References can be found here:
 * https://www.bluetooth.org/en-us/specification/adopted-specifications - Core
 * specification 4.1 [vol 2] Part E (Section 7.7.65) - Le Meta Event
 */
const std::map<int, const char *> HciMetaEventCode = {
    {0x01, "LE_Meta_Event::Connection_Complete"},
    {0x02, "LE_Meta_Event::Adv_Report"},
    {0x03, "LE_Meta_Event::Conn_Update_Complete"},
    {0x04, "LE_Meta_Event::Read_Remote_Features_Complete"},
    {0x05, "LE_Meta_Event::LTK_Requested"},
    // V4.1
    {0x06, "LE_Meta_Event::Remote_Connection_Parameter_Request"},
    // V4.2
    {0x07, "LE_Meta_Event::Data_Length_Change"},
    {0x08, "LE_Meta_Event::Read_Local_P256_Public_Key"},
    {0x09, "LE_Meta_Event::Generate_DHKEY_Complete"},
    {0x0A, "LE_Meta_Event::Enhanced_Connection_Complete"},
    {0x0B, "LE_Meta_Event::Direct_Advertising_Report"},
    // V5.0
    {0x0C, "LE_Meta_Event::Phy_Update_Complete"},
    //
    {0x0D, "LE_Meta_Event::Extended_Adv_Report"},
    {0x0E, "LE_Meta_Event::Periodic_Adv_Sync_Established"},
    {0x0F, "LE_Meta_Event::Periodic_Adv_Report"},
    {0x10, "LE_Meta_Event::Periodic_Adv_Sync_Lost"},
    {0x11, "LE_Meta_Event::Scan_Timeout"},
    {0x12, "LE_Meta_Event::Adv_Set_Terminated"},
    {0x13, "LE_Meta_Event::Scan_Request_Received"},
    {0x14, "LE_Meta_Event::Channel_Selection_Algorithm"},
    {0x15, "LE_Meta_Event::Connectionless_IQ_Report"},
    {0x16, "LE_Meta_Event::Connection_IQ_Report"},
    {0x17, "LE_Meta_Event::CTE_Request_Failed"},
    {0x18, "LE_Meta_Event::Peridic_Adv_Sync_Transfer_Received"},
    {0x19, "LE_Meta_Event::CIS_Established"},
    {0x1A, "LE_Meta_Event::CIS_Request"},
    {0x1B, "LE_Meta_Event::Create_Big_Template"},
    {0x1C, "LE_Meta_Event::Terminate_Big_Template"},
    {0x1D, "LE_Meta_Event::Big_Sync_Established"},
    {0x1E, "LE_Meta_Event::Big_Sync_Lost"},
    {0x1F, "LE_Meta_Event::Request_Peer_SCA_Complete"},
    {0x20, "LE_Meta_Event::Path_Loss_Threshold"},
    {0x21, "LE_Meta_Event::Transmit_Power_Reporting"},
    {0x22, "LE_Meta_Event::Big_Info_Adv_report"}};

/**
 * HCI Event codes
 *
 * References can be found here:
 * https://www.bluetooth.org/en-us/specification/adopted-specifications - Core
 * specification 4.1 [vol 2] Part E (Section 7.7) - Events
 */
constexpr uint8_t HciLeMetaEvent = 0x3e;

const std::map<int, const char *> HciEventCode = {
    {0x01, "Inquiry_Complete_Event"},
    {0x02, "Inquiry_Result_Event"},
    {0x03, "Connection_Complete_Event"},
    {0x04, "Connection_Request_Event"},
    {0x05, "Disconnection_Complete_Event"},
    {0x06, "Authentication_Complete_Event"},
    {0x07, "Remote_Name_Request_Complete_Event"},
    {0x08, "Encryption_Change_Event"},
    {0x09, "Change_Connection_Link_Key_Complete_Event"},
    {0x0A, "Master_Link_Key_Complete_Event"},
    {0x0B, "Read_Remote_Supported_Features_Complete_Event"},
    {0x0C, "Read_Remote_Version_Complete_Event"},
    {0x0D, "Q0S_Setup_Complete_Event"},
    {0x0E, "Command_Complete_Event"},
    {0x0F, "Command_Status_Event"},
    {0x10, "Hardware_Error_Event"},
    {0x11, "Flush_Occurred_Event"},
    {0x12, "Role_Change_Event"},
    {0x13, "Number_Of_Completed_Packets_Event"},
    {0x14, "Mode_Change_Event"},
    {0x15, "Return_Link_Keys_Event"},
    {0x16, "PIN_Code_Request_Event"},
    {0x17, "Link_Key_Request_Event"},
    {0x18, "Link_Key_Notification_Event"},
    {0x19, "Loopback_Command_Event"},
    {0x1A, "Data_Buffer_Overflow_Event"},
    {0x1B, "Max_Slots_Change_Event"},
    {0x1C, "Read_Clock_Offset_Complete_Event"},
    {0x1D, "Connection_Packet_Type_Changed_Event"},
    {0x1E, "QoS_Violation_Event"},
    {0x1F, "Page_Scan_Mode_Change_Event"},
    {0x20, "Page_Scan_Repetition_Mode_Change_Event"},
    {0x21, "Flow_Specification_Complete"},
    {0x22, "Inquiry_Result_With_Rssi"},
    {0x23, "Read_Remote_Extended_Features_Complete"},
    {0x2c, "Synchronous_Connection_Complete"},
    {0x2d, "Synchronous_Connection_Changed"},
    {0x2e, "Sniff_Subrating"},
    {0x2f, "Extended_Inquiry_Result"},
    {0x30, "Encryption_Key_Refresh_Complete"},
    {0x31, "Io_Capability_Request"},
    {0x32, "Io_Capability_Response"},
    {0x33, "User_Confirmation_Request"},
    {0x34, "User_Passkey_Request"},
    {0x35, "Remote_Oob_Data_Request"},
    {0x36, "Simple_Pairing_Complete"},
    {0x38, "Link_Supervision_Timeout_Changed"},
    {0x39, "Enhanced_Flush_Complete"},
    {0x3b, "User_Passkey_Notification"},
    {0x3c, "Keypress_Notification"},
    {0x3d, "Remote_Host_Supported_Features_Notification"},
    {HciLeMetaEvent, "Le_Meta_Event"},
    {0x48, "Number_Of_Completed_Data_Blocks"},
    {0xff, "Vendor_Specific"}};

const std::map<int, const char *> HciCommandOpCode = {
    {0x0000, "None"},
    {0x0401, "Inquiry"},
    {0x0402, "Inquiry_Cancel"},
    {0x0403, "Periodic_Inquiry_Mode"},
    {0x0404, "Exit_Periodic_Inquiry_Mode"},
    {0x0405, "Create_Connection"},
    {0x0406, "Disconnect"},
    {0x0407, "Add_Sco_Connection"},
    {0x0408, "Create_Connection_Cancel"},
    {0x0409, "Accept_Connection_Request"},
    {0x040a, "Reject_Connection_Request"},
    {0x040b, "Link_Key_Request_Reply"},
    {0x040c, "Link_Key_Request_Negative_Reply"},
    {0x040d, "Pin_Code_Request_Reply"},
    {0x040e, "Pin_Code_Request_Negative_Reply"},
    {0x040f, "Change_Connection_Packet_Type"},
    {0x0411, "Authentication_Requested"},
    {0x0413, "Set_Connection_Encryption"},
    {0x0415, "Change_Connection_Link_Key"},
    {0x0417, "Central_Link_Key"},
    {0x0419, "Remote_Name_Request"},
    {0x041a, "Remote_Name_Request_Cancel"},
    {0x041b, "Read_Remote_Supported_Features"},
    {0x041c, "Read_Remote_Extended_Features"},
    {0x041d, "Read_Remote_Version_Information"},
    {0x041f, "Read_Clock_Offset"},
    {0x0420, "Read_Lmp_Handle"},
    {0x0428, "Setup_Synchronous_Connection"},
    {0x0429, "Accept_Synchronous_Connection"},
    {0x042a, "Reject_Synchronous_Connection"},
    {0x042b, "Io_Capability_Request_Reply"},
    {0x042c, "User_Confirmation_Request_Reply"},
    {0x042d, "User_Confirmation_Request_Negative_Reply"},
    {0x042e, "User_Passkey_Request_Reply"},
    {0x042f, "User_Passkey_Request_Negative_Reply"},
    {0x0430, "Remote_Oob_Data_Request_Reply"},
    {0x0433, "Remote_Oob_Data_Request_Negative_Reply"},
    {0x0434, "Io_Capability_Request_Negative_Reply"},
    {0x043d, "Enhanced_Setup_Synchronous_Connection"},
    {0x043e, "Enhanced_Accept_Synchronous_Connection"},
    {0x0445, "Remote_Oob_Extended_Data_Request_Reply"},
    {0x0801, "Hold_Mode"},
    {0x0803, "Sniff_Mode"},
    {0x0804, "Exit_Sniff_Mode"},
    {0x0807, "Qos_Setup"},
    {0x0809, "Role_Discovery"},
    {0x080b, "Switch_Role"},
    {0x080c, "Read_Link_Policy_Settings"},
    {0x080d, "Write_Link_Policy_Settings"},
    {0x080e, "Read_Default_Link_Policy_Settings"},
    {0x080f, "Write_Default_Link_Policy_Settings"},
    {0x0810, "Flow_Specification"},
    {0x0811, "Sniff_Subrating"},
    {0x0c01, "Set_Event_Mask"},
    {0x0c03, "Reset"},
    {0x0c05, "Set_Event_Filter"},
    {0x0c08, "Flush"},
    {0x0c09, "Read_Pin_Type"},
    {0x0c0a, "Write_Pin_Type"},
    {0x0c0d, "Read_Stored_Link_Key"},
    {0x0c11, "Write_Stored_Link_Key"},
    {0x0c12, "Delete_Stored_Link_Key"},
    {0x0c13, "Write_Local_Name"},
    {0x0c14, "Read_Local_Name"},
    {0x0c15, "Read_Connection_Accept_Timeout"},
    {0x0c16, "Write_Connection_Accept_Timeout"},
    {0x0c17, "Read_Page_Timeout"},
    {0x0c18, "Write_Page_Timeout"},
    {0x0c19, "Read_Scan_Enable"},
    {0x0c1a, "Write_Scan_Enable"},
    {0x0c1b, "Read_Page_Scan_Activity"},
    {0x0c1c, "Write_Page_Scan_Activity"},
    {0x0c1d, "Read_Inquiry_Scan_Activity"},
    {0x0c1e, "Write_Inquiry_Scan_Activity"},
    {0x0c1f, "Read_Authentication_Enable"},
    {0x0c20, "Write_Authentication_Enable"},
    {0x0c23, "Read_Class_Of_Device"},
    {0x0c24, "Write_Class_Of_Device"},
    {0x0c25, "Read_Voice_Setting"},
    {0x0c26, "Write_Voice_Setting"},
    {0x0c27, "Read_Automatic_Flush_Timeout"},
    {0x0c28, "Write_Automatic_Flush_Timeout"},
    {0x0c29, "Read_Num_Broadcast_Retransmits"},
    {0x0c2a, "Write_Num_Broadcast_Retransmits"},
    {0x0c2b, "Read_Hold_Mode_Activity"},
    {0x0c2c, "Write_Hold_Mode_Activity"},
    {0x0c2d, "Read_Transmit_Power_Level"},
    {0x0c2e, "Read_Synchronous_Flow_Control_Enable"},
    {0x0c2f, "Write_Synchronous_Flow_Control_Enable"},
    {0x0c31, "Set_Controller_To_Host_Flow_Control"},
    {0x0c33, "Host_Buffer_Size"},
    {0x0c35, "Host_Num_Completed_Packets"},
    {0x0c36, "Read_Link_Supervision_Timeout"},
    {0x0c37, "Write_Link_Supervision_Timeout"},
    {0x0c38, "Read_Number_Of_Supported_Iac"},
    {0x0c39, "Read_Current_Iac_Lap"},
    {0x0c3a, "Write_Current_Iac_Lap"},
    {0x0c3f, "Set_Afh_Host_Channel_Classification"},
    {0x0c42, "Read_Inquiry_Scan_Type"},
    {0x0c43, "Write_Inquiry_Scan_Type"},
    {0x0c44, "Read_Inquiry_Mode"},
    {0x0c45, "Write_Inquiry_Mode"},
    {0x0c46, "Read_Page_Scan_Type"},
    {0x0c47, "Write_Page_Scan_Type"},
    {0x0c48, "Read_Afh_Channel_Assessment_Mode"},
    {0x0c49, "Write_Afh_Channel_Assessment_Mode"},
    {0x0c51, "Read_Extended_Inquiry_Response"},
    {0x0c52, "Write_Extended_Inquiry_Response"},
    {0x0c53, "Refresh_Encryption_Key"},
    {0x0c55, "Read_Simple_Pairing_Mode"},
    {0x0c56, "Write_Simple_Pairing_Mode"},
    {0x0c57, "Read_Local_Oob_Data"},
    {0x0c58, "Read_Inquiry_Response_Transmit_Power_Level"},
    {0x0c59, "Write_Inquiry_Transmit_Power_Level"},
    {0x0c5f, "Enhanced_Flush"},
    {0x0c60, "Send_Keypress_Notification"},
    {0x0c63, "Set_Event_Mask_Page_2"},
    {0x0c6c, "Read_Le_Host_Support"},
    {0x0c6d, "Write_Le_Host_Support"},
    {0x0c79, "Read_Secure_Connections_Host_Support"},
    {0x0c7a, "Write_Secure_Connections_Host_Support"},
    {0x0c7d, "Read_Local_Oob_Extended_Data"},
    {0x0c82, "Set_Ecosystem_Base_Interval"},
    {0x0c83, "Configure_Data_Path"},
    {0x1001, "Read_Local_Version_Information"},
    {0x1002, "Read_Local_Supported_Commands"},
    {0x1003, "Read_Local_Supported_Features"},
    {0x1004, "Read_Local_Extended_Features"},
    {0x1005, "Read_Buffer_Size"},
    {0x1009, "Read_Bd_Addr"},
    {0x100a, "Read_Data_Block_Size"},
    {0x100b, "Read_Local_Supported_Codecs_V1"},
    {0x100d, "Read_Local_Supported_Codecs_V2"},
    {0x100e, "Read_Local_Supported_Codec_Capabilities"},
    {0x100f, "Read_Local_Supported_Controller_Delay"},
    {0x1401, "Read_Failed_Contact_Counter"},
    {0x1402, "Reset_Failed_Contact_Counter"},
    {0x1403, "Read_Link_Quality"},
    {0x1405, "Read_Rssi"},
    {0x1406, "Read_Afh_Channel_Map"},
    {0x1407, "Read_Clock"},
    {0x1408, "Read_Encryption_Key_Size"},
    {0x1801, "Read_Loopback_Mode"},
    {0x1802, "Write_Loopback_Mode"},
    {0x1803, "Enable_Device_Under_Test_Mode"},
    {0x1804, "Write_Simple_Pairing_Debug_Mode"},
    {0x180a, "Write_Secure_Connections_Test_Mode"},
    {0x2001, "Le_Set_Event_Mask"},
    {0x2002, "Le_Read_Buffer_Size_V1"},
    {0x2003, "Le_Read_Local_Supported_Features"},
    {0x2005, "Le_Set_Random_Address"},
    {0x2006, "Le_Set_Advertising_Parameters"},
    {0x2007, "Le_Read_Advertising_Physical_Channel_Tx_Power"},
    {0x2008, "Le_Set_Advertising_Data"},
    {0x2009, "Le_Set_Scan_Response_Data"},
    {0x200a, "Le_Set_Advertising_Enable"},
    {0x200b, "Le_Set_Scan_Parameters"},
    {0x200c, "Le_Set_Scan_Enable"},
    {0x200d, "Le_Create_Connection"},
    {0x200e, "Le_Create_Connection_Cancel"},
    {0x200f, "Le_Read_Filter_Accept_List_Size"},
    {0x2010, "Le_Clear_Filter_Accept_List"},
    {0x2011, "Le_Add_Device_To_Filter_Accept_List"},
    {0x2012, "Le_Remove_Device_From_Filter_Accept_List"},
    {0x2013, "Le_Connection_Update"},
    {0x2014, "Le_Set_Host_Channel_Classification"},
    {0x2015, "Le_Read_Channel_Map"},
    {0x2016, "Le_Read_Remote_Features"},
    {0x2017, "Le_Encrypt"},
    {0x2018, "Le_Rand"},
    {0x2019, "Le_Start_Encryption"},
    {0x201a, "Le_Long_Term_Key_Request_Reply"},
    {0x201b, "Le_Long_Term_Key_Request_Negative_Reply"},
    {0x201c, "Le_Read_Supported_States"},
    {0x201d, "Le_Receiver_Test"},
    {0x201e, "Le_Transmitter_Test"},
    {0x201f, "Le_Test_End"},
    {0x2020, "Le_Remote_Connection_Parameter_Request_Reply"},
    {0x2021, "Le_Remote_Connection_Parameter_Request_Negative_Reply"},
    {0x2022, "Le_Set_Data_Length"},
    {0x2023, "Le_Read_Suggested_Default_Data_Length"},
    {0x2024, "Le_Write_Suggested_Default_Data_Length"},
    {0x2025, "Le_Read_Local_P_256_Public_Key_Command"},
    {0x2026, "Le_Generate_Dhkey_Command_V1"},
    {0x2027, "Le_Add_Device_To_Resolving_List"},
    {0x2028, "Le_Remove_Device_From_Resolving_List"},
    {0x2029, "Le_Clear_Resolving_List"},
    {0x202a, "Le_Read_Resolving_List_Size"},
    {0x202b, "Le_Read_Peer_Resolvable_Address"},
    {0x202c, "Le_Read_Local_Resolvable_Address"},
    {0x202d, "Le_Set_Address_Resolution_Enable"},
    {0x202e, "Le_Set_Resolvable_Private_Address_Timeout"},
    {0x202f, "Le_Read_Maximum_Data_Length"},
    {0x2030, "Le_Read_Phy"},
    {0x2031, "Le_Set_Default_Phy"},
    {0x2032, "Le_Set_Phy"},
    {0x2033, "Le_Enhanced_Receiver_Test"},
    {0x2034, "Le_Enhanced_Transmitter_Test"},
    {0x2035, "Le_Set_Extended_Advertising_Random_Address"},
    {0x2036, "Le_Set_Extended_Advertising_Parameters"},
    {0x2037, "Le_Set_Extended_Advertising_Data"},
    {0x2038, "Le_Set_Extended_Advertising_Scan_Response"},
    {0x2039, "Le_Set_Extended_Advertising_Enable"},
    {0x203a, "Le_Read_Maximum_Advertising_Data_Length"},
    {0x203b, "Le_Read_Number_Of_Supported_Advertising_Sets"},
    {0x203c, "Le_Remove_Advertising_Set"},
    {0x203d, "Le_Clear_Advertising_Sets"},
    {0x203e, "Le_Set_Periodic_Advertising_Param"},
    {0x203f, "Le_Set_Periodic_Advertising_Data"},
    {0x2040, "Le_Set_Periodic_Advertising_Enable"},
    {0x2041, "Le_Set_Extended_Scan_Parameters"},
    {0x2042, "Le_Set_Extended_Scan_Enable"},
    {0x2043, "Le_Extended_Create_Connection"},
    {0x2044, "Le_Periodic_Advertising_Create_Sync"},
    {0x2045, "Le_Periodic_Advertising_Create_Sync_Cancel"},
    {0x2046, "Le_Periodic_Advertising_Terminate_Sync"},
    {0x2047, "Le_Add_Device_To_Periodic_Advertising_List"},
    {0x2048, "Le_Remove_Device_From_Periodic_Advertising_List"},
    {0x2049, "Le_Clear_Periodic_Advertising_List"},
    {0x204a, "Le_Read_Periodic_Advertising_List_Size"},
    {0x204b, "Le_Read_Transmit_Power"},
    {0x204c, "Le_Read_Rf_Path_Compensation_Power"},
    {0x204d, "Le_Write_Rf_Path_Compensation_Power"},
    {0x204e, "Le_Set_Privacy_Mode"},
    {0x2059, "Le_Set_Periodic_Advertising_Receive_Enable"},
    {0x205a, "Le_Periodic_Advertising_Sync_Transfer"},
    {0x205b, "Le_Periodic_Advertising_Set_Info_Transfer"},
    {0x205c, "Le_Set_Periodic_Advertising_Sync_Transfer_Parameters"},
    {0x205d, "Le_Set_Default_Periodic_Advertising_Sync_Transfer_Parameters"},
    {0x205e, "Le_Generate_Dhkey_Command"},
    {0x205f, "Le_Modify_Sleep_Clock_Accuracy"},
    {0x2060, "Le_Read_Buffer_Size_V2"},
    {0x2061, "Le_Read_Iso_Tx_Sync"},
    {0x2062, "Le_Set_Cig_Parameters"},
    {0x2063, "Le_Set_Cig_Parameters_Test"},
    {0x2064, "Le_Create_Cis"},
    {0x2065, "Le_Remove_Cig"},
    {0x2066, "Le_Accept_Cis_Request"},
    {0x2067, "Le_Reject_Cis_Request"},
    {0x2068, "Le_Create_Big"},
    {0x206a, "Le_Terminate_Big"},
    {0x206b, "Le_Big_Create_Sync"},
    {0x206c, "Le_Big_Terminate_Sync"},
    {0x206d, "Le_Request_Peer_Sca"},
    {0x206e, "Le_Setup_Iso_Data_Path"},
    {0x206f, "Le_Remove_Iso_Data_Path"},
    {0x2074, "Le_Set_Host_Feature"},
    {0x2075, "Le_Read_Iso_Link_Quality"},
    {0x2076, "Le_Enhanced_Read_Transmit_Power_Level"},
    {0x2077, "Le_Read_Remote_Transmit_Power_Level"},
    {0x2078, "Le_Set_Path_Loss_Reporting_Parameters"},
    {0x2079, "Le_Set_Path_Loss_Reporting_Enable"},
    {0x207a, "Le_Set_Transmit_Power_Reporting_Enable"},
    {0xfd53, "Le_Get_Vendor_Capabilities"},
    {0xfd54, "Le_Multi_Advt"},
    {0xfd56, "Le_Batch_Scan"},
    {0xfd57, "Le_Adv_Filter"},
    {0xfd59, "Le_Energy_Info"},
    {0xfd5a, "Le_Extended_Scan_Params"},
    {0xfd5b, "Controller_Debug_Info"},
    {0xfd5d, "Controller_A2dp_Opcode"},
    {0xfd5e, "Controller_Bqr"}};

}  // namespace

std::string HciEventToString(const std::vector<uint8_t> &data) {
  if (data.size() < 2) {
    return "Malformed HciEvent";
  }
  auto event_code = data.at(0);
  if (event_code == HciLeMetaEvent) {
    if (data.size() < 3) {
      return "Malformed HciLeMetaEvent";
    }
    auto meta_code = data.at(2);
    if (HciMetaEventCode.count(meta_code)) {
      return HciMetaEventCode.at(meta_code);
    } else {
      return "LE_Meta_Event::" + stringutils::ToHexString(meta_code);
    }
  } else if (HciEventCode.count(event_code)) {
    return HciEventCode.at(event_code);
  }
  return stringutils::ToHexString(event_code);
}

std::string HciCommandToString(uint8_t x, uint8_t y) {
  auto opcode = y << 8 | x;
  if (HciCommandOpCode.count(opcode)) {
    return HciCommandOpCode.at(opcode);
  }
  return stringutils::ToHexString(x, y);
}

}  // namespace hci
}  // namespace netsim
