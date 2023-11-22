This folder contains tools to be used in the testing lab.

NOTE: The config.json file in this folder contains random values - for the full
config file, look at the corresponding folder in google3

Python dependencies:
  - subprocess32
  - shellescape
  - psutil
  - IPy

Metrics that can be gathered, listed by name of file and then key to response
dict:

* adb_hash:
    env: whether $ADB_VENDOR_KEYS is set (bool)
    hash: hash of keys in $ADB_VENDOR_KEYS (string)
* cpu:
    cpu: list of CPU core percents (float)
* disk:
    total: total space in 1k blocks (int)
    used: total used in 1k blocks (int)
    avail: total available in 1k blocks (int)
    percent_used: percentage space used (0-100) (int)
* name:
    name: system's hostname(string)
* network:
    connected: whether the network is connected (list of bools)
* num_users:
    num_users: number of users (int)
* process_time:
    pid_times: a list of (time, PID) tuples where time is a string
              representing time elapsed in D-HR:MM:SS format and PID is a string
              representing the pid (string, string)
* ram:
    total: total physical RAM available in KB (int)
    used: total RAM used by system in KB (int)
    free: total RAM free for new process in KB (int)
    buffers: total RAM buffered by different applications in KB
    cached: total RAM for caching of data in KB
* read:
    cached_read_rate: cached reads in MB/sec (float)
    buffered_read_rate: buffered disk reads in MB/sec (float)
* system_load:
    load_avg_1_min: system load average for past 1 min (float)
    load_avg_5_min: system load average for past 5 min (float)
    load_avg_15_min: system load average for past 15 min (float)
* uptime:
    time_seconds: uptime in seconds (float)
* usb:
    devices: a list of Device objects, each with name of device, number of bytes
    transferred, and the usb bus number/device id.
* verify:
    device serial number as key: device status as value
* version:
    fastboot_version: which version of fastboot (string)
    adb_version: which version of adb (string)
    python_version: which version of python (string)
    kernel_version: which version of kernel (string)
* zombie:
    adb_zombies: list of adb zombie processes (PID, state, name)
    fastboot_zombies: list of fastboot zombie processes (PID, state, name)
    other_zombies: list of other zombie processes (PID, state, name)
