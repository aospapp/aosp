/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Generated mock file from original source file
 *   Functions generated:11
 */

#include <string.h>

#include <map>
#include <string>

#include "bt_target.h"
#include "main/shim/shim.h"
#include "stack/btm/btm_dev.h"
#include "stack/include/l2c_api.h"
#include "stack/include/l2cdefs.h"
#include "stack/include/smp_api.h"
#include "stack/smp/smp_int.h"
#include "stack_config.h"
#include "test/common/mock_functions.h"
#include "types/raw_address.h"

#ifndef UNUSED_ATTR
#define UNUSED_ATTR
#endif

bool SMP_PairCancel(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return false;
}
bool SMP_Register(tSMP_CALLBACK* p_cback) {
  inc_func_call_count(__func__);
  return false;
}
tSMP_STATUS SMP_BR_PairWith(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return SMP_SUCCESS;
}
tSMP_STATUS SMP_Pair(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type) {
  inc_func_call_count(__func__);
  return SMP_SUCCESS;
}
tSMP_STATUS SMP_Pair(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return SMP_SUCCESS;
}
uint8_t SMP_SetTraceLevel(uint8_t new_level) {
  inc_func_call_count(__func__);
  return 0;
}
void SMP_ConfirmReply(const RawAddress& bd_addr, uint8_t res) {
  inc_func_call_count(__func__);
}
void SMP_Init(void) { inc_func_call_count(__func__); }
void SMP_OobDataReply(const RawAddress& bd_addr, tSMP_STATUS res, uint8_t len,
                      uint8_t* p_data) {
  inc_func_call_count(__func__);
}
void SMP_PasskeyReply(const RawAddress& bd_addr, uint8_t res,
                      uint32_t passkey) {
  inc_func_call_count(__func__);
}
void SMP_SecureConnectionOobDataReply(uint8_t* p_data) {
  inc_func_call_count(__func__);
}
void SMP_SecurityGrant(const RawAddress& bd_addr, tSMP_STATUS res) {
  inc_func_call_count(__func__);
}

bool SMP_CrLocScOobData() {
  inc_func_call_count(__func__);
  return false;
}

void SMP_ClearLocScOobData() { inc_func_call_count(__func__); }
