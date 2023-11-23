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

package com.android.adservices.data.topics;

import android.annotation.NonNull;
import android.app.adservices.topics.TopicParcel;

import com.android.internal.annotations.Immutable;

import com.google.auto.value.AutoValue;

import java.util.Objects;

/**
 * POJO Represents a Topic.
 *
 * @hide
 */
@Immutable
@AutoValue
public abstract class Topic {

    /**
     * @return an Integer represents the topic details
     */
    public abstract int getTopic();

    /**
     * @return the taxonomy version number
     */
    public abstract long getTaxonomyVersion();

    /**
     * @return the model version number
     */
    public abstract long getModelVersion();

    /**
     * @return generic builder
     */
    @NonNull
    public static Builder builder() {
        return new AutoValue_Topic.Builder();
    }

    /**
     * Creates an instance of Topic
     *
     * @param topic topic details
     * @param taxonomyVersion taxonomy version number
     * @param modelVersion model version number
     * @return an instance of Topic
     */
    @NonNull
    public static Topic create(
            int topic,
            long taxonomyVersion,
            long modelVersion) {
        Objects.requireNonNull(topic);

        return builder().setTopic(topic)
                .setTaxonomyVersion(taxonomyVersion)
                .setModelVersion(modelVersion).build();
    }

    /**
     * Builder for {@link Topic}
     */
    @AutoValue.Builder
    public abstract static class Builder {
        /** Set Topic */
        public abstract Builder setTopic(int topic);

        /** Set Taxonomy Version */
        public abstract Builder setTaxonomyVersion(long taxonomyVersion);

        /** Set Model Version */
        public abstract Builder setModelVersion(long modelVersion);

        /** Build a Topic instance */
        @NonNull
        public abstract Topic build();
    }

    /**
     * Return the parcel for this topic.
     *
     * @hide
     */
    @NonNull
    public TopicParcel convertTopicToTopicParcel() {
        return new TopicParcel.Builder()
                .setTopicId(getTopic())
                .setTaxonomyVersion(getTaxonomyVersion())
                .setModelVersion(getModelVersion())
                .build();
    }
}
