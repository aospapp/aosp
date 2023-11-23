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

import android.adservices.common.CommonFixture;
import android.net.Uri;

import org.junit.Assert;
import org.junit.Test;

public class AdTechUriValidatorTest {
    private static final String CLASS_NAME = "class";
    private static final String URI_FIELD_NAME = "field";

    private final AdTechUriValidator mValidator =
            new AdTechUriValidator(
                    ValidatorUtil.AD_TECH_ROLE_BUYER,
                    CommonFixture.VALID_BUYER_1.toString(),
                    CLASS_NAME,
                    URI_FIELD_NAME);

    @Test
    public void testValidUri() {
        Assert.assertTrue(
                mValidator
                        .getValidationViolations(
                                Uri.parse("https://" + CommonFixture.VALID_BUYER_1 + "/valid/uri"))
                        .isEmpty());
    }

    @Test
    public void testNullUri() {
        ValidatorTestUtil.assertViolationContainsOnly(
                mValidator.getValidationViolations(null),
                String.format(
                        AdTechUriValidator.URI_SHOULD_BE_SPECIFIED, CLASS_NAME, URI_FIELD_NAME));
    }

    @Test
    public void testNoHostUri() {
        ValidatorTestUtil.assertViolationContainsOnly(
                mValidator.getValidationViolations(Uri.parse("/a/b/c")),
                String.format(
                        AdTechUriValidator.URI_SHOULD_HAVE_PRESENT_HOST,
                        CLASS_NAME,
                        URI_FIELD_NAME));
    }

    @Test
    public void testNotMatchHost() {
        String uriHost = "buy.com";
        ValidatorTestUtil.assertViolationContainsOnly(
                mValidator.getValidationViolations(Uri.parse("https://" + uriHost + "/not/match")),
                String.format(
                        AdTechUriValidator.IDENTIFIER_AND_URI_ARE_INCONSISTENT,
                        ValidatorUtil.AD_TECH_ROLE_BUYER,
                        CommonFixture.VALID_BUYER_1,
                        ValidatorUtil.AD_TECH_ROLE_BUYER,
                        URI_FIELD_NAME,
                        uriHost));
    }

    @Test
    public void testNotHttpsHost() {
        ValidatorTestUtil.assertViolationContainsOnly(
                mValidator.getValidationViolations(
                        Uri.parse("http://" + CommonFixture.VALID_BUYER_1 + "/not/https/")),
                String.format(AdTechUriValidator.URI_SHOULD_USE_HTTPS, CLASS_NAME, URI_FIELD_NAME));
    }
}
