package android.telephonyprovider.cts;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.Telephony;

import androidx.test.filters.SmallTest;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

@SmallTest
public class ThreadsTest {
    private Context mContext;
    private ContentResolver mContentResolver;

    @BeforeClass
    public static void ensureDefaultSmsApp() {
        DefaultSmsAppHelper.ensureDefaultSmsApp();
    }

    @Before
    public void setupTestEnvironment() {
        cleanup();
        mContext = getInstrumentation().getContext();
        mContentResolver = mContext.getContentResolver();
    }

    @AfterClass
    public static void cleanup() {
        ContentResolver contentResolver = getInstrumentation().getContext().getContentResolver();
        contentResolver.delete(Telephony.Threads.CONTENT_URI, null, null);
    }

    @Test
    public void testThreadDeletion_doNotReuseThreadIdsFromEmptyThreads() {
        String destination1 = "+19998880001";
        String destination2 = "+19998880002";

        long threadId1 = Telephony.Threads.getOrCreateThreadId(mContext, destination1);

        Uri inboxUri = saveToTelephony(threadId1, destination1, "testThreadDeletion body");

        // The URI returned by the insert operation points to the message ID in the inbox. Though
        // this is a valid ID for queries, the SMS provider does not handle it for delete
        // operations. This takes the ID off the end of the URI and creates a URI pointing at that
        // row from the root of the SMS provider.
        Uri rootUri =
                Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, inboxUri.getLastPathSegment());

        int deletedCount = mContentResolver.delete(rootUri, null, null);

        assertThat(deletedCount).isEqualTo(1);

        long threadId2 = Telephony.Threads.getOrCreateThreadId(mContext, destination2);

        assertThat(threadId2).isGreaterThan(threadId1);
    }

    private Uri saveToTelephony(long threadId, String address, String body) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Telephony.Sms.THREAD_ID, threadId);
        contentValues.put(Telephony.Sms.ADDRESS, address);
        contentValues.put(Telephony.Sms.BODY, body);

        return mContext.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, contentValues);
    }
}

