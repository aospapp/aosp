/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.content.pm.cts.shortcutmanager.throttling;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class ReplyUtil {
    private ReplyUtil() {
    }

    public static void runTestAndReply(Context context, String replyAction, Runnable test) {
        try {
            test.run();

            sendReply(context, replyAction, null);
        } catch (Throwable e) {
            String error = "Test failed: " + e.getMessage() + "\n" + Log.getStackTraceString(e);
            sendReply(context, replyAction, error);
        }
    }

    public static void sendReply(Context context, String replyAction,
            String failureMessageOrNullForSuccess) {
        // Create the reply bundle.
        final Bundle ret = new Bundle();
        if (failureMessageOrNullForSuccess == null) {
            ret.putBoolean("success", true);
        } else {
            ret.putString("error", failureMessageOrNullForSuccess);
        }

        // Send reply
        final Intent reply = new Intent(replyAction);
        reply.putExtras(ret);

        context.sendBroadcast(reply);
    }
}
