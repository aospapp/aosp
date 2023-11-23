/******************************************************************************
 *
 *  Copyright (C) 2011-2012 Broadcom Corporation
 *  Copyright 2018-2019 NXP.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#ifndef __CONFIG_H
#define __CONFIG_H
#ifdef __cplusplus
extern "C"
{
#endif

int GetNxpConfigStrValue(const char* name, char* p_value, unsigned long len);
int GetNxpConfigNumValue(const char* name, void* p_value, unsigned long len);
int GetNxpConfigByteArrayValue(const char* name, char* pValue,long bufflen, long *len);
int GetNxpConfigUciByteArrayValue(const char* name, char* pValue,long bufflen, long *len);
int GetNxpConfigCountryCodeByteArrayValue(const char* name,const char* fName, char* pValue,long bufflen, long *len);

#ifdef __cplusplus
};
#endif

#define NAME_UWB_BOARD_VARIANT_CONFIG "UWB_BOARD_VARIANT_CONFIG"
#define NAME_UWB_BOARD_VARIANT_VERSION "UWB_BOARD_VARIANT_VERSION"
#define NAME_UWB_CORE_EXT_DEVICE_DEFAULT_CONFIG "UWB_CORE_EXT_DEVICE_DEFAULT_CONFIG"
#define NAME_UWB_DEBUG_DEFAULT_CONFIG "UWB_DEBUG_DEFAULT_CONFIG"
#define NAME_ANTENNA_PAIR_SELECTION_CONFIG1 "ANTENNA_PAIR_SELECTION_CONFIG1"
#define NAME_ANTENNA_PAIR_SELECTION_CONFIG2 "ANTENNA_PAIR_SELECTION_CONFIG2"
#define NAME_ANTENNA_PAIR_SELECTION_CONFIG3 "ANTENNA_PAIR_SELECTION_CONFIG3"
#define NAME_ANTENNA_PAIR_SELECTION_CONFIG4 "ANTENNA_PAIR_SELECTION_CONFIG4"
#define NAME_ANTENNA_PAIR_SELECTION_CONFIG5 "ANTENNA_PAIR_SELECTION_CONFIG5"
#define NAME_NXP_CORE_CONF_BLK "NXP_CORE_CONF_BLK_"
#define NAME_UWB_FW_DOWNLOAD_LOG "UWB_FW_DOWNLOAD_LOG"
#define NAME_NXP_UWB_FLASH_CONFIG "NXP_UWB_FLASH_CONFIG"
#define NAME_NXP_UWB_XTAL_38MHZ_CONFIG "NXP_UWB_XTAL_38MHZ_CONFIG"
#define NAME_NXP_UWB_EXTENDED_NTF_CONFIG "NXP_UWB_EXTENDED_NTF_CONFIG"
#define NAME_UWB_CORE_EXT_DEVICE_SR1XX_T_CONFIG "UWB_CORE_EXT_DEVICE_SR1XX_T_CONFIG"
#define NAME_UWB_CORE_EXT_DEVICE_SR1XX_S_CONFIG "UWB_CORE_EXT_DEVICE_SR1XX_S_CONFIG"

#define NAME_NXP_UWB_DEVICE_NODE      "NXP_UWB_DEVICE_NODE"
#define NAME_NXP_UWB_PROD_FW_FILENAME "NXP_UWB_PROD_FW_FILENAME"
#define NAME_NXP_UWB_DEV_FW_FILENAME "NXP_UWB_DEV_FW_FILENAME"
#define NAME_NXP_UWB_FW_FILENAME "NXP_UWB_FW_FILENAME"
#define NAME_NXP_UWB_EXT_APP_DEFAULT_CONFIG "NXP_UWB_EXT_APP_DEFAULT_CONFIG"
#define NAME_NXP_UWB_EXT_APP_SR1XX_T_CONFIG "NXP_UWB_EXT_APP_SR1XX_T_CONFIG"
#define NAME_NXP_UWB_EXT_APP_SR1XX_S_CONFIG "NXP_UWB_EXT_APP_SR1XX_S_CONFIG"
#define NAME_NXP_UWB_EXT_APP_DEFAULT_CONFIG "NXP_UWB_EXT_APP_DEFAULT_CONFIG"
#define NAME_UWB_USER_FW_BOOT_MODE_CONFIG "UWB_USER_FW_BOOT_MODE_CONFIG"
#define NAME_NXP_COUNTRY_CODE_CONFIG "NXP_COUNTRY_CODE_CONFIG"

#define NAME_NXP_SECURE_CONFIG_BLK "NXP_SECURE_CONFIG_BLK_"
#define NAME_PLATFORM_ID "PLATFORM_ID"

/* default configuration */
#define default_storage_location "/data/vendor/uwb"

#endif
