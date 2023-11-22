/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * tun.h - tun device functions
 */
#ifndef __TUN_H__
#define __TUN_H__

#include <fcntl.h>
#include <linux/if.h>
#include <sys/uio.h>
#include <unistd.h>

#include "common.h"
#include "ring.h"

struct tun_data {
  char device4[IFNAMSIZ];
  int read_fd6, write_fd6, fd4;
  struct packet_ring ring;
};

// tun_open and tun_alloc are defined in tun.c and only used by clatd_microbenchmark.c
int tun_open();
int tun_alloc(char *dev, int fd, size_t len);

/* function: send_tun
 * sends a clat_packet to a tun interface
 * fd      - the tun filedescriptor
 * out     - the packet to send
 * iov_len - the number of entries in the clat_packet
 * returns: number of bytes read on success, -1 on failure
 */
static inline int send_tun(int fd, clat_packet out, int iov_len) {
  return writev(fd, out, iov_len);
}

#endif
