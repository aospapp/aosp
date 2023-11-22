from adb import Adb
import subprocess

class AdbRoot(Adb):
    def collect_data(self, process):
        if not process.args.skip_push_binary:
            self.push_simpleperf_binary()
        subprocess.call(["adb", "shell", "cd /data/local/tmp; " + process.cmd])
        return True