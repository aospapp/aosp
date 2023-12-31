/*
 * Copyright 2012-2020 NXP
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

#ifndef _PHNXPUCIHAL_UTILS_H_
#define _PHNXPUCIHAL_UTILS_H_

#include <assert.h>
#include <phUwbStatus.h>
#include <pthread.h>
#include <semaphore.h>

/********************* Definitions and structures *****************************/

/* List structures */
struct listNode {
  void* pData;
  struct listNode* pNext;
};

struct listHead {
  struct listNode* pFirst;
  pthread_mutex_t mutex;
};

/* Semaphore handling structure */
typedef struct phNxpUciHal_Sem {
  /* Semaphore used to wait for callback */
  sem_t sem;

  /* Used to store the status sent by the callback */
  tHAL_UWB_STATUS status;

  /* Used to provide a local context to the callback */
  void* pContext;

} phNxpUciHal_Sem_t;

/* Semaphore helper macros */
#define SEM_WAIT(cb_data) sem_wait(&((cb_data).sem))
#define SEM_POST(p_cb_data) sem_post(&((p_cb_data)->sem))

/* Semaphore and mutex monitor */
typedef struct phNxpUciHal_Monitor {
  /* Mutex protecting native library against reentrance */
  pthread_mutex_t reentrance_mutex;

  /* Mutex protecting native library against concurrency */
  pthread_mutex_t concurrency_mutex;

  /* List used to track pending semaphores waiting for callback */
  struct listHead sem_list;

} phNxpUciHal_Monitor_t;

/************************ Exposed functions ***********************************/
/* List functions */
int listInit(struct listHead* pList);
int listDestroy(struct listHead* pList);
int listAdd(struct listHead* pList, void* pData);
int listRemove(struct listHead* pList, void* pData);
int listGetAndRemoveNext(struct listHead* pList, void** ppData);
void listDump(struct listHead* pList);

/* NXP UCI HAL utility functions */
phNxpUciHal_Monitor_t* phNxpUciHal_init_monitor(void);
void phNxpUciHal_cleanup_monitor(void);
phNxpUciHal_Monitor_t* phNxpUciHal_get_monitor(void);
tHAL_UWB_STATUS phNxpUciHal_init_cb_data(phNxpUciHal_Sem_t* pCallbackData,
                                   void* pContext);
void phNxpUciHal_sem_timed_wait(phNxpUciHal_Sem_t* pCallbackData);
void phNxpUciHal_cleanup_cb_data(phNxpUciHal_Sem_t* pCallbackData);
void phNxpUciHal_releaseall_cb_data(void);
void phNxpUciHal_print_packet(const char* pString, const uint8_t* p_data,
                              uint16_t len);
void phNxpUciHal_emergency_recovery(void);
double phNxpUciHal_byteArrayToDouble(const uint8_t* p_data);

/* Lock unlock helper macros */
/* Lock unlock helper macros */
#define REENTRANCE_LOCK()        \
  if (phNxpUciHal_get_monitor()) \
  pthread_mutex_lock(&phNxpUciHal_get_monitor()->reentrance_mutex)
#define REENTRANCE_UNLOCK()      \
  if (phNxpUciHal_get_monitor()) \
  pthread_mutex_unlock(&phNxpUciHal_get_monitor()->reentrance_mutex)
#define CONCURRENCY_LOCK()       \
  if (phNxpUciHal_get_monitor()) \
  pthread_mutex_lock(&phNxpUciHal_get_monitor()->concurrency_mutex)
#define CONCURRENCY_UNLOCK()     \
  if (phNxpUciHal_get_monitor()) \
  pthread_mutex_unlock(&phNxpUciHal_get_monitor()->concurrency_mutex)
#define STREAM_TO_UINT8(u8, p) \
  {                            \
    u8 = (uint8_t)(*(p));      \
    (p) += 1;                  \
  }

#define BE_STREAM_TO_UINT32(u32, p)                                    \
  {                                                                    \
    u32 = ((uint32_t)(*((p) + 3)) + ((uint32_t)(*((p) + 2)) << 8) +    \
           ((uint32_t)(*((p) + 1)) << 16) + ((uint32_t)(*(p)) << 24)); \
    (p) += 4;                                                          \
  }

#endif /* _PHNXPUCIHAL_UTILS_H_ */
