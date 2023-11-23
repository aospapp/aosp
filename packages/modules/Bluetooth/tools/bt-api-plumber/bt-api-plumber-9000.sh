#!/bin/bash

YELLOW="\033[1;33m"
NOCOLOR="\033[0m"
BLUE="\033[1;34m"
RED="\033[1;91m"

# TODO(optedoblivion): Check for 'git' and 'clang' binary

function check_environment {
    if [[ -z "${ANDROID_BUILD_TOP}" ]] || [[ -z "${ANDROID_HOST_OUT}" ]] ; then
      echo -e "${RED}ANDROID_BUILD_TOP${NOCOLOR} or ${RED}ANDROID_HOST_OUT${NOCOLOR} is not set for host run"
      echo -e "Navigate to android root and run:"
      echo -e "${YELLOW}"
      echo -e ". build/envsetup.sh"
      echo -e "lunch <fish>"
      echo -e "${NOCOLOR}"
      echo
      exit 1
    fi
}

QUOTES=(
    "It's a-me, Martino!"
    "Hello!"
    "Wahoo!"
    "Oh yeah!"
    "Martino time!"
    "Lucky!"
    "Hui hew! Just what I needed!"
    "Spin! Hammer! Fire! Jump!"
    "Yiiiiiipeee!"
    "Yeah, ha ha ha!"
    "Waha!"
    "Let's-a go!"
    "Here we go!"
    "Yes! I'm the winner!"
    "Luigi!"
    "Way to go!"
    "Here I go!"
    "Mama Mia!"
)

VERBOSE=false

# Controller
CONTROLLER=false
CONTROLLER_FILES=(
    "system/gd/hci/controller.h"
    "system/gd/hci/controller.cc"
    "system/gd/hci/controller.cc"
    "system/gd/hci/controller_mock.h"
    "system/gd/hci/controller_test.cc"
)
CONTROLLER_FIND_PATTERNS=(
    " LeRand(LeRandCallback cb);"
    "Controller::impl::le_rand_cb<LeRandCompleteView>, cb));"
    "impl::le_rand, cb);"
    " (LeRandCallback cb));"
    " le_rand_set.get_future().wait();"
)
CONTROLLER_CODE_TEMPLATES=(
    "  virtual void :CamelApiName:();"
    "  }\\\n\\\n  void :snake_api_name:() {\\\n    \\\\\/\\\\\/TODO(WHOAMI): Implement HCI Call"
    "}\\\n\\\nvoid Controller:::CamelApiName:() {\\\n  CallOn(impl_.get(), \\\\\&impl:::snake_api_name:);"
    "  MOCK_METHOD(void, :CamelApiName:, ());"
    "}\\\n\\\nTEST_F(ControllerTest, :CamelApiName:Test) {\\\n  controller->:CamelApiName:();"
)
CONTROLLER_REPLACEMENT_PATTERNS=(
    "FIRST\n\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
)

# Controller shim
CONTROLLER_SHIM=false
CONTROLLER_SHIM_FILES=(
    "system/device/include/controller.h"
    "system/main/shim/controller.cc"
    "system/main/shim/controller.cc"
    "system/test/mock/mock_device_controller.cc"
    "system/test/mock/mock_device_controller.cc"
)
CONTROLLER_SHIM_FIND_PATTERNS=(
    " (\*le_rand)(LeRandCallback);"
    "  bluetooth::shim::GetController()->LeRand(cb);"
    " controller_le_rand,"
    " le_rand(LeRandCallback cb) { return BTM_SUCCESS; }"
    " le_rand,"
)
CONTROLLER_SHIM_CODE_TEMPLATES=(
    "  uint8_t (*:snake_api_name:)(void);"
    "  return BTM_SUCCESS;\\\n}\\\n\\\nstatic uint8_t controller_:snake_api_name:() {\\\n  bluetooth::shim::GetController()->:CamelApiName:();"
    "    .:snake_api_name: = controller_:snake_api_name:"
    "tBTM_STATUS :snake_api_name:() { return BTM_SUCCESS; }"
    "    :snake_api_name:,"
)
CONTROLLER_SHIM_REPLACEMENT_PATTERNS=(
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
)

## Files length must match templates and replacement pattern lengths!
# BTM
BTM_SHIM=false
BTM_SHIM_FILES=(
    "system/main/shim/btm_api.h"
    "system/main/shim/btm_api.cc"
    "system/test/mock/mock_main_shim_btm_api.cc"
)
BTM_SHIM_FIND_PATTERNS=(
    "TM_STATUS BTM_LeRand(LeRandCallback);"
    "ontroller_get_interface()->le_rand(cb);"
    " bluetooth::shim::BTM_LeRand(LeRandCallback cb) {"
)
BTM_SHIM_CODE_TEMPLATES=(
    "\\\\\/*******************************************************************************\\\n *\\\n * Function        BTM_:CamelApiName:\\\n *\\\n * Description    :API_DESCRIPTION:\\\n *\\\n * Parameters\\\n *\\\n *******************************************************************************\\\\\/\\\ntBTM_STATUS BTM_:CamelApiName:(void);"
    "  return BTM_SUCCESS;\\\n}\\\n\\\ntBTM_STATUS bluetooth::shim::BTM_:CamelApiName:() {\\\n  \\\\\/\\\\\/PLUMB: controller_get_interface()->:snake_api_name:();"
    "  inc_func_call_count(__func__);\\\n  return BTM_SUCCESS;\\\n}\\\n\\\ntBTM_STATUS bluetooth::shim::BTM_:CamelApiName:() {"
)
BTM_SHIM_REPLACEMENT_PATTERNS=(
    "FIRST\n\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
)

# BTA
BTA=false
BTA_FILES=(
    # External BTA API
    "system/bta/include/bta_api.h"
    "system/bta/dm/bta_dm_api.cc"
    # internal BTA API
    "system/bta/dm/bta_dm_int.h"
    "system/bta/dm/bta_dm_act.cc"
)
BTA_FIND_PATTERNS=(
    "extern void BTA_DmLeRand(LeRandCallback cb);"
    "do_in_main_thread(FROM_HERE, base::Bind(bta_dm_le_rand, cb));"
    "extern void bta_dm_le_rand(LeRandCallback cb);"
    "ooth::shim::BTM_LeRand(cb);"
)
BTA_CODE_TEMPLATES=(
    "\\\\\/*******************************************************************************\\\n *\\\n * Function        BTA_Dm:CamelApiName:\\\n *\\\n * Description    :API_DESCRIPTION:\\\n *\\\n * Parameters\\\n *\\\n *******************************************************************************\\\\\/\\\nextern void BTA_Dm:CamelApiName:();"
    "}\\\n\\\nvoid BTA_Dm:CamelApiName:() {\\\n  APPL_TRACE_API(\"BTA_Dm:CamelApiName:\");\\\n  do_in_main_thread(FROM_HERE, base::Bind(bta_dm_:snake_api_name:));"
    "extern void bta_dm_:snake_api_name:();"
    "}\\\n\\\n\\\\\/*******************************************************************************\\\n *\\\n * Function        BTA_Dm:CamelApiName:\\\n *\\\n * Description    :API_DESCRIPTION:\\\n *\\\n * Parameters\\\n *\\\n *******************************************************************************\\\\\/\\\nvoid bta_dm_:snake_api_name:() {\\\n  \\\\\/\\\\\/PLUMB: bluetooth::shim::BTM_:CamelApiName:();"
)
BTA_REPLACEMENT_PATTERNS=(
    "FIRST\n\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
)

# BTIF
BTIF=false
BTIF_FILES=(
    # BTIF DM Layer
    "system/btif/include/btif_dm.h"
    "system/btif/src/btif_dm.cc"
    # BTIF Layer
    "system/include/hardware/bluetooth.h"
    "system/btif/src/bluetooth.cc"
    "system/btif/src/bluetooth.cc"
    "system/service/hal/fake_bluetooth_interface.cc"
    # Yes double it for two replacements
    "system/test/mock/mock_bluetooth_interface.cc"
    "system/test/mock/mock_bluetooth_interface.cc"
)
BTIF_FIND_PATTERNS=(
    # BTIF DM Layer
    "oid btif_dm_le_rand(LeRandCallback callback);"
    "_dm_le_rand(callback);"
    # BTIF Layer
    "} bt_interface_t;"
    " void dump("
    " le_rand,"
    " le_rand "
    "EXPORT_SYMBOL"
    " le_rand,"
)
BTIF_CODE_TEMPLATES=(
    # BTIF DM Layer
    " void btif_dm_:snake_api_name:();"
    "}\\\n\\\nvoid btif_dm_:snake_api_name:() {\\\n  \\\\\/\\\\\/PLUMB: BTA_Dm:CamelApiName:();"
    # BTIF Layer
    " \\\n\\\\\/**\\\n *\\\n * :API_DESCRIPTION:\\\n *\\\n *\\\\\/\\\nint (*:snake_api_name:)();"
    " int :snake_api_name:() {\\\n  if(!interface_ready()) return BT_STATUS_NOT_READY;\\\n  do_in_main_thread(FROM_HERE, base::BindOnce(btif_dm_:snake_api_name:));\\\n  return BT_STATUS_SUCCESS;\\\n}\\\n\\\nstatic"
    "\ \ \ \ :snake_api_name:,"
    "*\\\\\/\\\n\ \ \ \ nullptr, \\\\\/* :snake_api_name: "
    "static int :snake_api_name:() { return 0; }\\\n\\\nE"
    "\ \ \ \ :snake_api_name:,"
)
BTIF_REPLACEMENT_PATTERNS=(
    # BTIF DM Layer
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    # BTIF Layer
    "SECOND\nFIRST"
    "SECONDFIRST"
    "FIRST\nSECOND"
    "FIRSTSECOND"
    "SECONDFIRST"
    "FIRST\nSECOND"
)

# Topshim
TOPSHIM=false
TOPSHIM_FILES=(
    # Topshim API
    "system/gd/rust/topshim/src/btif.rs"
    "system/gd/rust/topshim/facade/src/adapter_service.rs"
    # Topshim Test API
    "system/blueberry/facade/topshim/facade.proto"
    "system/blueberry/tests/gd/rust/topshim/facade/automation_helper.py"
)
TOPSHIM_FIND_PATTERNS=(
    # Topshim API
    " le_rand)"
    ".le_rand();"
    # Topshim Test API
    " LeRand(google.protobuf.Empty) returns (google.protobuf.Empty) {}"
    " self.adapter_stub.LeRand(empty_proto.Empty())"
)
TOPSHIM_CODE_TEMPLATES=(
    # Topshim API
    "    }\\\n\\\n    pub fn :snake_api_name:(\\\\\&self) -> i32 {\\\n        ccall!(self, :snake_api_name:)"
    "        ctx.spawn(async move {\\\n            sink.success(Empty::default()).await.unwrap();\\\n        })\\\n    }\\\n\\\n    fn :snake_api_name:(\\\\\&mut self, ctx: RpcContext<'_>, _req: Empty, sink: UnarySink<Empty>) {\\\n        self.btif_intf.lock().unwrap().:snake_api_name:();"
    # Topshim Test API
    "  rpc :CamelApiName:(google.protobuf.Empty) returns (google.protobuf.Empty) {}"
    "    async def :snake_api_name:(self):\\\n        await self.adapter_stub.:CamelApiName:(empty_proto.Empty())"
)
TOPSHIM_REPLACEMENT_PATTERNS=(
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    "FIRST\nSECOND"
    "FIRST\n\nSECOND"
)

function help_menu {
    echo
    echo -e "${YELLOW}Help menu${NOCOLOR}"
    echo -e "==================================="
    echo -e "${BLUE}  --controller${NOCOLOR}"
    echo -e "    Adds plumbing for the GD Controller Layer for the API."
    echo -e "    This includes test file changes required to build."
    echo -e "${BLUE}  --controller-shim${NOCOLOR}"
    echo -e "    Adds plumbing for the GD Controller Shim Layer for the API."
    echo -e "    This includes test file changes required to build."
    echo -e "    Will autoplumb to ONLY the GD controller if --controller flag is set. (as opposed to legacy controller btu_hcif)"
    echo -e "${BLUE}  --btm${NOCOLOR}"
    echo -e "    Adds plumbing for the BTM Shim Layer for the given API."
    echo -e "    Will autoplumb to ONLY the controller shim if --controller-shim flag is set. vs directly to legacy btu_hcif"
    echo -e "${BLUE}  --bta${NOCOLOR}"
    echo -e "    Adds plumbing for the BTA Layer for the given API."
    echo -e "    Will autoplumb to BTM if --btm set."
    echo -e "${BLUE}  --btif${NOCOLOR}"
    echo -e "    Adds plumbing for the BTIF Layer for the API."
    echo -e "    This currently includes JNI as it is a requirement for Android to build."
    echo -e "    Will autoplumb to BTA if --bta set."
    echo -e "${BLUE}  --topshim${NOCOLOR}"
    echo -e "    Adds plumbing for the topshim to BTIF Layer for the API."
    echo -e "    This will also include testing APIs callable from python tests."
    echo -e "    Will autoplumb to BTIF if --btif set."
    echo -e "${BLUE}  --verbose${NOCOLOR}"
    echo -e "    Prints verbose logging."
    echo
    echo -e "Usage: $0 [--controller|--controller-shim|--btm|--bta|--btif|--topshim] [CamelCaseApiName] [snake_case_api_name] (description)"
    echo -e "        ${YELLOW}e.g."
    echo -e "         $0 --controller --btm ClearEventMask clear_event_mask \"Clear out the event mask\""
    echo -e "         $0 --controller --btm --bta --btif --topshim ClearEventMaskclear_event_mask \"Clear out the event mask\" ${NOCOLOR}"
    echo
}

## Start parsing arguments here
POSITIONAL=()
function parse_options {
    while [[ $# -gt 0 ]]
    do
    key="$1"
    case $key in
        --verbose)
            VERBOSE=true
            shift
            ;;
        --help)
            help_menu
            shift
            exit 0
            ;;
        --controller)
            CONTROLLER=true
            shift
            ;;
        --controller-shim)
            CONTROLLER_SHIM=true
            shift
            ;;
        --btm)
            # Actually we skip BTM here and just use the BTM Shim
            BTM_SHIM=true
            shift
            ;;
        --bta)
            BTA=true
            shift
            ;;
        --btif)
            BTIF=true
            shift
            ;;
        --topshim)
            TOPSHIM=true
            shift
            ;;
        --*)
            echo "$0: unrecognized argument: '$1'"
            echo "Try '$0 --help' for more information"
            exit 1
            shift
            ;;
        *)
            POSITIONAL+=("${1}")
            shift
            ;;
    esac
    done
    set -- "${POSITIONAL[@]}"
}

function show_mario {
    MSG="$(mario_message)I'm plumbing the '${1}'"
    echo
    echo -e "${RED} ⠀⠀⠀      ⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣀⣠⣤⣤⣤⣄⣀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀  "⠀
    echo -e "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⣴⡾⣻⣿⣿⣿⣿⣯⣍⠛⠻⢷⣦⣀⠀⠀⠀⠀⠀⠀⠀  "
    echo -e "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣴⣿⠟⢁⣾⠟⠋⣁⣀⣤⡉⠻⣷⡀⠀⠙⢿⣷⣄⠀⠀⠀⠀⠀  "⠀
    echo -e "${NOCOLOR}⠀⠀⠀⠀⠀⠀⠀⢀⡀${RED}⠀⠀⠀⠀⠀⠀⣰⣿⠏⠀⠀⢸⣿⠀⠼⢋⣉⣈⡳⢀⣿⠃⠀⠀⠀⠙⣿⣦⡀⠀⠀⠀  "⠀
    echo -e "${NOCOLOR}⠀⠀⠀⠀⠀⠀⢰⡿⠿⣷⡀⠀${RED}⠀⠀⣼⣿⠃⠀⠀⣀⣤⡿⠟⠛⠋⠉⠉⠙⢛⣻⠶⣦⣄⡀⠀⠘⣿⣷⡀⠀⠀  "⠀
    echo -e "${NOCOLOR}⢠⣾⠟⠳⣦⣄⢸⡇⠀⠈⣷⡀${RED}⠀⣼⣿⡏⢀⣤⡾${NOCOLOR}⢋⣵⠿⠻⢿⠋⠉⠉⢻⠟⠛⠻⣦⣝${RED}⠻⣷⣄⠸⣿⣿${NOCOLOR}⠀⠀     ( ${MSG} )"⠀
    echo -e "⠘⣧⠀⠀⠀⠙⢿⣿⠀⠀⢸⣷${RED}⠀⣿⣿⣧⣾⣏${NOCOLOR}⡴⠛⢡⠖⢛⣲⣅⠀⠀⣴⣋⡉⠳⡄⠈⠳${RED}⢬⣿⣿⣿⡿${NOCOLOR}⠀⠀    O"⠀
    echo -e "⠀⠘⠷⣤⣀⣀⣀⣽⡶⠛⠛⠛⢷⣿⣿⣿⣿⣏⠀⠀⡏⢰⡿⢿⣿⠀⠀⣿⠻⣿⠀⡷⠀⣠⣾⣿⡿⠛⠷⣦⠀   o"⠀
    echo -e "⠀⠀⢀⣾⠟⠉⠙⣿⣤⣄⠀⢀⣾⠉⠀⢹⣿⣿⣷⠀⠹⡘⣷⠾⠛⠋⠉⠛⠻⢿⡴⢃⣄⣻⣿⣿⣷⠀⠀⢹⡇  ."⠀
    echo -e "⠀⠀⢸⡇⠈⠉⠛⢦⣿⡏⠀⢸⣧⠀⠈⠻⣿⡿⢣⣾⣦⣽⠃⠀⠀⠀⠀⠀⠀⠀⣷⣾⣿⡇⠉⢿⡇⠀⢀⣼⠇  "⠀
    echo -e "⠀⠀⠘⣷⡠⣄⣀⣼⠇⠀⠀⠀⠻⣷⣤⣀⣸⡇⠀⠹⣿⣿⣦⣀⠀⠀⠀⠀⢀⣴⣿⣿⡟⠀⠀⢸⣷⣾⡿⠃⠀  "⠀
    echo -e "⠀⠀⠀⠈⠻⢦⣍⣀⣀⣀⡄⠀⣰⣿⡿⠿⢿⣇⠀⠀⠉⠛⠻⣿⣿⡷⠾⣿⣿⡿⠉⠁⠀⠀⢀⣾⠋⠁⠀⠀⠀  "
    echo -e "⠀⠀⠀⠀⠀⠀⠈⠉⠉⠙⠿⢿⣿⣇⠀⠀⠈⢿⣧⣄⠀⠀⠀⢹⣷⣶⣶⣾⣿⡇⠀⠀⣀⣴⡿⣧⣄⡀⠀⠀⠀  "⠀
    echo -e "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠙⢿⣷⡀⠀⠀⠙⢿⣿⣶⣤⡀⠻⢤⣀⡤⠞⢀⣴⣿⣿⠟⢷⡀⠙⠻⣦⣄⠀  "⠀
    echo -e "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⢻⣦⠀⢠⡟⠁⠙⢻⣿⠷⠶⣶⠶⠾⠛⠙⣿⠇⠀⠀⢻⡄⠀⠀⠙⢷⡀ "
    echo -e "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣸⣿⡀⣿⠁⣤⣤⡄⢻⡶⠶⠛⠛⠛⠛⠛⣿⢠⣾⣷⣆⢻⡀⠀⠀⠈⣷ "
    echo -e "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣿⣿⣿⣿⢸⣿⣿⣿⡈⢿⡀⠀⠀⠀⠀⠀⡿⢸⣿⣿⣿⢸⡇⠀⠀⠀⡟ "
    echo -e "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠉⠉⠉⠈⠉⠉⠉⠁⠈⠁⠀⠀⠀⠀⠈⠁⠈⠉⠉⠉⠀⠁⠀⠀⠈⠁ "
    echo
}

function mario_done {
    echo -e "${YELLOW}Done.${NOCOLOR}"
}

function mario_message {
    I=$((0 + $RANDOM % ${#QUOTES[@]}))
    echo -en "${BLUE}${QUOTES[$I]}! ${BLUE}${2}${YELLOW}${1} ${NOCOLOR}"
}

# TODO: Pass in which patterns and templates to use
function plumbit {
    layer="$1"
    shift
    camel_api_name="$1"
    shift
    snake_api_name="$1"
    shift
    api_description="$1"
    shift
    files_array=($@)

    WORKING_CODE_TEMPLATES=()
    WORKING_PATTERNS=()
    AUTOPLUMB=false
    if [ "$layer" == "controller" ]; then
        WORKING_PATTERNS=("${CONTROLLER_FIND_PATTERNS[@]}")
        WORKING_CODE_TEMPLATES=("${CONTROLLER_CODE_TEMPLATES[@]}")
        WORKING_REPLACEMENTS=("${CONTROLLER_REPLACEMENT_PATTERNS[@]}")
    elif [ "$layer" == "controller_shim" ]; then
        WORKING_PATTERNS=("${CONTROLLER_SHIM_FIND_PATTERNS[@]}")
        WORKING_CODE_TEMPLATES=("${CONTROLLER_SHIM_CODE_TEMPLATES[@]}")
        WORKING_REPLACEMENTS=("${CONTROLLER_SHIM_REPLACEMENT_PATTERNS[@]}")
        AUTOPLUMB=$CONTROLLER
    elif [ "$layer" == "btm_shim" ]; then
        WORKING_PATTERNS=("${BTM_SHIM_FIND_PATTERNS[@]}")
        WORKING_CODE_TEMPLATES=("${BTM_SHIM_CODE_TEMPLATES[@]}")
        WORKING_REPLACEMENTS=("${BTM_SHIM_REPLACEMENT_PATTERNS[@]}")
        AUTOPLUMB=$CONTROLLER_SHIM
    elif [ "$layer" == "bta" ]; then
        WORKING_PATTERNS=("${BTA_FIND_PATTERNS[@]}")
        WORKING_CODE_TEMPLATES=("${BTA_CODE_TEMPLATES[@]}")
        WORKING_REPLACEMENTS=("${BTA_REPLACEMENT_PATTERNS[@]}")
        AUTOPLUMB=$BTM_SHIM
    elif [ "$layer" == "btif" ]; then
        WORKING_PATTERNS=("${BTIF_FIND_PATTERNS[@]}")
        WORKING_CODE_TEMPLATES=("${BTIF_CODE_TEMPLATES[@]}")
        WORKING_REPLACEMENTS=("${BTIF_REPLACEMENT_PATTERNS[@]}")
        AUTOPLUMB=$BTA
    elif [ "$layer" == "topshim" ]; then
        WORKING_PATTERNS=("${TOPSHIM_FIND_PATTERNS[@]}")
        WORKING_CODE_TEMPLATES=("${TOPSHIM_CODE_TEMPLATES[@]}")
        WORKING_REPLACEMENTS=("${TOPSHIM_REPLACEMENT_PATTERNS[@]}")
        AUTOPLUMB=$BTIF
    fi

    for index in ${!files_array[@]}; do
        CODE=$(echo "${WORKING_CODE_TEMPLATES[$index]}" | sed "s/:CamelApiName:/$camel_api_name/g" | sed "s/:snake_api_name:/$snake_api_name/g" | sed "s/WHOAMI/$(whoami)/g" | sed "s/:API_DESCRIPTION:/${api_description}/g")
        if [ "$AUTOPLUMB" == true ]; then
            CODE=$(echo "${CODE}" | sed "s/PLUMB:/ Autoplumbed\\\\\\\n /g")
        fi
        PATTERN="${WORKING_PATTERNS[$index]}"
        REPLACEMENT=$(echo ${WORKING_REPLACEMENTS[$index]} | sed s/FIRST/"\\${PATTERN}"/g | sed s/SECOND/"${CODE}"/g)
        if [ "$VERBOSE" == true ]; then
            echo sed -i "s/\\${PATTERN}/\\${REPLACEMENT}/g" "${files_array[$index]}"
        fi
        sed -i "s/\\${PATTERN}/\\${REPLACEMENT}/g" "${files_array[$index]}"
    done
}

CL_COUNT=0

function commitit {
    mario_message "${1}" "Committing the code..."
    git commit -qam "${2} ${1} API"
    mario_done
    let CL_COUNT=$CL_COUNT+1
}

function clangit {
    FORMATTER="${ANDROID_BUILD_TOP}/tools/repohooks/tools/clang-format.py"
    FIX="--fix"
    CLANG_FORMAT="--clang-format ${ANDROID_BUILD_TOP}/prebuilts/clang/host/linux-x86/clang-stable/bin/clang-format"
    GIT_CLANG_FORMAT="--git-clang-format ${ANDROID_BUILD_TOP}/prebuilts/clang/host/linux-x86/clang-stable/bin/git-clang-format"
    COMMIT="--commit"
    STYLE="--style file"
    EXTENSIONS="--extensions c,h,cc,cpp,hpp"
    HASH="$1"
    CMD="${FORMATTER} ${FIX} ${CLANG_FORMAT} ${GIT_CLANG_FORMAT} ${COMMIT} ${HASH} ${STYLE} ${EXTENSIONS}"
    $(${CMD})
}

function rustfmtit {
    echo "rusty rust"
#    FORMATTER="${ANDROID_BUILD_TOP}/prebuilts/rust/linux-x86/stable/rustfmt"
#    CONFIG="'--config-path=rustfmt.toml'"
#    FILE=""
#    CMD="${FORMATTER} ${CONFIG} ${FILE}"
#    $(${CMD})
}


function formatit {
    mario_message "${1}" "Formatting the code..."
    hash="$(git log -n 1 --pretty=oneline | awk '{ print $1 }')"
    clangit $hash
    rustfmtit $hash
    git commit -a --amend --no-edit
    mario_done
}

function controller {
    if [ "$CONTROLLER" == false ]; then
        return
    fi
    mario_message "Controller" "Plumbing the '${1}' API..."
    plumbit "controller" "${1}" "${2}" "${3}" "${CONTROLLER_FILES[@]}"
    mario_done
    commitit "Controller" "${3}"
    formatit "Controller"
}

function controller_shim {
    if [ "$CONTROLLER_SHIM" == false ]; then
        return
    fi
    mario_message "Controller shim" "Plumbing the '${1}' API..."
    plumbit "controller_shim" "${1}" "${2}" "${3}" "${CONTROLLER_SHIM_FILES[@]}"
    mario_done
    commitit "Controller shim" "${3}"
    formatit "Controller shim"
}

function btm_shim {
    if [ "$BTM_SHIM" == false ]; then
        return
    fi
    mario_message "BTM" "Plumbing the '${1}' API..."
    plumbit "btm_shim" "${1}" "${2}" "${3}" "${BTM_SHIM_FILES[@]}"
    mario_done
    commitit "BTM" "${3}"
    formatit "BTM"
}

function bta {
    if [ "$BTA" == false ]; then
        return
    fi
    mario_message "BTA" "Plumbing the '${1}' API..."
    plumbit "bta" "${1}" "${2}" "${3}" "${BTA_FILES[@]}"
    mario_done
    commitit "BTA" "${3}"
    formatit "BTA"
}

function btif {
    if [ "$BTIF" == false ]; then
        return
    fi
    mario_message "BTIF" "Plumbing the '${1}' API..."
    plumbit "btif" "${1}" "${2}" "${3}" "${BTIF_FILES[@]}"
    mario_done
    commitit "BTIF" "${3}"
    formatit "BTIF"
}

function topshim {
    if [ "$TOPSHIM" == false ]; then
        return
    fi
    mario_message "Topshim" "Plumbing the '${1}' API..."
    plumbit "topshim" "${1}" "${2}" "${3}" "${TOPSHIM_FILES[@]}"
    mario_done
    commitit "Topshim" "${3}"
    formatit "Topshim"
}

function main {
    check_environment
    parse_options $@
    if [ "${#POSITIONAL[@]}" -lt 3 ]; then
        echo -e "${RED}Error: Invalid argument count for API Names!${NOCOLOR}"
        help_menu
        exit 1
    fi
    camel_api_name="${POSITIONAL[0]}"
    snake_api_name="${POSITIONAL[1]}"
    api_description="${POSITIONAL[@]:2}"
    show_mario "${camel_api_name} API that ${api_description}!"
    controller "${camel_api_name}" "${snake_api_name}" "${api_description}"
    controller_shim "${camel_api_name}" "${snake_api_name}" "${api_description}"
    btm_shim "${camel_api_name}" "${snake_api_name}" "${api_description}"
    bta "${camel_api_name}" "${snake_api_name}" "${api_description}"
    btif "${camel_api_name}" "${snake_api_name}" "${api_description}"
    topshim "${camel_api_name}" "${snake_api_name}" "${api_description}"
    git rebase -i HEAD~${CL_COUNT} -x 'git commit --amend'
}

main $@
#/usr/local/google/home/optedoblivion/workspace/AOSP/prebuilts/rust/linux-x86/stable/rustfmt '--config-path=rustfmt.toml' system/gd/rust/topshim/facade/src/adapter_service.rs
