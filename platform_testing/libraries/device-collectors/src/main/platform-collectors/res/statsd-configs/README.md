# Statsd Configs

This directory hosts binary statsd config protos.

## What they do

A config tells statsd what metrics to collect from the device during a test. For example,
`app-start` will instruct statsd to collect app startup metrics.

## Checking in a config

To check in a config, follow these steps:

1. Create a directory under this directory for the new config (e.g. `app-start`).
2. Put the new config in the subdirectory using the directory name + `.pb` extension.
3. Write a README file explaining what the config does and put it under the new subdirectory.

# (Internal only) Creating a config

_This section is subject to change as the Android Metrics team evolve their tools._

To create a config, follow these steps:

1. Follow the
[Add your metrics to a config](http://go/westworld-modulefooding#add-your-metrics-to-a-config)
section (and this section only) in the Android Metrics documentation to create a new config file.
2. Validate the config following the
[Validate and sent a changelist for review](http://go/westworld-modulefooding#validate-and-send-a-changelist-for-review)
section, but skip the sending CL for review part.
2. Build the config parsing utility:
`blaze build -c opt java/com/google/wireless/android/stats/westworld:parse_definitions`
3. Use the utility to create the binary config:
```
blaze-bin/java/com/google/wireless/android/stats/westworld/parse_definitions \
--action=WRITE_STATSD_CONFIG \
--definitions_dir=wireless/android/stats/platform/westworld/public/definitions/westworld/ \
--config_name=<You config's name defined in step 1> \
--allowed_sources=<Comma separated list of allowed log sources> \
--output_file=<Output path of your config>
```
Common allowed sources include `AID_ROOT`, `AID_SYSTEM`, `AID_RADIO`, `AID_BLUETOOTH`,
`AID_GRAPHICS`, `AID_STATSD` and `AID_INCIDENTD`.

Once the config file is generated, it can be checked in following the steps in the "Checking in a
config" section.
