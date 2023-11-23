# Command Line Interface for Netsim (netsim)

Usage:
* `netsim [Options] <COMMAND>`

Options:
* `-h, --help`:    Print help information
* `-v, --verbose`: Set verbose mode

## Commands:
* ### `version`:    Print Netsim version information
    * Usage: `netsim version`
* ### `radio`:      Control the radio state of a device
    * Usage: `netsim radio <RADIO_TYPE> <STATUS> <NAME>`
    * Arguments:
        * \<RADIO_TYPE\>:   Radio type [possible values: ble, classic, wifi, uwb]
        * \<STATUS\>:       Radio status [possible values: up, down]
        * \<NAME\>:         Device name
* ### `move`:       Set the device location
    * Usage: `netsim move <NAME> <X> <Y> [Z]`
    * Arguments:
        * \<NAME\>:         Device name
        * \<X\>:            x position of device
        * \<Y\>:            y position of device
        * [Z]:              Optional z position of device
* ### `devices`:    Display device(s) information
    * Usage: `netsim devices [OPTIONS]`
    * Options:
        * `-c, --continuous`:    Continuously print device(s) information every second
* ### `reset`:      Reset Netsim device scene
    * Usage: `netsim reset`
* ### `pcap`:       Control the packet capture functionalities with commands: list, patch, get
    * Usage: `netsim pcap <COMMAND>`
    * #### Commands
        * `list`:   List currently available Pcaps (packet captures)
            * Usage: `netsim pcap list [PATTERNS]...`
            * Arguments:
                * [PATTERNS]...:  Optional strings of pattern for pcaps to list. Possible filter fields
                                    include Pcap ID, Device Name, and Chip Kind
        * `patch`:  Patch a Pcap source to turn packet capture on/off
            * Usage: `netsim pcap patch <STATE> [PATTERNS]...`
            * Arguments:
                * \<STATE\>:        Packet capture state [possible values: on, off]
                * [PATTERNS]...:  Optional strings of pattern for pcaps to patch. Possible filter fields
                                    include Pcap ID, Device Name, and Chip Kind
        * `get`:    Download the packet capture content
            * Usage: `netsim pcap get [OPTIONS] [PATTERNS]...`
            * Arguments:
                * [PATTERNS]...:  Optional strings of pattern for pcaps to get. Possible filter fields
                                    include Pcap ID, Device Name, and Chip Kind
            * Options:
                * `-o, --location`: Directory to store downloaded pcap(s)
* ### `help`:       Print this message or the help of the given subcommand(s)
