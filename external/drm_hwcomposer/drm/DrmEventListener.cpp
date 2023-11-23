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

#include "DrmEventListener.h"

#include <linux/netlink.h>
#include <sys/socket.h>
#include <xf86drm.h>

#include <cassert>
#include <cerrno>
#include <cstring>

#include "DrmDevice.h"
#include "utils/log.h"

/* Originally defined in system/core/libsystem/include/system/graphics.h */
#define HAL_PRIORITY_URGENT_DISPLAY (-8)

namespace android {

DrmEventListener::DrmEventListener(DrmDevice *drm)
    : Worker("drm-event-listener", HAL_PRIORITY_URGENT_DISPLAY), drm_(drm) {
}

int DrmEventListener::Init() {
  uevent_fd_ = UniqueFd(
      socket(PF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, NETLINK_KOBJECT_UEVENT));
  if (!uevent_fd_) {
    ALOGE("Failed to open uevent socket: %s", strerror(errno));
    return -errno;
  }

  struct sockaddr_nl addr {};
  addr.nl_family = AF_NETLINK;
  addr.nl_pid = 0;
  addr.nl_groups = 0xFFFFFFFF;

  int ret = bind(uevent_fd_.Get(), (struct sockaddr *)&addr, sizeof(addr));
  if (ret) {
    ALOGE("Failed to bind uevent socket: %s", strerror(errno));
    return -errno;
  }

  // NOLINTNEXTLINE(readability-isolate-declaration)
  FD_ZERO(&fds_);
  FD_SET(drm_->fd(), &fds_);
  FD_SET(uevent_fd_.Get(), &fds_);
  max_fd_ = std::max(drm_->fd(), uevent_fd_.Get());

  return InitWorker();
}

void DrmEventListener::RegisterHotplugHandler(DrmEventHandler *handler) {
  assert(!hotplug_handler_);
  hotplug_handler_.reset(handler);
}

void DrmEventListener::FlipHandler(int /* fd */, unsigned int /* sequence */,
                                   unsigned int tv_sec, unsigned int tv_usec,
                                   void *user_data) {
  auto *handler = (DrmEventHandler *)user_data;
  if (!handler)
    return;

  handler->HandleEvent((uint64_t)tv_sec * 1000 * 1000 + tv_usec);
  delete handler;  // NOLINT(cppcoreguidelines-owning-memory)
}

void DrmEventListener::UEventHandler() {
  char buffer[1024];
  int ret = 0;

  struct timespec ts {};

  uint64_t timestamp = 0;
  ret = clock_gettime(CLOCK_MONOTONIC, &ts);
  if (!ret)
    timestamp = ts.tv_sec * 1000 * 1000 * 1000 + ts.tv_nsec;
  else
    ALOGE("Failed to get monotonic clock on hotplug %d", ret);

  while (true) {
    ret = read(uevent_fd_.Get(), &buffer, sizeof(buffer));
    if (ret == 0)
      return;

    if (ret < 0) {
      ALOGE("Got error reading uevent %d", ret);
      return;
    }

    if (!hotplug_handler_)
      continue;

    bool drm_event = false;
    bool hotplug_event = false;
    for (uint32_t i = 0; i < ret;) {
      char *event = buffer + i;
      if (strcmp(event, "DEVTYPE=drm_minor") != 0)
        drm_event = true;
      else if (strcmp(event, "HOTPLUG=1") != 0)
        hotplug_event = true;

      i += strlen(event) + 1;
    }

    if (drm_event && hotplug_event)
      hotplug_handler_->HandleEvent(timestamp);
  }
}

void DrmEventListener::Routine() {
  int ret = 0;
  do {
    ret = select(max_fd_ + 1, &fds_, nullptr, nullptr, nullptr);
  } while (ret == -1 && errno == EINTR);

  if (FD_ISSET(drm_->fd(), &fds_)) {
    drmEventContext event_context =
        {.version = 2,
         .vblank_handler = nullptr,
         .page_flip_handler = DrmEventListener::FlipHandler};
    drmHandleEvent(drm_->fd(), &event_context);
  }

  if (FD_ISSET(uevent_fd_.Get(), &fds_))
    UEventHandler();
}
}  // namespace android
