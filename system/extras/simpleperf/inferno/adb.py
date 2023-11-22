import subprocess
import abc
import os

BIN_PATH = "../scripts/bin/android/%s/simpleperf"

class Abi:
    ARM    = 1
    ARM_64 = 2
    X86    = 3
    X86_64 = 4

    def __init__(self):
        pass

class Adb:

    def __init__(self):
        pass


    def delete_previous_data(self):
        err = subprocess.call(["adb", "shell", "rm", "-f", "/data/local/tmp/perf.data"])


    def get_process_pid(self, process_name):
        piof_output = subprocess.check_output(["adb", "shell", "pidof", process_name])
        try:
            process_id = int(piof_output)
        except ValueError:
            process_id = 0
        return process_id


    def pull_data(self):
        err = subprocess.call(["adb", "pull", "/data/local/tmp/perf.data", "."])
        return err


    @abc.abstractmethod
    def collect_data(self, simpleperf_command):
        raise NotImplementedError("%s.collect_data(str) is not implemented!" % self.__class__.__name__)


    def get_props(self):
        props = {}
        output = subprocess.check_output(["adb", "shell", "getprop"])
        lines = output.split("\n")
        for line in lines:
            tokens = line.split(": ")
            if len(tokens) < 2:
                continue
            key = tokens[0].replace("[", "").replace("]", "")
            value = tokens[1].replace("[", "").replace("]", "")
            props[key] = value
        return props

    def parse_abi(self, str):
        if str.find("arm64") != -1:
            return Abi.ARM_64
        if str.find("arm") != -1:
            return Abi.ARM
        if str.find("x86_64") != -1:
            return Abi.X86_64
        if str.find("x86") != -1:
            return Abi.X86
        return Abi.ARM_64

    def get_exec_path(self, abi):
        folder_name = "arm64"
        if abi == Abi.ARM:
            folder_name = "arm"
        if abi == Abi.X86:
            folder_name = "x86"
        if abi == Abi.X86_64:
            folder_name = "x86_64"
        return os.path.join(os.path.dirname(__file__), BIN_PATH % folder_name)

    def push_simpleperf_binary(self):
        # Detect the ABI of the device
        props = self.get_props()
        abi_raw = props["ro.product.cpu.abi"]
        abi = self.parse_abi(abi_raw)
        exec_path = self.get_exec_path(abi)

        # Push simpleperf to the device
        print "Pushing local '%s' to device." % exec_path
        subprocess.call(["adb", "push", exec_path, "/data/local/tmp/simpleperf"])
