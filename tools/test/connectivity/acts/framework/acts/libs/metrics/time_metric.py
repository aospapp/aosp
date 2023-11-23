import time
from enum import IntEnum

from acts.libs.metrics.metric import PassiveMetric, JsonMetric


class TestTime(PassiveMetric, JsonMetric):
    """A metric which will measure the amount of time passed."""

    class Status(IntEnum):
        STATUS_STOPPED = 0
        STATUS_RUNNING = 1
        STATUS_PAUSED = 2

    def __init__(self):
        super().__init__()
        self.status = TestTime.Status.STATUS_STOPPED
        self.last_start_time = 0
        self.duration = 0

    def start(self):
        if self.status == TestTime.Status.STATUS_STOPPED:
            self.duration = 0
        if self.status != TestTime.Status.STATUS_RUNNING:
            self.last_start_time = time.time()
        self.status = TestTime.Status.STATUS_RUNNING

    def pause(self):
        if self.status == TestTime.Status.STATUS_RUNNING:
            self.status = TestTime.Status.STATUS_PAUSED
            self.duration = time.time() - self.last_start_time

    def stop(self):
        if self.status == TestTime.Status.STATUS_RUNNING:
            self.duration = time.time() - self.last_start_time
        self.status = TestTime.Status.STATUS_STOPPED

    def as_json(self):
        return {
            'time': self.duration
        }
