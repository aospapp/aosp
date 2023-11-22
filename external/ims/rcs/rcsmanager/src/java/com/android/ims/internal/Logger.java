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

package com.android.ims.internal;

import java.lang.String;
import android.util.Log;

import android.os.Build;
import android.text.TextUtils;

/**
 * Logger
 *
 * @hide
 */
public class Logger {
    /**
     * DEBUG level
     */
    public static int DEBUG_LEVEL = 0;

    /**
     * INFO level
     */
    public static int INFO_LEVEL = 1;

    /**
     * WARN level
     */
    public static int WARN_LEVEL = 2;

    /**
     * ERROR level
     */
    public static int ERROR_LEVEL = 3;

    /**
     * FATAL level
     */
    public static int FATAL_LEVEL = 4;

    /**
     * RCS test mode flag
     */
    public static boolean rcsTestMode = false;

    /**
     * Trace level
     */
    public static int traceLevel = DEBUG_LEVEL;

    /**
     * Log tag name
     */
    private static String tagname = "rcs";

    /**
     * Classname
     */
    private String classname;

    /**
     * Constructor
     *
     * @param classname Classname
     */
    private Logger(String tagName, String classname) {
        if(!TextUtils.isEmpty(tagName)) {
            this.tagname = tagName;
        }

        int index = classname.lastIndexOf('.');
        if (index != -1) {
            this.classname = classname.substring(index+1);
        } else {
            this.classname = classname;
        }

        if(Build.IS_DEBUGGABLE || rcsTestMode){
            traceLevel = DEBUG_LEVEL;
        }else{
            traceLevel = ERROR_LEVEL;
        }
    }

    public static void setRcsTestMode(boolean test) {
        rcsTestMode = test;

        if (Build.IS_DEBUGGABLE || rcsTestMode) {
            traceLevel = DEBUG_LEVEL;
        } else {
            traceLevel = ERROR_LEVEL;
        }
    }

    /**
     * Is logger activated. Reserved for future debug tool to turn on/off the log only.
     *
     * @return boolean
     */
    public boolean isActivated() {
        return true;
    }

    /**
     * Debug trace
     *
     * @param trace Trace
     */
    public void debug(String trace) {
        if (isActivated() && (traceLevel <= DEBUG_LEVEL)) {
            Log.d(tagname, "[" + classname +"] " + trace);
        }
    }

    /**
     * Debug trace
     *
     * @param trace Trace
     * @param e the exception which need to be printed.
     */
    public void debug(String trace, Throwable e) {
        if (isActivated() && (traceLevel <= DEBUG_LEVEL)) {
            Log.d(tagname, "[" + classname +"] " + trace, e);
        }
    }

    /**
     * Info trace
     *
     * @param trace Trace
     */
    public void info(String trace) {
        if (isActivated() && (traceLevel <= INFO_LEVEL)) {
            Log.i(tagname, "[" + classname +"] " + trace);
        }
    }

    /**
     * Warning trace
     *
     * @param trace Trace
     */
    public void warn(String trace) {
        if (isActivated() && (traceLevel <= WARN_LEVEL)) {
            Log.w(tagname, "[" + classname +"] " + trace);
        }
    }

    /**
     * Error trace
     *
     * @param trace Trace
     */
    public void error(String trace) {
        if (isActivated() && (traceLevel <= ERROR_LEVEL)) {
            Log.e(tagname, "[" + classname +"] " + trace);
        }
    }

    /**
     * Error trace
     *
     * @param trace Trace
     * @param e Exception
     */
    public void error(String trace, Throwable e) {
        if (isActivated() && (traceLevel <= ERROR_LEVEL)) {
            Log.e(tagname, "[" + classname +"] " + trace, e);
        }
    }

    /*
     * Print the debug log and don't consider the traceLevel
     *
     * @param trace Trace
     * @param e Exception
     */
    public void print(String trace) {
        Log.i(tagname, "[" + classname +"] " + trace);
    }

    /**
     * Print the debug log and don't consider the traceLevel
     *
     * @param trace Trace
     * @param e Exception
     */
    public void print(String trace, Throwable e) {
        Log.i(tagname, "[" + classname +"] " + trace, e);
    }

    /**
     * Create a static instance
     *
     * @param classname Classname
     * @return Instance
     */
    public static synchronized Logger getLogger(String tagName, String classname) {
        return new Logger(tagName, classname);
    }

    /**
     * Create a static instance
     *
     * @param classname Classname
     * @return Instance
     */
    public static synchronized Logger getLogger(String classname) {
        return new Logger(tagname, classname);
    }
}

