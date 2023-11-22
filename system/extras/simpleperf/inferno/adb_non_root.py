from adb import Adb
import subprocess
import time

class AdbNonRoot(Adb):
    # If adb cannot run as root, there is still a way to collect data but it is much more complicated.
    # 1. Identify the platform abi, use getprop:  ro.product.cpu.abi
    # 2. Push the precompiled scripts/bin/android/[ABI]/simpleperf to device /data/local/tmp/simpleperf
    # 4. Use run-as to copy /data/local/tmp/simplerperf -> /apps/installation_path/simpleperf
    # 5. Use run-as to run: /apps/installation_path/simpleperf -p APP_PID -o /apps/installation_path/perf.data
    # 6. Use run-as fork+pipe trick to copy /apps/installation_path/perf.data to /data/local/tmp/perf.data
    def collect_data(self, process):

        if not process.args.skip_push_binary:
          self.push_simpleperf_binary()

        # Copy simpleperf to the data
        subprocess.check_output(["adb", "shell", "run-as %s" % process.name, "cp", "/data/local/tmp/simpleperf", "."])

        # Patch command to run with path to data folder where simpleperf was written.
        process.cmd = process.cmd.replace("/data/local/tmp/perf.data", "./perf.data")

        # Start collecting samples.
        process.cmd = ("run-as %s " % process.name) + process.cmd
        subprocess.call(["adb", "shell", process.cmd])

        # Wait sampling_duration+1.5 seconds.
        time.sleep(int(process.args.capture_duration) + 1)

        # Move data to a location where shell user can read it.
        subprocess.call(["adb", "shell", "run-as %s cat perf.data | tee /data/local/tmp/perf.data >/dev/null" % (process.name)])

        return True
