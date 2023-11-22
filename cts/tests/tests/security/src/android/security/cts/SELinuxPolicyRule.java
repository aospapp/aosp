/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.security.cts;

import android.util.Xml;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;


/**
 * A class for generating representations of SELinux avc rules parsed from an xml file.
 */
public class SELinuxPolicyRule {
    public final List<String> source_types;
    public final List<String> target_types;
    public final Multimap<String, String> obj_classes;
    public final String name;
    public final String type;

    private SELinuxPolicyRule(List<String> source_types, List<String> target_types,
            Multimap<String, String> obj_classes, String name, String type) {
        this.source_types = source_types;
        this.target_types = target_types;
        this.obj_classes = obj_classes;
        this.name = name;
        this.type = type;
    }

    public static SELinuxPolicyRule readRule(XmlPullParser xpp) throws IOException, XmlPullParserException {
        List<String> source_types = new ArrayList<String>();
        List<String> target_types = new ArrayList<String>();
        Multimap<String, String> obj_classes = HashMultimap.create();
        xpp.require(XmlPullParser.START_TAG, null, "avc_rule");
        String ruleName = xpp.getAttributeValue(null, "name");
        String ruleType = xpp.getAttributeValue(null, "type");
        while (xpp.next() != XmlPullParser.END_TAG) {
            if (xpp.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = xpp.getName();
            if (name.equals("type")) {
                if (xpp.getAttributeValue(null, "type").equals("source")) {
                    source_types.add(readType(xpp));
                } else if (xpp.getAttributeValue(null, "type").equals("target")) {
                    target_types.add(readType(xpp));
                } else {
                    skip(xpp);
                }
            } else if (name.equals("obj_class")) {
                String obj_name = xpp.getAttributeValue(null, "name");
                List<String> perms = readObjClass(xpp);
                obj_classes.putAll(obj_name, perms);
            } else {
                skip(xpp);
            }
        }
        return new SELinuxPolicyRule(source_types, target_types, obj_classes, ruleName, ruleType);
    }

    public static List<SELinuxPolicyRule> readRulesFile(InputStream in) throws IOException, XmlPullParserException {
        List<SELinuxPolicyRule> rules = new ArrayList<SELinuxPolicyRule>();
        XmlPullParser xpp = Xml.newPullParser();
        xpp.setInput(in, null);
        xpp.nextTag();
        xpp.require(XmlPullParser.START_TAG, null, "SELinux_AVC_Rules");

        /* read rules */
        while (xpp.next()  != XmlPullParser.END_TAG) {
            if (xpp.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = xpp.getName();
            if (name.equals("avc_rule")) {
                SELinuxPolicyRule r = readRule(xpp);
                rules.add(r);
            } else {
                skip(xpp);
            }
        }
        return rules;
    }

    private static List<String> readObjClass(XmlPullParser xpp) throws IOException, XmlPullParserException {
        List<String> perms = new ArrayList<String>();
        xpp.require(XmlPullParser.START_TAG, null, "obj_class");
        while (xpp.next() != XmlPullParser.END_TAG) {
        if (xpp.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = xpp.getName();
            if (name.equals("permission")) {
                perms.add(readPermission(xpp));
            } else {
                skip(xpp);
            }
        }
        return perms;
    }

    private static String readType(XmlPullParser xpp) throws IOException, XmlPullParserException {
        xpp.require(XmlPullParser.START_TAG, null, "type");
        String type = readText(xpp);
        xpp.require(XmlPullParser.END_TAG, null, "type");
        return type;
    }

    private static String readPermission(XmlPullParser xpp) throws IOException, XmlPullParserException {
        xpp.require(XmlPullParser.START_TAG, null, "permission");
        String permission = readText(xpp);
        xpp.require(XmlPullParser.END_TAG, null, "permission");
        return permission;
    }

    private static String readText(XmlPullParser xpp) throws IOException, XmlPullParserException {
        String result = "";
        if (xpp.next() == XmlPullParser.TEXT) {
            result = xpp.getText();
            xpp.nextTag();
        }
        return result;
    }

    public static void skip(XmlPullParser xpp) throws XmlPullParserException, IOException {
        if (xpp.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (xpp.next()) {
            case XmlPullParser.END_TAG:
                depth--;
                break;
            case XmlPullParser.START_TAG:
                depth++;
                break;
            }
        }
    }
}
