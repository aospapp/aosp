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

package com.android.server.appsearch.contactsindexer;

import static android.app.appsearch.testutil.AppSearchTestUtils.checkIsBatchResultSuccess;
import static android.app.appsearch.testutil.AppSearchTestUtils.convertSearchResultsToDocuments;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.SearchResultsShim;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.testutil.AppSearchSessionShimImpl;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// Since AppSearchHelper mainly just calls AppSearch's api to index/remove files, we shouldn't
// worry too much about it since AppSearch has good test coverage. Here just add some simple checks.
public class AppSearchHelperTest {
    private final Executor mSingleThreadedExecutor = Executors.newSingleThreadExecutor();

    private Context mContext;
    private AppSearchHelper mAppSearchHelper;
    private ContactsUpdateStats mUpdateStats;

    private AppSearchSessionShim mDb;
    private ContactsIndexerConfig mConfigForTest = new TestContactsIndexerConfig();

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mUpdateStats = new ContactsUpdateStats();

        // b/258968096
        // Internally AppSearchHelper.createAppSearchHelper will set Person and
        // ContactPoint schema for AppSearch.
        //
        // Since everything is async, the fact we didn't wait until it finish is making
        // testCreateAppSearchHelper_incompatibleSchemaChange flaky:
        //   - In that test, it uses an AppSearchSessionShim to set
        //   CONTACT_POINT_SCHEMA_WITH_LABEL_REPEATED
        //   - Then, the test will create another AppSearchHelper
        //   - For this local AppSearchHelper in the test, we are expecting an incompatible
        //   schema change.
        //   - But if mAppSearchHelper doesn't finish setting its schema, and
        //   CONTACT_POINT_SCHEMA_WITH_LABEL_REPEATED is set first, mAppSearchHelper will get an
        //   incompatible schema change, and the one created later for the test won't since it
        //   will set the same schemas as mAppSearchHelper.
        //
        // To fix the flakiness, we need to wait until mAppSearchHelper finishes initialization.
        // We choose to do it in the setup to make sure it won't create such flakiness in the
        // future tests.
        //
        mAppSearchHelper = AppSearchHelper.createAppSearchHelper(mContext, mSingleThreadedExecutor,
                mConfigForTest);
        // TODO(b/237115318) we need to revisit this once the contact indexer is refactored.
        // getSession here will call get() on the future for AppSearchSession to make sure it has
        // been initialized.
        AppSearchSession unused = mAppSearchHelper.getSession();
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.DATABASE_NAME).build();
        mDb = AppSearchSessionShimImpl.createSearchSessionAsync(
                searchContext).get();
    }

    @After
    public void tearDown() throws Exception {
        // Wipe the data in AppSearchHelper.DATABASE_NAME.
        SetSchemaRequest setSchemaRequest = new SetSchemaRequest.Builder()
                .setForceOverride(true).build();
        mDb.setSchemaAsync(setSchemaRequest).get();
    }

    @Test
    public void testAppSearchHelper_permissionIsSetCorrectlyForPerson() throws Exception {
        // TODO(b/203605504) We can create AppSearchHelper in the test itself so make things more
        //  clear.
        AppSearchSession session = mAppSearchHelper.getSession();
        CompletableFuture<AppSearchResult<GetSchemaResponse>> responseFuture =
                new CompletableFuture<>();

        // TODO(b/203605504) Considering using AppSearchShim, which is our test utility that
        //  glues AppSearchSession to the Future API
        session.getSchema(mSingleThreadedExecutor, responseFuture::complete);

        AppSearchResult<GetSchemaResponse> result = responseFuture.get();
        assertThat(result.isSuccess()).isTrue();
        GetSchemaResponse response = result.getResultValue();
        assertThat(response.getRequiredPermissionsForSchemaTypeVisibility()).hasSize(2);
        assertThat(response.getRequiredPermissionsForSchemaTypeVisibility()).containsKey(
                ContactPoint.SCHEMA_TYPE);
        assertThat(response.getRequiredPermissionsForSchemaTypeVisibility()).containsEntry(
                Person.SCHEMA_TYPE,
                ImmutableSet.of(ImmutableSet.of(SetSchemaRequest.READ_CONTACTS)));
    }

    @Test
    public void testIndexContacts() throws Exception {
        mAppSearchHelper.indexContactsAsync(generatePersonData(50), mUpdateStats).get();

        List<String> appsearchIds = mAppSearchHelper.getAllContactIdsAsync().get();
        assertThat(appsearchIds.size()).isEqualTo(50);
    }

    @Test
    public void testIndexContacts_clearAfterIndex() throws Exception {
        List<Person> contacts = generatePersonData(50);

        CompletableFuture<Void> indexContactsFuture = mAppSearchHelper.indexContactsAsync(contacts,
                mUpdateStats);
        contacts.clear();
        indexContactsFuture.get();

        List<String> appsearchIds = mAppSearchHelper.getAllContactIdsAsync().get();
        assertThat(appsearchIds.size()).isEqualTo(50);
    }

    @Test
    public void testAppSearchHelper_removeContacts() throws Exception {
        mAppSearchHelper.indexContactsAsync(generatePersonData(50), mUpdateStats).get();
        List<String> indexedIds = mAppSearchHelper.getAllContactIdsAsync().get();

        List<String> deletedIds = new ArrayList<>();
        for (int i = 0; i < 50; i += 5) {
            deletedIds.add(String.valueOf(i));
        }
        mAppSearchHelper.removeContactsByIdAsync(deletedIds, mUpdateStats).get();

        assertThat(indexedIds.size()).isEqualTo(50);
        List<String> appsearchIds = mAppSearchHelper.getAllContactIdsAsync().get();
        assertThat(appsearchIds).containsNoneIn(deletedIds);
    }

    @Test
    public void testCreateAppSearchHelper_compatibleSchemaChange() throws Exception {
        AppSearchHelper appSearchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor, mConfigForTest);

        assertThat(appSearchHelper).isNotNull();
        assertThat(appSearchHelper.isDataLikelyWipedDuringInitAsync().get()).isFalse();
    }

    @Test
    public void testCreateAppSearchHelper_compatibleSchemaChange2() throws Exception {
        SetSchemaRequest setSchemaRequest = new SetSchemaRequest.Builder()
                .addSchemas(TestUtils.CONTACT_POINT_SCHEMA_WITH_APP_IDS_OPTIONAL)
                .setForceOverride(true).build();
        mDb.setSchemaAsync(setSchemaRequest).get();

        // APP_IDS changed from optional to repeated, which is a compatible change.
        AppSearchHelper appSearchHelper =
                AppSearchHelper.createAppSearchHelper(mContext, mSingleThreadedExecutor,
                        mConfigForTest);

        assertThat(appSearchHelper).isNotNull();
        assertThat(appSearchHelper.isDataLikelyWipedDuringInitAsync().get()).isFalse();
    }

    @Test
    public void testCreateAppSearchHelper_incompatibleSchemaChange() throws Exception {
        SetSchemaRequest setSchemaRequest = new SetSchemaRequest.Builder()
                .addSchemas(TestUtils.CONTACT_POINT_SCHEMA_WITH_LABEL_REPEATED)
                .setForceOverride(true).build();
        mDb.setSchemaAsync(setSchemaRequest).get();

        // LABEL changed from repeated to optional, which is an incompatible change.
        AppSearchHelper appSearchHelper =
                AppSearchHelper.createAppSearchHelper(mContext, mSingleThreadedExecutor,
                        mConfigForTest);

        assertThat(appSearchHelper).isNotNull();
        assertThat(appSearchHelper.isDataLikelyWipedDuringInitAsync().get()).isTrue();
    }

    @Test
    public void testGetAllContactIds() throws Exception {
        indexContactsInBatchesAsync(generatePersonData(200)).get();

        List<String> appSearchContactIds = mAppSearchHelper.getAllContactIdsAsync().get();

        assertThat(appSearchContactIds.size()).isEqualTo(200);
    }

    private CompletableFuture<Void> indexContactsInBatchesAsync(List<Person> contacts) {
        CompletableFuture<Void> indexContactsInBatchesFuture =
                CompletableFuture.completedFuture(null);
        int startIndex = 0;
        while (startIndex < contacts.size()) {
            int batchEndIndex = Math.min(
                    startIndex + ContactsIndexerImpl.NUM_UPDATED_CONTACTS_PER_BATCH_FOR_APPSEARCH,
                    contacts.size());
            List<Person> batchedContacts = contacts.subList(startIndex, batchEndIndex);
            indexContactsInBatchesFuture = indexContactsInBatchesFuture
                    .thenCompose(x -> mAppSearchHelper.indexContactsAsync(batchedContacts,
                            mUpdateStats));
            startIndex = batchEndIndex;
        }
        return indexContactsInBatchesFuture;
    }

    @Test
    public void testPersonSchema_indexFirstMiddleAndLastNames() throws Exception {
        // Override test config to index first, middle and last names.
        ContactsIndexerConfig config = new TestContactsIndexerConfig() {
            @Override
            public boolean shouldIndexFirstMiddleAndLastNames() {
                return true;
            }
        };
        SetSchemaRequest setSchemaRequest = new SetSchemaRequest.Builder()
                .addSchemas(ContactPoint.SCHEMA, Person.getSchema(config))
                .setForceOverride(true).build();
        mDb.setSchemaAsync(setSchemaRequest).get();
        // Index document
        GenericDocument doc1 =
                new GenericDocument.Builder<>("namespace", "id1", Person.SCHEMA_TYPE)
                        .setPropertyString(Person.PERSON_PROPERTY_NAME, "新中野")
                        .setPropertyString(Person.PERSON_PROPERTY_FAMILY_NAME, "新")
                        .setPropertyString(Person.PERSON_PROPERTY_GIVEN_NAME, "野")
                        .setPropertyString(Person.PERSON_PROPERTY_MIDDLE_NAME, "中")
                        .build();
        checkIsBatchResultSuccess(
                mDb.putAsync(
                        new PutDocumentsRequest.Builder().addGenericDocuments(doc1).build()));

        SearchSpec spec = new SearchSpec.Builder()
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .build();

        // Searching by full name returns document
        SearchResultsShim searchResults = mDb.search("新中野", spec);
        List<GenericDocument> documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents).containsExactly(doc1);

        // Searching by last name returns document
        searchResults = mDb.search("新", spec);
        documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents).containsExactly(doc1);

        // Searching by middle name returns document
        searchResults = mDb.search("中", spec);
        documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents).containsExactly(doc1);

        // Searching by first name returns document
        searchResults = mDb.search("野", spec);
        documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents).containsExactly(doc1);
    }

    // Index document only using the full name. This helps understand why first, middle
    // and last names need to be indexed in order to be able to search some Chinese names
    // efficiently. This can also potentially alert us of any future ICU tokenization changes.
    // For e.g., if "新中野" is segmented to "新","中" and "野" in the future (as compared to only
    // a single token "新中野" currently), the third and fourth asserts in ths test will start
    // failing. This documents current behavior, but doesn't endorse it. Ideally, all of the below
    // queries would be considered matches even when only the full name is indexed.
    @Test
    public void testPersonSchema_indexFullNameOnly() throws Exception {
        SetSchemaRequest setSchemaRequest = new SetSchemaRequest.Builder()
                .addSchemas(ContactPoint.SCHEMA, Person.getSchema(mConfigForTest))
                .setForceOverride(true).build();
        mDb.setSchemaAsync(setSchemaRequest).get();
        GenericDocument doc1 =
                new GenericDocument.Builder<>("namespace", "id1", Person.SCHEMA_TYPE)
                        .setPropertyString(Person.PERSON_PROPERTY_NAME, "新中野")
                        .setPropertyString(Person.PERSON_PROPERTY_FAMILY_NAME, "新")
                        .setPropertyString(Person.PERSON_PROPERTY_GIVEN_NAME, "野")
                        .setPropertyString(Person.PERSON_PROPERTY_MIDDLE_NAME, "中")
                        .build();
        checkIsBatchResultSuccess(
                mDb.putAsync(
                        new PutDocumentsRequest.Builder().addGenericDocuments(doc1).build()));

        // Searching by full name returns the document
        SearchResultsShim searchResults =
                mDb.search(
                        "新中野",
                        new SearchSpec.Builder()
                                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                                .build());
        List<GenericDocument> documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents).containsExactly(doc1);

        SearchSpec spec = new SearchSpec.Builder()
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .build();

        // Searching by last name returns the document
        searchResults = mDb.search("新", spec);
        documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents).containsExactly(doc1);

        // Searching by middle name doesn't return the document
        searchResults = mDb.search("中", spec);
        documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents).isEmpty();

        // Searching by first name doesn't return the document
        searchResults = mDb.search("野", spec);
        documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents).isEmpty();
    }

    List<Person> generatePersonData(int numContacts) {
        List<Person> personList = new ArrayList<>();
        for (int i = 0; i < numContacts; i++) {
            personList.add(
                    new Person.Builder(/*namespace=*/ "", String.valueOf(i), "name" + i).build());
        }
        return personList;
    }
}