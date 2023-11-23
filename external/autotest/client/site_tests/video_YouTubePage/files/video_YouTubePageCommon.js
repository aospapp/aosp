// Copyright (c) 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

(function() {
  var videoElement = document.querySelector('#movie_player video');
  var videoEvents = {
    timeupdate: []
  };
  var currentlyFullscreen = false

  if (videoElement instanceof HTMLMediaElement) {
    function logEventHappened(e) {
      videoEvents[e.type + '_completed'] = true;
    }

    function logTimeUpdate(e) {
      videoEvents.timeupdate.push(getCurrentTime());
    }

    function onError(e) {
      console.error('Error playing video: ' + e.type);
    }

    videoElement.addEventListener('playing', logEventHappened);
    videoElement.addEventListener('seeking', logEventHappened);
    videoElement.addEventListener('seeked', logEventHappened);
    videoElement.addEventListener('ended', logEventHappened);
    videoElement.addEventListener('timeupdate', logTimeUpdate);
    videoElement.addEventListener('error', onError);
    videoElement.addEventListener('abort', onError);
  }
  else {
    console.error('Can not play non HTML5 video element.');
  }

  /**
   * Event handler to monitor document fullscreenchange events. We are
   * currently fullscreened if a fullscreenElement exists.
   * @see https://fullscreen.spec.whatwg.org/
   */
  function onFullscreen(e) {
    if (document.webkitFullscreenElement === null ||
        document.fullscreenElement === null) {
      currentlyFullscreen = false;
    } else {
      currentlyFullscreen = true;
    }
  }

  function playVideo() {
    videoElement.play();
  }

  function pauseVideo() {
    videoElement.pause();
  }

  function seek(time) {
    videoElement.currentTime = time;
  }

  function seekToAlmostEnd(seconds_before_end) {
    videoElement.currentTime = getDuration() - seconds_before_end;
  }

  function setPlaybackQuality(quality) {
    videoElement.setPlaybackQuality(quality);
  }

  function getVideoState() {
    if (videoElement.ended) {
      return 'ended';
    }
    else if (videoElement.paused) {
      return 'paused';
    }
    else if (videoElement.seeking) {
      return 'seeking';
    }
    else {
      return 'playing';
    }
  }

  function getDuration() {
    return videoElement.duration;
  }

  function getCurrentTime() {
    return videoElement.currentTime;
  }

  function getEventHappened(e) {
    // Pass in the base event name and it will get automatically converted.
    return videoEvents[e + '_completed'] === true;
  }

  function clearEventHappened(e) {
    delete videoEvents[e + '_completed'];
  }

  function getLastSecondTimeupdates(e) {
    var updatesInLastSecond = 0;
    var duration = getDuration();

    for (var index in videoEvents.timeupdate) {
      var update_time = videoEvents.timeupdate[index];
      if (update_time > duration - 1 && update_time < duration) {
        updatesInLastSecond += 1;
      }
    }
    return updatesInLastSecond;
  }

  function getPlaybackQuality() {
    return videoElement.getPlaybackQuality();
  }

  function getFramesStatistics() {
    droppedFramesPercentage = 100 * (videoElement.webkitDroppedFrameCount /
                                     videoElement.webkitDecodedFrameCount);

    return {'droppedFrameCount': videoElement.webkitDroppedFrameCount,
            'decodedFrameCount': videoElement.webkitDecodedFrameCount,
            'droppedFramesPercentage': droppedFramesPercentage};
  }

  function isCurrentlyFullscreen() {
    return currentlyFullscreen;
  }

  window.__videoElement = videoElement;
  window.__playVideo = playVideo;
  window.__pauseVideo = pauseVideo;
  window.__seek = seek;
  window.__seekToAlmostEnd = seekToAlmostEnd;
  window.__getVideoState = getVideoState;
  window.__getDuration = getDuration;
  window.__getCurrentTime = getCurrentTime;
  window.__getEventHappened = getEventHappened;
  window.__clearEventHappened = clearEventHappened;
  window.__getLastSecondTimeupdates = getLastSecondTimeupdates;
  window.__getFramesStatistics = getFramesStatistics;
  window.__isCurrentlyFullscreen = isCurrentlyFullscreen;

  document.addEventListener('webkitfullscreenchange', onFullscreen);
  document.addEventListener('fullscreenchange', onFullscreen);

  return window.__videoElement !== null;
})();
