#!/system/bin/sh

# BetterBug required fields
am='am start -a com.google.android.apps.betterbug.intent.FILE_BUG_DEEPLINK --ez EXTRA_DEEPLINK true '
issueTitle=' --es EXTRA_ISSUE_TITLE '
additionalComment=' --es EXTRA_ADDITIONAL_COMMENT '
componentId=' --el EXTRA_COMPONENT_ID '
requireBugReport=' --ez EXTRA_REQUIRE_BUGREPORT '
bugAssign=' --es EXTRA_BUG_ASSIGNEE '
ccGroup=' --es EXTRA_CC '

# BetterBug title
kAuthTitle="UDFPS Fingerprint authentication has high failed rate"
kLockoutTitle="UDFPS Fingerprint has too many lockout counts"
kLatencyTitle="UDFPS Fingerprint took long to unlock device"

# BetterBug context comment
kAuthComment="This bug is auto created by fingerprint HAL to track fingerprint authentication"
kLockoutComment="This bug is auto created by fingerprint HAL to track fingerprint lockout"
kLatencyComment="This bug is auto created by fingerprint HAL to track fingerprint latency"

# BetterBug assign & CC owner
kBugAssign='udfps_data_study@google.com'
kCcGroup='eddielan@google.com'
kComponentId='817555'

# Command to send intent to BetterBug
commonCommand="$componentId ${kComponentId//\ /\\ }
               $requireBugReport true
               $bugAssign ${kBugAssign//\ /\\ }
               $ccGroup ${kCcGroup//\ /\\ }"
authCommand="$am $issueTitle ${kAuthTitle//\ /\\ }
             $additionalComment ${kAuthComment//\ /\\ }"
lockoutCommand="$am $issueTitle ${kLockoutTitle//\ /\\ }
                $additionalComment ${kLockoutComment//\ /\\ }"
latencyCommand="$am $issueTitle ${kLatencyTitle//\ /\\ }
                $additionalComment ${kLatencyComment//\ /\\ }"

# Type of bug being triggered
# 1. Latency
# 2. Lockout
# 3. Finerprint authentication(FRR)
bug_type="$1"

send=1
if [ "$bug_type" == "latency" ]; then
  intentCommand="$latencyCommand $commonCommand"
elif [ "$bug_type" == "lockout" ]; then
  intentCommand="$lockoutCommand $commonCommand"
elif [ "$bug_type" == "auth" ]; then
  intentCommand="$authCommand $commonCommand"
else
  send=0
  echo "Unknown bug_type $bug_type"
fi

if [ $send -eq 1 ]
then
  eval $intentCommand
fi

# Exit
exit 0
