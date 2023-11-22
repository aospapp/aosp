/*
 * Copyright (c) 2015, Motorola Mobility LLC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     - Neither the name of Motorola Mobility nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL MOTOROLA MOBILITY LLC BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */

package com.android.service.ims.presence;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Groups;

import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import com.android.ims.internal.EABContract;
import com.android.ims.internal.Logger;

public class ContactDbUtil {
    private static Logger logger = Logger.getLogger("ContactDbUtil");

    public static void addVideoCallingContactGroup(Context context, Account vCallingAccount) {
        logger.info("addVideoCallingContactGroup");
        ContentResolver contentResolver = context.getContentResolver();
        if (vCallingAccount == null) {
            logger.error("vCallingAccount == null");
            return;
        }
        long videoCallingGroupId = 0;
        final Cursor cursor = contentResolver.query(Groups.CONTENT_URI,
                new String[] { Groups._ID },
                Groups.ACCOUNT_NAME + "=? AND " + Groups.ACCOUNT_TYPE + "=? AND " +
                Groups.TITLE + "=?",
                new String[] { AccountUtil.ACCOUNT_NAME, AccountUtil.ACCOUNT_TYPE,
                context.getString(R.string.video_calling_contact_group) }, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    videoCallingGroupId = cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }
        if (videoCallingGroupId == 0) {
            logger.debug("addVideoCallingContactGroup, creating group");

            // Video Calling group doesn't exist yet, so create it
            final ContentValues contentValues = new ContentValues();
            contentValues.put(Groups.ACCOUNT_NAME, AccountUtil.ACCOUNT_NAME);
            contentValues.put(Groups.ACCOUNT_TYPE, AccountUtil.ACCOUNT_TYPE);
            contentValues.put(Groups.TITLE, context.getString(R.string.video_calling_contact_group));
            contentValues.put(Groups.GROUP_IS_READ_ONLY, 1);
            contentValues.put(Groups.GROUP_VISIBLE, 1);
            contentValues.put(Groups.SYSTEM_ID, "com.android.vt.eab");

            final Uri newGroupUri = contentResolver.insert(Groups.CONTENT_URI, contentValues);
            if (null != newGroupUri) {
                videoCallingGroupId = ContentUris.parseId(newGroupUri);
            } else {
                logger.error("newGroupUri is null.");
            }
        } else {
            logger.debug("addVideoCallingContactGroup, Video Calling Group, already exists!!!");

        }

        logger.debug("videoCallingGroupId: " + videoCallingGroupId);
        SharedPrefUtil.setVideoCallingGroupId(context, videoCallingGroupId);
    }

    public static void removeVideoCallingContactGroup(Context context) {
        logger.debug("removeVideoCallingContactGroup");
        long storedVideoCallingGroupId = SharedPrefUtil.getVideoCallingGroupId(context);
        long videoCallingGroupId = 0;
        ContentResolver contentResolver = context.getContentResolver();
        final Cursor cursor = contentResolver.query(Groups.CONTENT_URI,
                new String[] { Groups._ID },
                Groups.ACCOUNT_NAME + "=? AND " + Groups.ACCOUNT_TYPE + "=? AND " +
                Groups.TITLE + "=?",
                new String[] { AccountUtil.ACCOUNT_NAME, AccountUtil.ACCOUNT_TYPE,
                context.getString(R.string.video_calling_contact_group) }, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    videoCallingGroupId = cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }
        logger.debug("videoCallingGroupId : " + videoCallingGroupId);
        logger.debug("storedVideoCallingGroupId : " + storedVideoCallingGroupId);
        if (videoCallingGroupId == 0) {
            logger.debug("videoCallingGroupId : " + videoCallingGroupId +
                    " not present. Do nothing. ");
        } else {
            if (storedVideoCallingGroupId == videoCallingGroupId) {
                String deleteWhereClause = Groups._ID + "='" + videoCallingGroupId + "'";
                contentResolver.delete(Groups.CONTENT_URI, deleteWhereClause, null);
                logger.debug("Removing Video Calling Group.");
            }
            SharedPrefUtil.setVideoCallingGroupId(context, 0);
        }
        logger.debug("videoCallingGroupId: " + videoCallingGroupId);
    }

    public static int resetVtCapability(ContentResolver resolver) {
        if(resolver == null) {
            logger.error("resetVtCapability, resolver = null");
            return 0;
        }

        String selection = ContactsContract.Data.MIMETYPE + " = '" + Phone.CONTENT_ITEM_TYPE + "'";

        ContentValues values = new ContentValues();
        values.put(ContactsContract.Data.CARRIER_PRESENCE, 0); // reset all.
        int count = resolver.update(ContactsContract.Data.CONTENT_URI, values, selection, null);
        logger.debug("resetVtCapability count=" + count);
        return count;
    }

    public static int updateVtCapability(ContentResolver resolver, String number, boolean enable) {
        String[] projection = new String[] {EABContract.EABColumns.DATA_ID};

        int updatedCount = 0;
        Cursor cursor = null;
        try {
            cursor = resolver.query(Contacts.Impl.CONTENT_URI,
                   projection, "PHONE_NUMBERS_EQUAL(contact_number, ?, 0)",
                   new String[] {number}, null);
            if(null != cursor) {
                int count = cursor.getCount();
                logger.debug("updateVtCapability to Contact DB, count=" + count);
                if(count <= 0) {
                    logger.error("updateVtCapability, no number to be updated");
                    cursor.close();
                    cursor = null;
                    return updatedCount;
                }

                while(cursor.moveToNext()) {
                    long dataId = cursor.getLong(cursor.getColumnIndex(
                            EABContract.EABColumns.DATA_ID));
                    // update one by one to avoid loosing matching error.
                    updatedCount += updateVtCapability(resolver, dataId, enable);
                }
            }
        } catch (Exception e) {
            logger.error("updateVtCapability exception", e);
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        logger.debug("updateVtCapability updatedCount=" + updatedCount);
        return updatedCount;
    }

    public static int updateVtCapability(ContentResolver resolver, long dataId, boolean enable) {
        if(resolver == null) {
            logger.error("resetVtCapability, resolver = null");
            return 0;
        }

        String selection = ContactsContract.Data.MIMETYPE + " = '" + Phone.CONTENT_ITEM_TYPE
                + "' and " + ContactsContract.Data._ID + "= '" + dataId + "'";

        int oldValue = 0;
        final Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[] { ContactsContract.Data._ID, ContactsContract.Data.CARRIER_PRESENCE },
                selection, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    oldValue = cursor.getInt(1);
                }
            } finally {
                cursor.close();
            }
        }


        ContentValues values = new ContentValues();
        values.put(ContactsContract.Data.CARRIER_PRESENCE,
                enable?(oldValue | ContactsContract.Data.CARRIER_PRESENCE_VT_CAPABLE):
                (oldValue & ~ContactsContract.Data.CARRIER_PRESENCE_VT_CAPABLE));
        int count = resolver.update(ContactsContract.Data.CONTENT_URI, values, selection, null);
        logger.debug("resetVtCapability count=" + count);
        return count;
    }
}
