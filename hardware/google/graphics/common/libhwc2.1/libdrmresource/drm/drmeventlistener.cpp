/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "hwc-drm-event-listener"

#include "drmeventlistener.h"
#include "drmdevice.h"
#include <drm/samsung_drm.h>

#include <assert.h>
#include <errno.h>
#include <linux/netlink.h>
#include <sys/socket.h>

#include <hardware/hardware.h>
#include <hardware/hwcomposer.h>
#include <log/log.h>
#include <xf86drm.h>

namespace android {

DrmEventListener::DrmEventListener(DrmDevice *drm)
    : Worker("drm-event-listener", HAL_PRIORITY_URGENT_DISPLAY), drm_(drm) {
}

DrmEventListener::~DrmEventListener() {
    Exit();
}

int DrmEventListener::Init() {
  struct epoll_event ev;
  char buffer[1024];

  /* Open User Event File Descriptor */
  uevent_fd_.Set(socket(PF_NETLINK, SOCK_DGRAM, NETLINK_KOBJECT_UEVENT));
  if (uevent_fd_.get() < 0) {
    ALOGE("Failed to open uevent socket: %s", strerror(errno));
    return uevent_fd_.get();
  }

  struct sockaddr_nl addr;
  memset(&addr, 0, sizeof(addr));
  addr.nl_family = AF_NETLINK;
  addr.nl_pid = 0;
  addr.nl_groups = 0xFFFFFFFF;

  int ret = bind(uevent_fd_.get(), (struct sockaddr *)&addr, sizeof(addr));
  if (ret) {
    ALOGE("Failed to bind uevent socket: %s", strerror(errno));
    return -errno;
  }

  /* Open TUI Event File Descriptor */
  tuievent_fd_.Set(open(kTUIStatusPath, O_RDONLY));
  if (tuievent_fd_.get() < 0) {
    ALOGE("Failed to open sysfs(%s) for TUI event: %s", kTUIStatusPath, strerror(errno));
    return tuievent_fd_.get();
  }

  /* Read garbage data once */
  pread(tuievent_fd_.get(), &buffer, sizeof(buffer), 0);

  /* Set EPoll*/
  epoll_fd_.Set(epoll_create(maxFds));
  if (epoll_fd_.get() < 0) {
    ALOGE("Failed to create epoll: %s", strerror(errno));
    return epoll_fd_.get();
  }

  ev.events = EPOLLIN;
  ev.data.fd = uevent_fd_.get();
  if (epoll_ctl(epoll_fd_.get(), EPOLL_CTL_ADD, uevent_fd_.get(), &ev) < 0) {
    ALOGE("Failed to add uevent fd into epoll: %s", strerror(errno));
    return -errno;
  }

  ev.events = EPOLLIN;
  ev.data.fd = drm_->fd();
  if (epoll_ctl(epoll_fd_.get(), EPOLL_CTL_ADD, drm_->fd(), &ev) < 0) {
    ALOGE("Failed to add drm fd into epoll: %s", strerror(errno));
    return -errno;
  }

  ev.events = EPOLLPRI;
  ev.data.fd = tuievent_fd_.get();
  if (epoll_ctl(epoll_fd_.get(), EPOLL_CTL_ADD, tuievent_fd_.get(), &ev) < 0) {
    ALOGE("Failed to add tui fd into epoll: %s", strerror(errno));
    return -errno;
  }

  return InitWorker();
}

void DrmEventListener::RegisterHotplugHandler(DrmEventHandler *handler) {
  assert(!hotplug_handler_);
  hotplug_handler_.reset(handler);
}

void DrmEventListener::UnRegisterHotplugHandler(DrmEventHandler *handler) {
  if (handler == hotplug_handler_.get())
    hotplug_handler_ = NULL;
}

void DrmEventListener::RegisterTUIHandler(DrmTUIEventHandler *handler) {
  if (tui_handler_) {
    ALOGE("TUI handler was already registered");
    return;
  }
  tui_handler_.reset(handler);
}

void DrmEventListener::UnRegisterTUIHandler(DrmTUIEventHandler *handler) {
  if (handler == tui_handler_.get())
    tui_handler_ = NULL;
}

bool DrmEventListener::IsDrmInTUI() {
  char buffer[1024];
  int ret;

  ret = pread(tuievent_fd_.get(), &buffer, sizeof(buffer), 0);
  if (ret == 0) {
    return false;
  } else if (ret < 0) {
    ALOGE("Got error reading TUI event %s", strerror(errno));
    return false;
  }

  return atoi(buffer) == 1 ? true : false;
}

void DrmEventListener::FlipHandler(int /* fd */, unsigned int /* sequence */,
                                   unsigned int tv_sec, unsigned int tv_usec,
                                   void *user_data) {
  DrmEventHandler *handler = (DrmEventHandler *)user_data;
  if (!handler)
    return;

  handler->HandleEvent((uint64_t)tv_sec * 1000 * 1000 + tv_usec);
  delete handler;
}

void DrmEventListener::UEventHandler() {
  char buffer[1024];
  int ret;

  struct timespec ts;
  uint64_t timestamp = 0;
  ret = clock_gettime(CLOCK_MONOTONIC, &ts);
  if (!ret)
    timestamp = ts.tv_sec * 1000 * 1000 * 1000 + ts.tv_nsec;
  else
    ALOGE("Failed to get monotonic clock on hotplug %d", ret);

  ret = read(uevent_fd_.get(), &buffer, sizeof(buffer));
  if (ret == 0) {
      return;
  } else if (ret < 0) {
    ALOGE("Got error reading uevent %d", ret);
    return;
  }

  bool drm_event = false, hotplug_event = false;
  for (int i = 0; i < ret;) {
    char *event = buffer + i;
    if (!strcmp(event, "DEVTYPE=drm_minor")) {
      drm_event = true;
    } else if (!strcmp(event, "HOTPLUG=1")) {
      hotplug_event = true;
    }

    i += strlen(event) + 1;
  }

  if (drm_event && hotplug_event) {
    if (!hotplug_handler_)
      return;

    hotplug_handler_->HandleEvent(timestamp);
  }
}

void DrmEventListener::TUIEventHandler() {
  if (!tui_handler_) {
    ALOGE("%s:: tui event handler is not valid", __func__);
    return;
  }

  tui_handler_->HandleTUIEvent();
}

void DrmEventListener::Routine() {
  struct epoll_event events[maxFds];
  int nfds, n;

  do {
    nfds = epoll_wait(epoll_fd_.get(), events, maxFds, -1);
  } while (nfds <= 0);

  for (n = 0; n < nfds; n++) {
    if (events[n].events & EPOLLIN) {
      if (events[n].data.fd == uevent_fd_.get()) {
        UEventHandler();
      } else if (events[n].data.fd == drm_->fd()) {
        drmEventContext event_context =
            {.version = 2,
             .vblank_handler = NULL,
             .page_flip_handler = DrmEventListener::FlipHandler};
        drmHandleEvent(drm_->fd(), &event_context);
      }
    } else if (events[n].events & EPOLLPRI) {
      if (events[n].data.fd == tuievent_fd_.get()) {
        TUIEventHandler();
      }
    }
  }
}
}  // namespace android
