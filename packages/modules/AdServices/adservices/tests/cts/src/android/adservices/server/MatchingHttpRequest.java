/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.adservices.server;

import android.net.Uri;
import com.google.auto.value.AutoValue;

/** Specifies a partial request to match against and mock. */
@AutoValue
public abstract class MatchingHttpRequest {
  public abstract Uri getUri();

  public abstract HttpMethod getMethod();

    public static android.adservices.server.MatchingHttpRequest.Builder builder() {
        return new AutoValue_MatchingHttpRequest.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract android.adservices.server.MatchingHttpRequest.Builder setUri(Uri uri);

        public abstract android.adservices.server.MatchingHttpRequest.Builder setMethod(
                HttpMethod method);

        public abstract MatchingHttpRequest build();
    }
}
