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
package com.android.car.audio;

import static android.car.PlatformVersion.VERSION_CODES.UPSIDE_DOWN_CAKE_0;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT;

import static com.android.car.audio.CarAudioService.CAR_DEFAULT_AUDIO_ATTRIBUTE;
import static com.android.car.audio.CarAudioUtils.isMicrophoneInputDevice;

import static java.util.Locale.ROOT;

import android.annotation.NonNull;
import android.car.builtin.media.AudioManagerHelper;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.car.audio.CarAudioContext.AudioContext;
import com.android.car.internal.util.VersionUtils;
import com.android.internal.util.Preconditions;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A helper class loads all audio zones from the configuration XML file.
 */
/* package */ final class CarAudioZonesHelper {
    private static final String NAMESPACE = null;
    private static final String TAG_ROOT = "carAudioConfiguration";

    private static final String TAG_OEM_CONTEXTS = "oemContexts";
    private static final String TAG_OEM_CONTEXT = "oemContext";
    private static final String TAG_AUDIO_ATTRIBUTES = "audioAttributes";
    private static final String TAG_AUDIO_ATTRIBUTE = "audioAttribute";
    private static final String TAG_USAGE = "usage";
    private static final String ATTR_USAGE_VALUE = "value";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_CONTENT_TYPE = "contentType";
    private static final String ATTR_USAGE = "usage";
    private static final String ATTR_TAGS = "tags";

    private static final String TAG_AUDIO_ZONES = "zones";
    private static final String TAG_AUDIO_ZONE = "zone";
    private static final String TAG_AUDIO_ZONE_CONFIGS = "zoneConfigs";
    private static final String TAG_AUDIO_ZONE_CONFIG = "zoneConfig";
    private static final String TAG_VOLUME_GROUPS = "volumeGroups";
    private static final String TAG_VOLUME_GROUP = "group";
    private static final String TAG_AUDIO_DEVICE = "device";
    private static final String TAG_CONTEXT = "context";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_IS_PRIMARY = "isPrimary";
    private static final String ATTR_IS_CONFIG_DEFAULT = "isDefault";
    private static final String ATTR_ZONE_NAME = "name";
    private static final String ATTR_CONFIG_NAME = "name";
    private static final String ATTR_DEVICE_ADDRESS = "address";
    private static final String ATTR_CONTEXT_NAME = "context";
    private static final String ATTR_ZONE_ID = "audioZoneId";
    private static final String ATTR_OCCUPANT_ZONE_ID = "occupantZoneId";
    private static final String TAG_INPUT_DEVICES = "inputDevices";
    private static final String TAG_INPUT_DEVICE = "inputDevice";
    private static final String TAG_MIRRORING_DEVICES = "mirroringDevices";
    private static final String TAG_MIRRORING_DEVICE = "mirroringDevice";
    private static final int INVALID_VERSION = -1;
    private static final int SUPPORTED_VERSION_1 = 1;
    private static final int SUPPORTED_VERSION_2 = 2;
    private static final int SUPPORTED_VERSION_3 = 3;
    private static final SparseIntArray SUPPORTED_VERSIONS;

    static {
        SUPPORTED_VERSIONS = new SparseIntArray(3);
        SUPPORTED_VERSIONS.put(SUPPORTED_VERSION_1, SUPPORTED_VERSION_1);
        SUPPORTED_VERSIONS.put(SUPPORTED_VERSION_2, SUPPORTED_VERSION_2);
        SUPPORTED_VERSIONS.put(SUPPORTED_VERSION_3, SUPPORTED_VERSION_3);
    }

    private final AudioManager mAudioManager;
    private final CarAudioSettings mCarAudioSettings;
    private final List<CarAudioContextInfo> mCarAudioContextInfos = new ArrayList<>();
    private final Map<String, CarAudioDeviceInfo> mAddressToCarAudioDeviceInfo;
    private final Map<String, AudioDeviceInfo> mAddressToInputAudioDeviceInfoForAllInputDevices;
    private final InputStream mInputStream;
    private final SparseIntArray mZoneIdToOccupantZoneIdMapping;
    private final Set<Integer> mAudioZoneIds;
    private final Set<String> mAssignedInputAudioDevices;
    private final Set<String> mAudioZoneConfigNames;
    private final boolean mUseCarVolumeGroupMute;
    private final boolean mUseCoreAudioVolume;
    private final boolean mUseCoreAudioRouting;
    private final List<CarAudioDeviceInfo> mMirroringDevices = new ArrayList<>();

    private final ArrayMap<String, Integer> mContextNameToId = new ArrayMap<>();
    private CarAudioContext mCarAudioContext;
    private int mNextSecondaryZoneId;
    private int mCurrentVersion;

    /**
     * <p><b>Note: <b/> CarAudioZonesHelper is expected to be used from a single thread. This
     * should be the same thread that originally called new CarAudioZonesHelper.
     */
    CarAudioZonesHelper(AudioManager audioManager,
            @NonNull CarAudioSettings carAudioSettings,
            @NonNull InputStream inputStream,
            @NonNull List<CarAudioDeviceInfo> carAudioDeviceInfos,
            @NonNull AudioDeviceInfo[] inputDeviceInfo, boolean useCarVolumeGroupMute,
            boolean useCoreAudioVolume, boolean useCoreAudioRouting) {
        mAudioManager = Objects.requireNonNull(audioManager,
                "Audio manager cannot be null");
        mCarAudioSettings = Objects.requireNonNull(carAudioSettings);
        mInputStream = Objects.requireNonNull(inputStream);
        Objects.requireNonNull(carAudioDeviceInfos);
        Objects.requireNonNull(inputDeviceInfo);
        mAddressToCarAudioDeviceInfo = CarAudioZonesHelper.generateAddressToInfoMap(
                carAudioDeviceInfos);
        mAddressToInputAudioDeviceInfoForAllInputDevices =
                CarAudioZonesHelper.generateAddressToInputAudioDeviceInfoMap(inputDeviceInfo);
        mNextSecondaryZoneId = PRIMARY_AUDIO_ZONE + 1;
        mZoneIdToOccupantZoneIdMapping = new SparseIntArray();
        mAudioZoneIds = new ArraySet<>();
        mAssignedInputAudioDevices = new ArraySet<>();
        mAudioZoneConfigNames = new ArraySet<>();
        mUseCarVolumeGroupMute = useCarVolumeGroupMute;
        mUseCoreAudioVolume = useCoreAudioVolume;
        mUseCoreAudioRouting = useCoreAudioRouting;
    }

    SparseIntArray getCarAudioZoneIdToOccupantZoneIdMapping() {
        return mZoneIdToOccupantZoneIdMapping;
    }

    SparseArray<CarAudioZone> loadAudioZones() throws IOException, XmlPullParserException {
        return parseCarAudioZones(mInputStream);
    }

    private static Map<String, CarAudioDeviceInfo> generateAddressToInfoMap(
            List<CarAudioDeviceInfo> carAudioDeviceInfos) {
        return carAudioDeviceInfos.stream()
                .filter(info -> !TextUtils.isEmpty(info.getAddress()))
                .collect(Collectors.toMap(CarAudioDeviceInfo::getAddress, info -> info));
    }

    private static Map<String, AudioDeviceInfo> generateAddressToInputAudioDeviceInfoMap(
            @NonNull AudioDeviceInfo[] inputAudioDeviceInfos) {
        Map<String, AudioDeviceInfo> deviceAddressToInputDeviceMap =
                new ArrayMap<>(inputAudioDeviceInfos.length);
        for (int i = 0; i < inputAudioDeviceInfos.length; ++i) {
            AudioDeviceInfo device = inputAudioDeviceInfos[i];
            if (device.isSource()) {
                deviceAddressToInputDeviceMap.put(device.getAddress(), device);
            }
        }
        return deviceAddressToInputDeviceMap;
    }

    private SparseArray<CarAudioZone> parseCarAudioZones(InputStream stream)
            throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, NAMESPACE != null);
        parser.setInput(stream, null);

        // Ensure <carAudioConfiguration> is the root
        parser.nextTag();
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_ROOT);

        // Version check
        final int versionNumber = Integer.parseInt(
                parser.getAttributeValue(NAMESPACE, ATTR_VERSION));

        if (SUPPORTED_VERSIONS.get(versionNumber, INVALID_VERSION) == INVALID_VERSION) {
            throw new IllegalArgumentException("Latest Supported version:"
                    + SUPPORTED_VERSION_3 + " , got version:" + versionNumber);
        }

        mCurrentVersion = versionNumber;
        // Get all zones configured under <zones> tag
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_OEM_CONTEXTS.equals(parser.getName())) {
                parseCarAudioContexts(parser);
            } else if (TAG_MIRRORING_DEVICES.equals(parser.getName())) {
                parseMirroringDevices(parser);
            } else if (TAG_AUDIO_ZONES.equals(parser.getName())) {
                loadCarAudioContexts();
                return parseAudioZones(parser);
            } else {
                skip(parser);
            }
        }
        throw new MissingResourceException(TAG_AUDIO_ZONES + " is missing from configuration",
                "", TAG_AUDIO_ZONES);
    }

    private void parseMirroringDevices(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        if (isVersionLessThanThree()) {
            throw new IllegalStateException(
                    TAG_MIRRORING_DEVICES + " are not supported in car_audio_configuration.xml"
                            + " version " + mCurrentVersion + ". Must be at least version "
                            + SUPPORTED_VERSION_3);
        }

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            if (TAG_MIRRORING_DEVICE.equals(parser.getName())) {
                parseMirroringDevice(parser);
            }
            skip(parser);
        }
    }

    private void parseMirroringDevice(XmlPullParser parser) {
        String address = parser.getAttributeValue(NAMESPACE, ATTR_DEVICE_ADDRESS);
        validateOutputDeviceExist(address);
        CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.get(address);
        if (mMirroringDevices.contains(info)) {
            throw new IllegalArgumentException(TAG_MIRRORING_DEVICE + " " + address
                    + " repeats, " + TAG_MIRRORING_DEVICES + " can not repeat.");
        }
        mMirroringDevices.add(info);
    }

    private void loadCarAudioContexts() {
        if (isVersionLessThanThree() || mCarAudioContextInfos.isEmpty()) {
            mCarAudioContextInfos.addAll(CarAudioContext.getAllContextsInfo());
        }
        for (int index = 0; index < mCarAudioContextInfos.size(); index++) {
            CarAudioContextInfo info = mCarAudioContextInfos.get(index);
            mContextNameToId.put(info.getName().toLowerCase(ROOT), info.getId());
        }
        mCarAudioContext = new CarAudioContext(mCarAudioContextInfos, mUseCoreAudioRouting);
    }

    private void parseCarAudioContexts(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int contextId = 0;

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            if (TAG_OEM_CONTEXT.equals(parser.getName())) {
                parseCarAudioContext(parser, contextId);
                contextId++;
            } else {
                skip(parser);
            }
        }
    }

    private void parseCarAudioContext(XmlPullParser parser, int contextId)
            throws XmlPullParserException, IOException {
        String contextName = parser.getAttributeValue(NAMESPACE, ATTR_NAME);
        CarAudioContextInfo context = null;
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            if (TAG_AUDIO_ATTRIBUTES.equals(parser.getName())) {
                List<AudioAttributes> attributes = parseAudioAttributes(parser, contextName);
                if (mUseCoreAudioRouting) {
                    contextId = CoreAudioHelper.getStrategyForAudioAttributes(attributes.get(0));
                    if (contextId == CoreAudioHelper.INVALID_STRATEGY) {
                        throw new IllegalArgumentException(TAG_AUDIO_ATTRIBUTES
                                + ": Cannot find strategy id for context: "
                                + contextName + " and attributes \"" + attributes.get(0) + "\" .");
                    }
                }
                validateCarAudioContextAttributes(contextId, attributes, contextName);
                context = new CarAudioContextInfo(attributes.toArray(new AudioAttributes[0]),
                        contextName, contextId);
                mCarAudioContextInfos.add(context);
            } else {
                skip(parser);
            }
        }
    }

    private void validateCarAudioContextAttributes(int contextId, List<AudioAttributes> attributes,
            String contextName) {
        if (!mUseCoreAudioRouting) {
            return;
        }
        AudioProductStrategy strategy = CoreAudioHelper.getStrategy(contextId);
        Preconditions.checkNotNull(strategy, "No strategy for context id = %d", contextId);
        for (int index = 0; index < attributes.size(); index++) {
            AudioAttributes aa = attributes.get(index);
            if (!strategy.supportsAudioAttributes(aa)
                    && !CoreAudioHelper.isDefaultStrategy(strategy.getId())) {
                throw new IllegalArgumentException("Invalid attributes " + aa + " for context: "
                        + contextName);
            }
        }
    }

    private List<AudioAttributes> parseAudioAttributes(XmlPullParser parser, String contextName)
            throws XmlPullParserException, IOException {
        List<AudioAttributes> supportedAttributes = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            if (TAG_USAGE.equals(parser.getName())) {
                AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
                parseUsage(parser, attributesBuilder, ATTR_USAGE_VALUE);
                AudioAttributes attributes = attributesBuilder.build();
                supportedAttributes.add(attributes);
            } else if (TAG_AUDIO_ATTRIBUTE.equals(parser.getName())) {
                AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
                // Usage, ContentType and tags are optional but at least one value must be
                // provided to build a valid audio attributes
                boolean hasValidUsage = parseUsage(parser, attributesBuilder, ATTR_USAGE);
                boolean hasValidContentType = parseContentType(parser, attributesBuilder);
                boolean hasValidTags = parseTags(parser, attributesBuilder);
                if (!(hasValidUsage || hasValidContentType || hasValidTags)) {
                    throw new RuntimeException("Empty attributes for context: " + contextName);
                }
                AudioAttributes attributes = attributesBuilder.build();
                supportedAttributes.add(attributes);
            }
            // Always skip to upper level since we're at the lowest.
            skip(parser);
        }
        if (supportedAttributes.isEmpty()) {
            throw new IllegalArgumentException("No attributes for context: " + contextName);
        }
        return supportedAttributes;
    }

    private boolean parseUsage(XmlPullParser parser, AudioAttributes.Builder builder,
                               String attrValue)
            throws XmlPullParserException, IOException {
        String usageLiteral = parser.getAttributeValue(NAMESPACE, attrValue);
        if (usageLiteral == null) {
            return false;
        }
        int usage = AudioManagerHelper.xsdStringToUsage(usageLiteral);
        // TODO (b/248106031): Remove once AUDIO_USAGE_NOTIFICATION_EVENT is fixed in core
        if (Objects.equals(usageLiteral, "AUDIO_USAGE_NOTIFICATION_EVENT")) {
            usage = USAGE_NOTIFICATION_EVENT;
        }
        if (AudioAttributes.isSystemUsage(usage)) {
            builder.setSystemUsage(usage);
        } else {
            builder.setUsage(usage);
        }
        return true;
    }

    private boolean parseContentType(XmlPullParser parser, AudioAttributes.Builder builder)
            throws XmlPullParserException, IOException {
        if (!VersionUtils.isPlatformVersionAtLeastU()) {
            throw new IllegalArgumentException("car_audio_configuration.xml tag "
                    + ATTR_CONTENT_TYPE + ", is only supported for release version "
                    + UPSIDE_DOWN_CAKE_0 + " and higher");
        }
        String contentTypeLiteral = parser.getAttributeValue(NAMESPACE, ATTR_CONTENT_TYPE);
        if (contentTypeLiteral == null) {
            return false;
        }
        int contentType = AudioManagerHelper.xsdStringToContentType(contentTypeLiteral);
        builder.setContentType(contentType);
        return true;
    }

    private boolean parseTags(XmlPullParser parser, AudioAttributes.Builder builder)
            throws XmlPullParserException, IOException {
        String tagsLiteral = parser.getAttributeValue(NAMESPACE, ATTR_TAGS);
        if (!VersionUtils.isPlatformVersionAtLeastU()) {
            throw new IllegalArgumentException("car_audio_configuration.xml tag " + ATTR_TAGS
                    + ", is only supported for release version " + UPSIDE_DOWN_CAKE_0
                    + " and higher");
        }
        if (tagsLiteral == null) {
            return false;
        }
        AudioManagerHelper.addTagToAudioAttributes(builder, tagsLiteral);
        return true;
    }

    private SparseArray<CarAudioZone> parseAudioZones(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        SparseArray<CarAudioZone> carAudioZones = new SparseArray<>();

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_ZONE.equals(parser.getName())) {
                CarAudioZone zone = parseAudioZone(parser);
                verifyOnlyOnePrimaryZone(zone, carAudioZones);
                carAudioZones.put(zone.getId(), zone);
            } else {
                skip(parser);
            }
        }

        verifyPrimaryZonePresent(carAudioZones);
        addRemainingMicrophonesToPrimaryZone(carAudioZones);
        return carAudioZones;
    }

    private void addRemainingMicrophonesToPrimaryZone(SparseArray<CarAudioZone> carAudioZones) {
        CarAudioZone primaryAudioZone = carAudioZones.get(PRIMARY_AUDIO_ZONE);
        for (AudioDeviceInfo info : mAddressToInputAudioDeviceInfoForAllInputDevices.values()) {
            if (!mAssignedInputAudioDevices.contains(info.getAddress())
                    && isMicrophoneInputDevice(info)) {
                primaryAudioZone.addInputAudioDevice(new AudioDeviceAttributes(info));
            }
        }
    }

    private void verifyOnlyOnePrimaryZone(CarAudioZone newZone, SparseArray<CarAudioZone> zones) {
        if (newZone.getId() == PRIMARY_AUDIO_ZONE && zones.contains(PRIMARY_AUDIO_ZONE)) {
            throw new RuntimeException("More than one zone parsed with primary audio zone ID: "
                    + PRIMARY_AUDIO_ZONE);
        }
    }

    private void verifyPrimaryZonePresent(SparseArray<CarAudioZone> zones) {
        if (!zones.contains(PRIMARY_AUDIO_ZONE)) {
            throw new RuntimeException("Primary audio zone is required");
        }
    }

    private CarAudioZone parseAudioZone(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        final boolean isPrimary = Boolean.parseBoolean(
                parser.getAttributeValue(NAMESPACE, ATTR_IS_PRIMARY));
        final String zoneName = parser.getAttributeValue(NAMESPACE, ATTR_ZONE_NAME);
        final int audioZoneId = getZoneId(isPrimary, parser);
        parseOccupantZoneId(audioZoneId, parser);
        final CarAudioZone zone = new CarAudioZone(mCarAudioContext, zoneName, audioZoneId);
        if (isVersionLessThanThree()) {
            final CarAudioZoneConfig.Builder zoneConfigBuilder = new CarAudioZoneConfig.Builder(
                    zoneName, audioZoneId, /* zoneConfigId= */ 0, /* isDefault= */ true);
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                // Expect at least one <volumeGroups> in one audio zone
                if (TAG_VOLUME_GROUPS.equals(parser.getName())) {
                    parseVolumeGroups(parser, zoneConfigBuilder);
                } else if (TAG_INPUT_DEVICES.equals(parser.getName())) {
                    parseInputAudioDevices(parser, zone);
                } else {
                    skip(parser);
                }
            }
            zone.addZoneConfig(zoneConfigBuilder.build());
            return zone;
        }
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            // Expect at least one <zoneConfigs> in one audio zone
            if (TAG_AUDIO_ZONE_CONFIGS.equals(parser.getName())) {
                parseZoneConfigs(parser, zone);
            } else if (TAG_INPUT_DEVICES.equals(parser.getName())) {
                parseInputAudioDevices(parser, zone);
            } else {
                skip(parser);
            }
        }
        return zone;
    }

    private int getZoneId(boolean isPrimary, XmlPullParser parser) {
        String audioZoneIdString = parser.getAttributeValue(NAMESPACE, ATTR_ZONE_ID);
        if (isVersionOne()) {
            Preconditions.checkArgument(audioZoneIdString == null,
                    "Invalid audio attribute %s"
                            + ", Please update car audio configurations file "
                            + "to version to 2 to use it.", ATTR_ZONE_ID);
            return isPrimary ? PRIMARY_AUDIO_ZONE
                    : getNextSecondaryZoneId();
        }
        // Primary zone does not need to define it
        if (isPrimary && audioZoneIdString == null) {
            return PRIMARY_AUDIO_ZONE;
        }
        Objects.requireNonNull(audioZoneIdString,
                "Requires audioZoneId for all audio zones.");
        int zoneId = parsePositiveIntAttribute(ATTR_ZONE_ID, audioZoneIdString);
        //Verify that primary zone id is PRIMARY_AUDIO_ZONE
        if (isPrimary) {
            Preconditions.checkArgument(zoneId == PRIMARY_AUDIO_ZONE,
                    "Primary zone %s must be %d or it can be left empty.",
                    ATTR_ZONE_ID, PRIMARY_AUDIO_ZONE);
        } else {
            Preconditions.checkArgument(zoneId != PRIMARY_AUDIO_ZONE,
                    "%s can only be %d for primary zone.",
                    ATTR_ZONE_ID, PRIMARY_AUDIO_ZONE);
        }
        validateAudioZoneIdIsUnique(zoneId);
        return zoneId;
    }

    private void parseOccupantZoneId(int audioZoneId, XmlPullParser parser) {
        String occupantZoneIdString = parser.getAttributeValue(NAMESPACE, ATTR_OCCUPANT_ZONE_ID);
        if (isVersionOne()) {
            Preconditions.checkArgument(occupantZoneIdString == null,
                    "Invalid audio attribute %s"
                            + ", Please update car audio configurations file "
                            + "to version to 2 to use it.", ATTR_OCCUPANT_ZONE_ID);
            return;
        }
        //Occupant id not required for all zones
        if (occupantZoneIdString == null) {
            return;
        }
        int occupantZoneId = parsePositiveIntAttribute(ATTR_OCCUPANT_ZONE_ID, occupantZoneIdString);
        validateOccupantZoneIdIsUnique(occupantZoneId);
        mZoneIdToOccupantZoneIdMapping.put(audioZoneId, occupantZoneId);
    }

    private int parsePositiveIntAttribute(String attribute, String integerString) {
        try {
            return Integer.parseUnsignedInt(integerString);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException(attribute + " must be a positive integer, but was \""
                    + integerString + "\" instead.", e);
        }
    }

    private void parseInputAudioDevices(XmlPullParser parser, CarAudioZone zone)
            throws IOException, XmlPullParserException {
        if (isVersionOne()) {
            throw new IllegalStateException(
                    TAG_INPUT_DEVICES + " are not supported in car_audio_configuration.xml version "
                            + SUPPORTED_VERSION_1);
        }
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_INPUT_DEVICE.equals(parser.getName())) {
                String audioDeviceAddress =
                        parser.getAttributeValue(NAMESPACE, ATTR_DEVICE_ADDRESS);
                validateInputAudioDeviceAddress(audioDeviceAddress);
                AudioDeviceInfo info =
                        mAddressToInputAudioDeviceInfoForAllInputDevices.get(audioDeviceAddress);
                Preconditions.checkArgument(info != null,
                        "%s %s of %s does not exist, add input device to"
                                + " audio_policy_configuration.xml.",
                        ATTR_DEVICE_ADDRESS, audioDeviceAddress, TAG_INPUT_DEVICE);
                zone.addInputAudioDevice(new AudioDeviceAttributes(info));
            }
            skip(parser);
        }
    }

    private void validateInputAudioDeviceAddress(String audioDeviceAddress) {
        Objects.requireNonNull(audioDeviceAddress, () ->
                TAG_INPUT_DEVICE + " " + ATTR_DEVICE_ADDRESS + " attribute must be present.");
        Preconditions.checkArgument(!audioDeviceAddress.isEmpty(),
                "%s %s attribute can not be empty.",
                TAG_INPUT_DEVICE, ATTR_DEVICE_ADDRESS);
        if (mAssignedInputAudioDevices.contains(audioDeviceAddress)) {
            throw new IllegalArgumentException(TAG_INPUT_DEVICE + " " + audioDeviceAddress
                    + " repeats, " + TAG_INPUT_DEVICES + " can not repeat.");
        }
        mAssignedInputAudioDevices.add(audioDeviceAddress);
    }

    private void validateOccupantZoneIdIsUnique(int occupantZoneId) {
        if (mZoneIdToOccupantZoneIdMapping.indexOfValue(occupantZoneId) > -1) {
            throw new IllegalArgumentException(ATTR_OCCUPANT_ZONE_ID + " " + occupantZoneId
                    + " is already associated with a zone");
        }
    }

    private void validateAudioZoneIdIsUnique(int audioZoneId) {
        if (mAudioZoneIds.contains(audioZoneId)) {
            throw new IllegalArgumentException(ATTR_ZONE_ID + " " + audioZoneId
                    + " is already associated with a zone");
        }
        mAudioZoneIds.add(audioZoneId);
    }

    private void parseZoneConfigs(XmlPullParser parser, CarAudioZone zone)
            throws XmlPullParserException, IOException {
        int zoneConfigId = 0;
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_ZONE_CONFIG.equals(parser.getName())) {
                if (zone.getId() == PRIMARY_AUDIO_ZONE && zoneConfigId > 0) {
                    throw new IllegalArgumentException(
                            "Primary zone cannot have multiple zone configurations");
                }
                parseZoneConfig(parser, zone, zoneConfigId);
                zoneConfigId++;
            } else {
                skip(parser);
            }
        }
    }

    private void parseZoneConfig(XmlPullParser parser, CarAudioZone zone, int zoneConfigId)
            throws XmlPullParserException, IOException {
        final boolean isDefault = Boolean.parseBoolean(
                parser.getAttributeValue(NAMESPACE, ATTR_IS_CONFIG_DEFAULT));
        final String zoneConfigName = parser.getAttributeValue(NAMESPACE, ATTR_CONFIG_NAME);
        validateAudioZoneConfigName(zoneConfigName);
        final CarAudioZoneConfig.Builder zoneConfigBuilder = new CarAudioZoneConfig.Builder(
                zoneConfigName, zone.getId(), zoneConfigId, isDefault);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            // Expect at least one <volumeGroups> in one audio zone config
            if (TAG_VOLUME_GROUPS.equals(parser.getName())) {
                parseVolumeGroups(parser, zoneConfigBuilder);
            } else {
                skip(parser);
            }
        }
        zone.addZoneConfig(zoneConfigBuilder.build());
    }

    private void validateAudioZoneConfigName(String configName) {
        Objects.requireNonNull(configName, TAG_AUDIO_ZONE_CONFIG + " " + ATTR_CONFIG_NAME
                        + " attribute must be present.");
        Preconditions.checkArgument(!configName.isEmpty(),
                "%s %s attribute can not be empty.",
                TAG_AUDIO_ZONE_CONFIG, ATTR_CONFIG_NAME);
        if (mAudioZoneConfigNames.contains(configName)) {
            throw new IllegalArgumentException(ATTR_CONFIG_NAME + " " + configName
                            + " repeats, " + ATTR_CONFIG_NAME + " can not repeat.");
        }
        mAudioZoneConfigNames.add(configName);
    }

    private void parseVolumeGroups(XmlPullParser parser,
            CarAudioZoneConfig.Builder zoneConfigBuilder)
            throws XmlPullParserException, IOException {
        int groupId = 0;
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_VOLUME_GROUP.equals(parser.getName())) {
                String groupName = parser.getAttributeValue(NAMESPACE, ATTR_NAME);
                Preconditions.checkArgument(!mUseCoreAudioVolume || groupName != null,
                        "%s %s attribute can not be empty when relying on core volume groups",
                        TAG_VOLUME_GROUP, ATTR_NAME);
                if (groupName == null) {
                    groupName = new StringBuilder().append("config ")
                            .append(zoneConfigBuilder.getZoneConfigId()).append(" group ")
                            .append(groupId).toString();
                }
                zoneConfigBuilder.addVolumeGroup(parseVolumeGroup(parser,
                        zoneConfigBuilder.getZoneId(), zoneConfigBuilder.getZoneConfigId(),
                        groupId, groupName));
                groupId++;
            } else {
                skip(parser);
            }
        }
    }

    private CarVolumeGroup parseVolumeGroup(XmlPullParser parser, int zoneId, int configId,
            int groupId, String groupName) throws XmlPullParserException, IOException {
        CarVolumeGroupFactory groupFactory = new CarVolumeGroupFactory(mAudioManager,
                mCarAudioSettings, mCarAudioContext, zoneId, configId, groupId, groupName,
                mUseCarVolumeGroupMute);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_DEVICE.equals(parser.getName())) {
                String address = parser.getAttributeValue(NAMESPACE, ATTR_DEVICE_ADDRESS);
                validateOutputDeviceExist(address);
                parseVolumeGroupContexts(parser, groupFactory, address);
            } else {
                skip(parser);
            }
        }
        return groupFactory.getCarVolumeGroup(mUseCoreAudioVolume);
    }

    private void validateOutputDeviceExist(String address) {
        if (!mAddressToCarAudioDeviceInfo.containsKey(address)) {
            throw new IllegalStateException(String.format(
                    "Output device address %s does not belong to any configured output device.",
                    address));
        }
    }

    private void parseVolumeGroupContexts(
            XmlPullParser parser, CarVolumeGroupFactory groupFactory, String address)
            throws XmlPullParserException, IOException {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_CONTEXT.equals(parser.getName())) {
                @AudioContext int carAudioContextId = parseCarAudioContextId(
                        parser.getAttributeValue(NAMESPACE, ATTR_CONTEXT_NAME));
                validateCarAudioContextSupport(carAudioContextId);
                CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.get(address);
                groupFactory.setDeviceInfoForContext(carAudioContextId, info);

                // If V1, default new contexts to same device as DEFAULT_AUDIO_USAGE
                if (isVersionOne() && carAudioContextId == mCarAudioContext
                        .getContextForAudioAttribute(CAR_DEFAULT_AUDIO_ATTRIBUTE)) {
                    groupFactory.setNonLegacyContexts(info);
                }
            }
            // Always skip to upper level since we're at the lowest.
            skip(parser);
        }
    }

    private boolean isVersionLessThanThree() {
        return mCurrentVersion < SUPPORTED_VERSION_3;
    }

    private boolean isVersionOne() {
        return mCurrentVersion == SUPPORTED_VERSION_1;
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
                default:
                    break;
            }
        }
    }

    private @AudioContext int parseCarAudioContextId(String context) {
        return mContextNameToId.getOrDefault(context.toLowerCase(ROOT),
                CarAudioContext.getInvalidContext());
    }

    private void validateCarAudioContextSupport(@AudioContext int audioContext) {
        if (isVersionOne() && CarAudioContext.getCarSystemContextIds().contains(audioContext)) {
            throw new IllegalArgumentException(String.format(
                    "Non-legacy audio contexts such as %s are not supported in "
                            + "car_audio_configuration.xml version %d",
                    mCarAudioContext.toString(audioContext), SUPPORTED_VERSION_1));
        }
    }

    private int getNextSecondaryZoneId() {
        int zoneId = mNextSecondaryZoneId;
        mNextSecondaryZoneId += 1;
        return zoneId;
    }

    public CarAudioContext getCarAudioContext() {
        return mCarAudioContext;
    }

    public List<CarAudioDeviceInfo> getMirrorDeviceInfos() {
        return mMirroringDevices;
    }
}
