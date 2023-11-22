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
 * tun.c - tun device functions
 */
#include <arpa/inet.h>
#include <fcntl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include "common.h"

/* function: tun_open
 * tries to open the tunnel device in non-blocking mode
 */
int tun_open() {
  int fd;

  fd = open("/dev/tun", O_RDWR | O_NONBLOCK | O_CLOEXEC);
  if (fd < 0) {
    fd = open("/dev/net/tun", O_RDWR | O_NONBLOCK | O_CLOEXEC);
  }

  return fd;
}

/* function: tun_alloc
 * creates a tun interface and names it
 * dev - the name for the new tun device
 * fd - an open fd to the tun device node
 * len - the length of the buffer pointed to by dev
 */
int tun_alloc(char *dev, int fd, size_t len) {
  struct ifreq ifr;
  int err;

  memset(&ifr, 0, sizeof(ifr));

  ifr.ifr_flags = IFF_TUN;
  if (*dev) {
    strncpy(ifr.ifr_name, dev, IFNAMSIZ);
    ifr.ifr_name[IFNAMSIZ - 1] = '\0';
  }

  if ((err = ioctl(fd, TUNSETIFF, (void *)&ifr)) < 0) {
    close(fd);
    return err;
  }
  strlcpy(dev, ifr.ifr_name, len);
  return 0;
}
