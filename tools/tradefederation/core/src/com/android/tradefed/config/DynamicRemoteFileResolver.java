/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.config;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.OptionSetter.OptionFieldsForName;
import com.android.tradefed.config.remote.GcsRemoteFileResolver;
import com.android.tradefed.config.remote.IRemoteFileResolver;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class that helps resolving path to remote files.
 *
 * <p>For example: gs://bucket/path/file.txt will be resolved by downloading the file from the GCS
 * bucket.
 */
public class DynamicRemoteFileResolver {

    public static final String DYNAMIC_RESOLVER = "dynamic-resolver";
    private static final Map<String, IRemoteFileResolver> PROTOCOL_SUPPORT = new HashMap<>();

    static {
        PROTOCOL_SUPPORT.put(GcsRemoteFileResolver.PROTOCOL, new GcsRemoteFileResolver());
    }
    // The configuration map being static, we only need to update it once per TF instance.
    private static AtomicBoolean sIsUpdateDone = new AtomicBoolean(false);

    private Map<String, OptionFieldsForName> mOptionMap;

    /** Sets the map of options coming from {@link OptionSetter} */
    public void setOptionMap(Map<String, OptionFieldsForName> optionMap) {
        mOptionMap = optionMap;
    }

    /**
     * Runs through all the {@link File} option type and check if their path should be resolved.
     *
     * @return The list of {@link File} that was resolved that way.
     * @throws ConfigurationException
     */
    public final Set<File> validateRemoteFilePath() throws ConfigurationException {
        Set<File> downloadedFiles = new HashSet<>();
        try {
            for (Map.Entry<String, OptionFieldsForName> optionPair : mOptionMap.entrySet()) {
                final OptionFieldsForName optionFields = optionPair.getValue();
                for (Map.Entry<Object, Field> fieldEntry : optionFields) {
                    final Object obj = fieldEntry.getKey();
                    final Field field = fieldEntry.getValue();
                    final Option option = field.getAnnotation(Option.class);
                    if (option == null) {
                        continue;
                    }
                    // At this point, we know this is an option field; make sure it's set
                    field.setAccessible(true);
                    final Object value;
                    try {
                        value = field.get(obj);
                    } catch (IllegalAccessException e) {
                        throw new ConfigurationException(
                                String.format("internal error: %s", e.getMessage()));
                    }

                    if (value == null) {
                        continue;
                    } else if (value instanceof File) {
                        File consideredFile = (File) value;
                        File downloadedFile = resolveRemoteFiles(consideredFile, option);
                        if (downloadedFile != null) {
                            downloadedFiles.add(downloadedFile);
                            // Replace the field value
                            try {
                                field.set(obj, downloadedFile);
                            } catch (IllegalAccessException e) {
                                CLog.e(e);
                                throw new ConfigurationException(
                                        String.format(
                                                "Failed to download %s due to '%s'",
                                                consideredFile.getPath(), e.getMessage()),
                                        e);
                            }
                        }
                    } else if (value instanceof Collection) {
                        Collection<Object> c = (Collection<Object>) value;
                        Collection<Object> copy = new ArrayList<>(c);
                        for (Object o : copy) {
                            if (o instanceof File) {
                                File consideredFile = (File) o;
                                File downloadedFile = resolveRemoteFiles(consideredFile, option);
                                if (downloadedFile != null) {
                                    downloadedFiles.add(downloadedFile);
                                    // TODO: See if order could be preserved.
                                    c.remove(consideredFile);
                                    c.add(downloadedFile);
                                }
                            }
                        }
                    } else if (value instanceof Map) {
                        Map<Object, Object> m = (Map<Object, Object>) value;
                        Map<Object, Object> copy = new LinkedHashMap<>(m);
                        for (Entry<Object, Object> entry : copy.entrySet()) {
                            Object key = entry.getKey();
                            Object val = entry.getValue();

                            Object finalKey = key;
                            Object finalVal = val;
                            if (key instanceof File) {
                                key = resolveRemoteFiles((File) key, option);
                                if (key != null) {
                                    downloadedFiles.add((File) key);
                                    finalKey = key;
                                }
                            }
                            if (val instanceof File) {
                                val = resolveRemoteFiles((File) val, option);
                                if (val != null) {
                                    downloadedFiles.add((File) val);
                                    finalVal = val;
                                }
                            }

                            m.remove(entry.getKey());
                            m.put(finalKey, finalVal);
                        }
                    } else if (value instanceof MultiMap) {
                        MultiMap<Object, Object> m = (MultiMap<Object, Object>) value;
                        MultiMap<Object, Object> copy = new MultiMap<>(m);
                        for (Object key : copy.keySet()) {
                            List<Object> mapValues = copy.get(key);

                            m.remove(key);
                            Object finalKey = key;
                            if (key instanceof File) {
                                key = resolveRemoteFiles((File) key, option);
                                if (key != null) {
                                    downloadedFiles.add((File) key);
                                    finalKey = key;
                                }
                            }
                            for (Object mapValue : mapValues) {
                                if (mapValue instanceof File) {
                                    File f = resolveRemoteFiles((File) mapValue, option);
                                    if (f != null) {
                                        downloadedFiles.add(f);
                                        mapValue = f;
                                    }
                                }
                                m.put(finalKey, mapValue);
                            }
                        }
                    }
                }
            }
        } catch (ConfigurationException e) {
            // Clean up the files before throwing
            for (File f : downloadedFiles) {
                FileUtil.recursiveDelete(f);
            }
            throw e;
        }
        return downloadedFiles;
    }

    @VisibleForTesting
    protected IRemoteFileResolver getResolver(String protocol) {
        if (updateProtocols()) {
            IGlobalConfiguration globalConfig = getGlobalConfig();
            Object o = globalConfig.getConfigurationObject(DYNAMIC_RESOLVER);
            if (o != null) {
                if (o instanceof IRemoteFileResolver) {
                    IRemoteFileResolver resolver = (IRemoteFileResolver) o;
                    CLog.d("Adding %s to supported remote file resolver", resolver);
                    PROTOCOL_SUPPORT.put(resolver.getSupportedProtocol(), resolver);
                } else {
                    CLog.e("%s is not of type IRemoteFileResolver", o);
                }
            }
        }
        return PROTOCOL_SUPPORT.get(protocol);
    }

    @VisibleForTesting
    protected boolean updateProtocols() {
        return sIsUpdateDone.compareAndSet(false, true);
    }

    @VisibleForTesting
    IGlobalConfiguration getGlobalConfig() {
        return GlobalConfiguration.getInstance();
    }

    private File resolveRemoteFiles(File consideredFile, Option option)
            throws ConfigurationException {
        String path = consideredFile.getPath();
        String protocol = getProtocol(path);
        IRemoteFileResolver resolver = getResolver(protocol);
        if (resolver != null) {
            return resolver.resolveRemoteFiles(consideredFile, option);
        }
        // Not a remote file
        return null;
    }

    /**
     * Java URL doesn't recognize 'gs' as a protocol and throws an exception so we do the protocol
     * extraction ourselves.
     */
    private String getProtocol(String path) {
        int index = path.indexOf(":/");
        if (index == -1) {
            return "";
        }
        return path.substring(0, index);
    }
}
