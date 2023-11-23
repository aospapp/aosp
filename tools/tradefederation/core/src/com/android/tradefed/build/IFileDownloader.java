/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tradefed.build;

import java.io.File;

/**
 * Interface for downloading a remote file.
 */
public interface IFileDownloader {

    /**
     * Downloads a remote file to a temporary file on local disk.
     *
     * @param remoteFilePath the remote path to the file to download, relative to a implementation
     * specific root.
     * @return the temporary local downloaded {@link File}.
     * @throws BuildRetrievalError if file could not be downloaded
     */
    public File downloadFile(String remoteFilePath) throws BuildRetrievalError;

    /**
     * Alternate form of {@link #downloadFile(String)}, that allows caller to specify the
     * destination file the remote contents should be placed in.
     *
     * @param relativeRemotePath the remote path to the file to download, relative to an
     *        implementation-specific root.
     * @param destFile the file to place the downloaded contents into. Should not exist.
     * @throws BuildRetrievalError if file could not be downloaded
     */
    public void downloadFile(String relativeRemotePath, File destFile) throws BuildRetrievalError;

    /**
     * Check local file's freshness. If local file is the same as remote file, then it's fresh. If
     * not, local file is stale. This is mainly used for cache. The default implementation will
     * always return true, so if the file is immutable it will never need to check freshness.
     *
     * @param localFile local file.
     * @param remoteFilePath remote file path.
     * @return True if local file is fresh, otherwise false.
     * @throws BuildRetrievalError
     */
    public default boolean isFresh(File localFile, String remoteFilePath)
            throws BuildRetrievalError {
        return true;
    }
}
