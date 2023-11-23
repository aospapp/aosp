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

package com.android.adservices.service.measurement.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;

import java.util.Optional;

public class WebTest {

    private static final String COM_PUBLIC_SUFFIX = "com";
    private static final String BLOGSPOT_COM_PUBLIC_SUFFIX = "blogspot.com";
    private static final String TOP_PRIVATE_DOMAIN = "private-domain";
    private static final String SUBDOMAIN = "subdomain";
    private static final String HTTPS_SCHEME = "https";
    private static final String HTTP_SCHEME = "http";
    private static final String PORT = "443";

    private static final Uri HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX =
            Uri.parse(
                    String.format(
                            "%s://%s.%s", HTTPS_SCHEME, TOP_PRIVATE_DOMAIN, COM_PUBLIC_SUFFIX));
    private static final Uri HTTPS_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX =
            Uri.parse(
                    String.format(
                            "%s://%s.%s",
                            HTTPS_SCHEME, TOP_PRIVATE_DOMAIN, BLOGSPOT_COM_PUBLIC_SUFFIX));
    private static final Uri HTTPS_SUBDOMAIN_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX =
            Uri.parse(
                    String.format(
                            "%s://%s.%s.%s",
                            HTTPS_SCHEME, SUBDOMAIN, TOP_PRIVATE_DOMAIN, COM_PUBLIC_SUFFIX));
    private static final Uri HTTPS_SUBDOMAIN_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX =
            Uri.parse(
                    String.format(
                            "%s://%s.%s.%s",
                            HTTPS_SCHEME,
                            SUBDOMAIN,
                            TOP_PRIVATE_DOMAIN,
                            BLOGSPOT_COM_PUBLIC_SUFFIX));
    private static final Uri HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX_PORT =
            Uri.parse(
                    String.format(
                            "%s://%s.%s:%s",
                            HTTPS_SCHEME, TOP_PRIVATE_DOMAIN, COM_PUBLIC_SUFFIX, PORT));

    private static final Uri HTTP_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX =
            Uri.parse(
                    String.format(
                            "%s://%s.%s", HTTP_SCHEME, TOP_PRIVATE_DOMAIN, COM_PUBLIC_SUFFIX));

    @Test
    public void testTopPrivateDomainAndScheme_ValidPublicDomainAndHttpsScheme() {
        Optional<Uri> output =
                Web.topPrivateDomainAndScheme(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testOriginAndScheme_ValidPublicDomainAndHttpsScheme() {
        Optional<Uri> output = Web.originAndScheme(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testTopPrivateDomainAndScheme_ValidPrivateDomainAndHttpsScheme() {
        Optional<Uri> output =
                Web.topPrivateDomainAndScheme(HTTPS_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testOriginAndScheme_ValidPrivateDomainAndHttpsScheme() {
        Optional<Uri> output = Web.originAndScheme(HTTPS_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testTopPrivateDomainAndScheme_ValidPublicDomainAndHttpsScheme_extraSubdomain() {
        Optional<Uri> output =
                Web.topPrivateDomainAndScheme(HTTPS_SUBDOMAIN_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testOriginAndScheme_ValidPublicDomainAndHttpsScheme_extraSubdomain() {
        Optional<Uri> output =
                Web.originAndScheme(HTTPS_SUBDOMAIN_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_SUBDOMAIN_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testTopPrivateDomainAndScheme_ValidPrivateDomainAndHttpsScheme_extraSubdomain() {
        Optional<Uri> output =
                Web.topPrivateDomainAndScheme(
                        HTTPS_SUBDOMAIN_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testOriginAndScheme_ValidPrivateDomainAndHttpsScheme_extraSubdomain() {
        Optional<Uri> output =
                Web.originAndScheme(HTTPS_SUBDOMAIN_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_SUBDOMAIN_PRIVATE_DOMAIN_BLOGSPOT_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testTopPrivateDomainAndScheme_ValidPublicDomainAndHttpScheme() {
        Optional<Uri> output = Web.topPrivateDomainAndScheme(HTTP_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTP_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testOriginAndScheme_ValidPublicDomainAndHttpScheme() {
        Optional<Uri> output = Web.originAndScheme(HTTP_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX);
        assertTrue(output.isPresent());
        assertEquals(HTTP_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testTopPrivateDomainAndScheme_ValidPublicDomainAndPortAndHttpsScheme() {
        Optional<Uri> output =
                Web.topPrivateDomainAndScheme(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX_PORT);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX, output.get());
    }

    @Test
    public void testOriginAndScheme_ValidPublicDomainAndPortAndHttpsScheme() {
        Optional<Uri> output = Web.originAndScheme(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX_PORT);
        assertTrue(output.isPresent());
        assertEquals(HTTPS_PRIVATE_DOMAIN_COM_PUBLIC_SUFFIX_PORT, output.get());
    }
}
