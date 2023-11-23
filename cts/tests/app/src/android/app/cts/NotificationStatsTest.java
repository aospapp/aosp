/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.cts;

import android.service.notification.NotificationStats;
import android.test.AndroidTestCase;


/** Test the public NotificationStats api. */
public class NotificationStatsTest extends AndroidTestCase {

    public void testGetDismissalSentiment() {
        NotificationStats notificationStats = new NotificationStats();

        // Starts at unknown. Cycle through all of the options and back.
        assertEquals(NotificationStats.DISMISS_SENTIMENT_UNKNOWN,
                notificationStats.getDismissalSentiment());
        notificationStats.setDismissalSentiment(NotificationStats.DISMISS_SENTIMENT_NEGATIVE);
        assertEquals(NotificationStats.DISMISS_SENTIMENT_NEGATIVE,
                notificationStats.getDismissalSentiment());
        notificationStats.setDismissalSentiment(NotificationStats.DISMISS_SENTIMENT_NEUTRAL);
        assertEquals(NotificationStats.DISMISS_SENTIMENT_NEUTRAL,
                notificationStats.getDismissalSentiment());
        notificationStats.setDismissalSentiment(NotificationStats.DISMISS_SENTIMENT_POSITIVE);
        assertEquals(NotificationStats.DISMISS_SENTIMENT_POSITIVE,
                notificationStats.getDismissalSentiment());
        notificationStats.setDismissalSentiment(NotificationStats.DISMISS_SENTIMENT_UNKNOWN);
        assertEquals(NotificationStats.DISMISS_SENTIMENT_UNKNOWN,
                notificationStats.getDismissalSentiment());
    }

}
