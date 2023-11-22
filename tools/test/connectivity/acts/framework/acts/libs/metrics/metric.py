import json
import os
import threading


class Metric(object):
    """An object that can measure a metric during a test."""

    def __init__(self):
        self.test = None
        self.testcase = None
        self.metric_dir = None
        self.binding_name = None

    def setup(self, test, testcase, binding_name):
        """Sets up the metric to work with a test.

        Args:
            test: An instance of the test class.
            testcase: The testcase function to be called.
            binding_name: The name this metric will be bound as in the output.
        """
        self.test = test
        self.testcase = testcase
        self.binding_name = binding_name
        self.metric_dir = os.path.join(self.test.log_path, 'undeclared_output',
                                       type(self.test).__name__,
                                       self.testcase.__name__,
                                       self.binding_name)
        os.makedirs(self.metric_dir)

    def finish(self):
        """Called when the metric is being cleaned up and has finished."""
        pass


class PassiveMetric(Metric):
    """A metric that records information passively while a test runs."""

    def start(self):
        """Starts the metric."""
        pass

    def pause(self):
        """Pauses the metric."""
        pass

    def stop(self):
        """Stops the metric."""
        pass


class JsonMetric(Metric):
    """A metric that will write out to a json file when finished.

    Warnings: This metric will store all values in memory and will not be
    released automatically until the process ends. For large amounts of data
    either make sure to clear the data manually when done, or stream to a file
    directly while the test is running.
    """

    def as_json(self):
        pass

    def finish(self):
        metric_file = os.path.join(self.metric_dir, 'raw.json')
        with open(metric_file, mode='w') as f:
            f.write(json.dumps(self.as_json(), indent=2))


class RecordableMetric(Metric):
    """A metric that provides recording data as a stream.

    RecordableMetrics provide recording x,y value metrics as a stream. This
    is often used to record variable and constant data such as a metrics change
    over time.

    RecordableMetrics can record to multiple channels which represent multiple
    recordings of the same metric. For example you might record the same metric
    on multiple phones. Each pone would be given there own channel.

    Metrics will be recorded to a smetric file. Each value recorded will be
    recorded on it's own line as a json. To read back simply read each entry
    line by line.

    Attributes:
        default_channel: The default channel to write to if none is given.
        open_channels: A mapping of channel name to file handle. Channels will
            be kept open until the metric finishes to improve performance.
            This mapping keeps track of all open channels so they may be written
            to multiple times, and closed on finished.
    """

    def __init__(self, default_channel='std'):
        """
        Args:
            default_channel: The channel to record to by default if no channel
                is given.
        """
        self.default_channel = default_channel
        self.open_channels = {}

    def record(self, value, key, channel=None):
        """Records a single pair of values.

        Args:
            value: The variable part of the recording. This will be the metric
                data.
            key: The constant part of the recording. This will signify the
                conditions the measurement happened.
            channel: The channel to record to.
        """
        channel = channel or self.default_channel

        stream_file = self._get_channel_stream(channel)

        entry = {
            'key': key,
            'value': value,
        }

        stream_file.write(json.dumps(entry))
        stream_file.write('\n')

    def _get_channel_stream(self, channel):
        if channel not in self.open_channels:
            channel_file = os.path.join(self.metric_dir, channel + '.smetric')

            if os.path.exists(channel_file):
                raise ValueError(
                    'Trying to record to a smetric that already exists.')

            if not os.path.exists(os.path.dirname(channel_file)):
                os.makedirs(os.path.dirname(channel_file))

            stream_file = open(channel_file, mode='w')
            self.open_channels[channel] = stream_file
        else:
            stream_file = self.open_channels[channel]
        return stream_file

    def finish(self):
        for blob in self.open_channels.values():
            blob.close()


class AsyncRecordableMetric(RecordableMetric, PassiveMetric):
    """A metric that runs in another thread and records at a certain rate."""

    def __init__(self, rate, default_channel="std"):
        """
        Args:
            rate: The rate to record at (in seconds).
            default_channel: The channel to record to by default.
        """
        super().__init__(default_channel)
        self.rate = rate
        self.timer = None
        self.has_stopped = False
        self.lock = threading.Lock()

    def start(self):
        if self.timer:
            return

        self.has_stopped = False

        self._iteration()

    def stop(self):
        with self.lock:
            if self.timer:
                self.timer.cancel()
                self.timer = None

    def _iteration(self):
        with self.lock:
            if not self.has_stopped:
                self.run_one_iteration()
                self.timer = threading.Timer(self.rate, self._iteration)
                self.timer.start()

    def run_one_iteration(self):
        """Called once every iteration to record."""
        pass
