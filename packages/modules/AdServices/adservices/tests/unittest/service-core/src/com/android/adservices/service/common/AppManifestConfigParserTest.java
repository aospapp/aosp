/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.adservices.service.common;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.res.XmlResourceParser;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.adservices.service.exception.XmlParseException;
import com.android.adservices.servicecoretest.R;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParserException;

@SmallTest
public class AppManifestConfigParserTest {
    private Context mContext = ApplicationProvider.getApplicationContext();
    private String mPackageName = mContext.getPackageName();

    @Test
    public void testValidXml() throws Exception {
        XmlResourceParser parser =
                mContext.getPackageManager()
                        .getResourcesForApplication(mPackageName)
                        .getXml(R.xml.ad_services_config);

        AppManifestConfig appManifestConfig = AppManifestConfigParser.getConfig(parser);
        assertThat(appManifestConfig).isNotNull();

        // Verify IncludesSdkLibrary tags.
        assertThat(appManifestConfig.getIncludesSdkLibraryConfig()).isNotNull();
        assertThat(appManifestConfig.getIncludesSdkLibraryConfig().getIncludesSdkLibraries())
                .contains("1234567");

        // Verify Attribution tags.
        assertEquals(appManifestConfig.getAttributionConfig().getAllowAllToAccess(), false);
        assertEquals(
                appManifestConfig.getAttributionConfig().getAllowAdPartnersToAccess().size(), 1);
        assertThat(appManifestConfig.getAttributionConfig().getAllowAdPartnersToAccess())
                .contains("1234");

        // Verify Custom Audience tags.
        assertEquals(appManifestConfig.getCustomAudiencesConfig().getAllowAllToAccess(), false);
        assertEquals(
                appManifestConfig.getCustomAudiencesConfig().getAllowAdPartnersToAccess().size(),
                2);
        assertThat(appManifestConfig.getCustomAudiencesConfig().getAllowAdPartnersToAccess())
                .contains("1234");
        assertThat(appManifestConfig.getCustomAudiencesConfig().getAllowAdPartnersToAccess())
                .contains("4567");

        // Verify Topics tags.
        assertEquals(appManifestConfig.getTopicsConfig().getAllowAllToAccess(), false);
        assertThat(appManifestConfig.getTopicsConfig().getAllowAdPartnersToAccess())
                .contains("1234567");
    }

    @Test
    public void testInvalidXml_missingSdkLibrary() throws Exception {
        XmlResourceParser parser =
                mContext.getPackageManager()
                        .getResourcesForApplication(mPackageName)
                        .getXml(R.xml.ad_services_config_missing_sdk_name);

        Exception e =
                assertThrows(
                        XmlParseException.class, () -> AppManifestConfigParser.getConfig(parser));
        assertEquals("Sdk name not mentioned in <includes-sdk-library>", e.getMessage());
    }

    @Test
    public void testInvalidXml_incorrectValues() throws Exception {
        XmlResourceParser parser =
                mContext.getPackageManager()
                        .getResourcesForApplication(mPackageName)
                        .getXml(R.xml.ad_services_config_incorrect_values);

        Exception e =
                assertThrows(
                        XmlParseException.class, () -> AppManifestConfigParser.getConfig(parser));
        assertEquals(
                "allowAll cannot be set to true when allowAdPartners is also set", e.getMessage());
    }

    @Test
    public void testInvalidXml_repeatTags() throws Exception {
        XmlResourceParser parser =
                mContext.getPackageManager()
                        .getResourcesForApplication(mPackageName)
                        .getXml(R.xml.ad_services_config_repeat_tags);

        Exception e =
                assertThrows(
                        XmlParseException.class, () -> AppManifestConfigParser.getConfig(parser));
        assertEquals("Tag custom-audiences appears more than once", e.getMessage());
    }

    @Test
    public void testInvalidXml_incorrectStartTag() throws Exception {
        XmlResourceParser parser =
                mContext.getPackageManager()
                        .getResourcesForApplication(mPackageName)
                        .getXml(R.xml.ad_services_config_incorrect_start_tag);

        Exception e =
                assertThrows(
                        XmlPullParserException.class,
                        () -> AppManifestConfigParser.getConfig(parser));
        assertEquals("expected START_TAGBinary XML file line #17", e.getMessage());
    }

    @Test
    public void testInvalidXml_incorrectTag() throws Exception {
        XmlResourceParser parser =
                mContext.getPackageManager()
                        .getResourcesForApplication(mPackageName)
                        .getXml(R.xml.ad_services_config_incorrect_tag);

        Exception e =
                assertThrows(
                        XmlParseException.class, () -> AppManifestConfigParser.getConfig(parser));
        assertEquals(
                "Unknown tag: foobar [Tags and attributes are case sensitive]", e.getMessage());
    }

    @Test
    public void testInvalidXml_incorrectAttr() throws Exception {
        XmlResourceParser parser =
                mContext.getPackageManager()
                        .getResourcesForApplication(mPackageName)
                        .getXml(R.xml.ad_services_config_incorrect_attr);

        Exception e =
                assertThrows(
                        XmlParseException.class, () -> AppManifestConfigParser.getConfig(parser));
        assertEquals(
                "Unknown attribute: foobar [Tags and attributes are case sensitive]",
                e.getMessage());
    }
}
