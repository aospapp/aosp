// Copyright 2016 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package android.net.http;

import static android.net.http.ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED;
import static android.net.http.ConnectionMigrationOptions.MIGRATION_OPTION_UNSPECIFIED;
import static android.net.http.DnsOptions.DNS_OPTION_ENABLED;
import static android.net.http.DnsOptions.DNS_OPTION_UNSPECIFIED;

import android.net.http.DnsOptions.StaleDnsOptions;

import androidx.annotation.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An implementation of IHttpEngineBuilder which handles translation of configuration options to
 * json-based experimental options, if necessary.
 *
 * <p>{@hide internal class}
 */
public final class ExperimentalOptionsTranslatingHttpEngineBuilder extends IHttpEngineBuilder {
    private static final Set<Integer> SUPPORTED_OPTIONS = Collections.unmodifiableSet(
            new HashSet(Arrays.asList(IHttpEngineBuilder.CONNECTION_MIGRATION_OPTIONS,
                    IHttpEngineBuilder.DNS_OPTIONS, IHttpEngineBuilder.QUIC_OPTIONS)));

    private JSONObject mParsedExperimentalOptions;
    private final List<ExperimentalOptionsPatch> mExperimentalOptionsPatches = new ArrayList<>();

    private final IHttpEngineBuilder mDelegate;

    ExperimentalOptionsTranslatingHttpEngineBuilder(IHttpEngineBuilder delegate) {
        this.mDelegate = delegate;
    }

    @Override
    public IHttpEngineBuilder setQuicOptions(QuicOptions options) {
        // If the delegate builder supports enabling connection migration directly, just use it
        if (mDelegate.getSupportedConfigOptions().contains(IHttpEngineBuilder.QUIC_OPTIONS)) {
            mDelegate.setQuicOptions(options);
            return this;
        }

        // If not, we'll have to work around it by modifying the experimental options JSON.
        mExperimentalOptionsPatches.add((experimentalOptions) -> {
            JSONObject quicOptions = createDefaultIfAbsent(experimentalOptions, "QUIC");

            // Note: using the experimental APIs always overwrites what's in the experimental
            // JSON, even though "repeated" fields could in theory be additive.
            if (!options.getAllowedQuicHosts().isEmpty()) {
                quicOptions.put("host_whitelist", String.join(",", options.getAllowedQuicHosts()));
            }
            if (!options.getEnabledQuicVersions().isEmpty()) {
                quicOptions.put("quic_version", String.join(",", options.getEnabledQuicVersions()));
            }
            if (!options.getConnectionOptions().isEmpty()) {
                quicOptions.put(
                        "connection_options", String.join(",", options.getConnectionOptions()));
            }
            if (!options.getClientConnectionOptions().isEmpty()) {
                quicOptions.put("client_connection_options",
                        String.join(",", options.getClientConnectionOptions()));
            }
            if (!options.getExtraQuicheFlags().isEmpty()) {
                quicOptions.put("set_quic_flags", String.join(",", options.getExtraQuicheFlags()));
            }

            if (options.hasInMemoryServerConfigsCacheSize()) {
                quicOptions.put("max_server_configs_stored_in_properties",
                        options.getInMemoryServerConfigsCacheSize());
            }

            if (options.getHandshakeUserAgent() != null) {
                quicOptions.put("user_agent_id", options.getHandshakeUserAgent());
            }

            if (options.getRetryWithoutAltSvcOnQuicErrors() != null) {
                quicOptions.put("retry_without_alt_svc_on_quic_errors",
                        options.getRetryWithoutAltSvcOnQuicErrors());
            }

            if (options.getEnableTlsZeroRtt() != null) {
                quicOptions.put("disable_tls_zero_rtt", !options.getEnableTlsZeroRtt());
            }

            if (options.getPreCryptoHandshakeIdleTimeout() != null) {
                quicOptions.put("max_idle_time_before_crypto_handshake_seconds",
                        options.getPreCryptoHandshakeIdleTimeout().toSeconds());
            }

            if (options.getCryptoHandshakeTimeout() != null) {
                quicOptions.put("max_time_before_crypto_handshake_seconds",
                        options.getCryptoHandshakeTimeout().toSeconds());
            }

            if (options.getIdleConnectionTimeout() != null) {
                quicOptions.put("idle_connection_timeout_seconds",
                        options.getIdleConnectionTimeout().toSeconds());
            }

            if (options.getRetransmittableOnWireTimeout() != null) {
                quicOptions.put("retransmittable_on_wire_timeout_milliseconds",
                        options.getRetransmittableOnWireTimeout().toMillis());
            }

            if (options.getCloseSessionsOnIpChange() != null) {
                quicOptions.put(
                        "close_sessions_on_ip_change", options.getCloseSessionsOnIpChange());
            }

            if (options.getGoawaySessionsOnIpChange() != null) {
                quicOptions.put(
                        "goaway_sessions_on_ip_change", options.getGoawaySessionsOnIpChange());
            }

            if (options.getInitialBrokenServicePeriod() != null) {
                quicOptions.put("initial_delay_for_broken_alternative_service_seconds",
                        options.getInitialBrokenServicePeriod().toSeconds());
            }

            if (options.getIncreaseBrokenServicePeriodExponentially() != null) {
                quicOptions.put("exponential_backoff_on_initial_delay",
                        options.getIncreaseBrokenServicePeriodExponentially());
            }

            if (options.getDelayJobsWithAvailableSpdySession() != null) {
                quicOptions.put("delay_main_job_with_available_spdy_session",
                        options.getDelayJobsWithAvailableSpdySession());
            }
        });
        return this;
    }

    @Override
    public IHttpEngineBuilder setDnsOptions(DnsOptions options) {
        // If the delegate builder supports enabling connection migration directly, just use it
        if (mDelegate.getSupportedConfigOptions().contains(IHttpEngineBuilder.DNS_OPTIONS)) {
            mDelegate.setDnsOptions(options);
            return this;
        }

        // If not, we'll have to work around it by modifying the experimental options JSON.
        mExperimentalOptionsPatches.add((experimentalOptions) -> {
            JSONObject asyncDnsOptions = createDefaultIfAbsent(experimentalOptions, "AsyncDNS");

            if (options.getUseHttpStackDnsResolver() != DNS_OPTION_UNSPECIFIED) {
                asyncDnsOptions.put("enable",
                        options.getUseHttpStackDnsResolver() == DNS_OPTION_ENABLED);
            }

            JSONObject staleDnsOptions = createDefaultIfAbsent(experimentalOptions, "StaleDNS");

            if (options.getStaleDns() != DNS_OPTION_UNSPECIFIED) {
                staleDnsOptions.put("enable", options.getStaleDns() == DNS_OPTION_ENABLED);
            }

            if (options.getPersistHostCache() != DNS_OPTION_UNSPECIFIED) {
                staleDnsOptions.put("persist_to_disk",
                        options.getPersistHostCache() == DNS_OPTION_ENABLED);
            }

            if (options.getPersistHostCachePeriod() != null) {
                staleDnsOptions.put("persist_delay_ms",
                        options.getPersistHostCachePeriod().toMillis());
            }

            if (options.getStaleDnsOptions() != null) {
                StaleDnsOptions staleDnsOptionsJava = options.getStaleDnsOptions();

                if (staleDnsOptionsJava.getAllowCrossNetworkUsage() != DNS_OPTION_UNSPECIFIED) {
                    staleDnsOptions.put("allow_other_network",
                            staleDnsOptionsJava.getAllowCrossNetworkUsage()
                                    == DNS_OPTION_ENABLED);
                }

                if (staleDnsOptionsJava.getFreshLookupTimeout() != null) {
                    staleDnsOptions.put(
                            "delay_ms", staleDnsOptionsJava.getFreshLookupTimeout().toMillis());
                }

                if (staleDnsOptionsJava.getUseStaleOnNameNotResolved() != DNS_OPTION_UNSPECIFIED) {
                    staleDnsOptions.put("use_stale_on_name_not_resolved",
                            staleDnsOptionsJava.getUseStaleOnNameNotResolved()
                                    == DNS_OPTION_ENABLED);
                }

                if (staleDnsOptionsJava.getMaxExpiredDelay() != null) {
                    staleDnsOptions.put("max_expired_time_ms",
                            staleDnsOptionsJava.getMaxExpiredDelay().toMillis());
                }
            }

            JSONObject quicOptions = createDefaultIfAbsent(experimentalOptions, "QUIC");
            if (options.getPreestablishConnectionsToStaleDnsResults() != DNS_OPTION_UNSPECIFIED) {
                quicOptions.put("race_stale_dns_on_connection",
                        options.getPreestablishConnectionsToStaleDnsResults()
                                == DNS_OPTION_ENABLED);
            }
        });
        return this;
    }

    @Override
    public IHttpEngineBuilder setConnectionMigrationOptions(ConnectionMigrationOptions options) {
        // If the delegate builder supports enabling connection migration directly, just use it
        if (mDelegate.getSupportedConfigOptions().contains(
                    IHttpEngineBuilder.CONNECTION_MIGRATION_OPTIONS)) {
            mDelegate.setConnectionMigrationOptions(options);
            return this;
        }

        // If not, we'll have to work around it by modifying the experimental options JSON.
        mExperimentalOptionsPatches.add((experimentalOptions) -> {
            JSONObject quicOptions = createDefaultIfAbsent(experimentalOptions, "QUIC");

            if (options.getDefaultNetworkMigration() != MIGRATION_OPTION_UNSPECIFIED) {
                quicOptions.put("migrate_sessions_on_network_change_v2",
                        options.getDefaultNetworkMigration()
                                == MIGRATION_OPTION_ENABLED);
            }
            if (options.getAllowServerMigration() != null) {
                quicOptions.put("allow_server_migration", options.getAllowServerMigration());
            }
            if (options.getMigrateIdleConnections() != null) {
                quicOptions.put("migrate_idle_sessions", options.getMigrateIdleConnections());
            }
            if (options.getIdleMigrationPeriod() != null) {
                quicOptions.put("idle_session_migration_period_seconds",
                        options.getIdleMigrationPeriod().toSeconds());
            }
            if (options.getMaxTimeOnNonDefaultNetwork() != null) {
                quicOptions.put("max_time_on_non_default_network_seconds",
                        options.getMaxTimeOnNonDefaultNetwork().toSeconds());
            }
            if (options.getMaxPathDegradingNonDefaultMigrationsCount() != null) {
                quicOptions.put("max_migrations_to_non_default_network_on_path_degrading",
                        options.getMaxPathDegradingNonDefaultMigrationsCount());
            }
            if (options.getMaxWriteErrorNonDefaultNetworkMigrationsCount() != null) {
                quicOptions.put("max_migrations_to_non_default_network_on_write_error",
                        options.getMaxWriteErrorNonDefaultNetworkMigrationsCount());
            }
            if (options.getPathDegradationMigration() != MIGRATION_OPTION_UNSPECIFIED) {
                boolean pathDegradationValue = options.getPathDegradationMigration()
                        == MIGRATION_OPTION_ENABLED;

                boolean skipPortMigrationFlag = false;

                if (options.getAllowNonDefaultNetworkUsage() != MIGRATION_OPTION_UNSPECIFIED) {
                    boolean nonDefaultNetworkValue =
                            (options.getAllowNonDefaultNetworkUsage()
                                    == MIGRATION_OPTION_ENABLED);
                    if (!pathDegradationValue && nonDefaultNetworkValue) {
                        // Misconfiguration which doesn't translate easily to the JSON flags
                        throw new IllegalArgumentException(
                                "Unable to turn on non-default network usage without path "
                                + "degradation migration!");
                    } else if (pathDegradationValue && nonDefaultNetworkValue) {
                        // Both values being true results in the non-default network migration
                        // being enabled.
                        quicOptions.put("migrate_sessions_early_v2", true);
                        skipPortMigrationFlag = true;
                    } else {
                        quicOptions.put("migrate_sessions_early_v2", false);
                    }
                }

                if (!skipPortMigrationFlag) {
                    quicOptions.put("allow_port_migration", pathDegradationValue);
                }
            }
        });

        return this;
    }

    @Override
    public IHttpEngineBuilder setExperimentalOptions(String options) {
        if (options == null || options.isEmpty()) {
            mParsedExperimentalOptions = null;
        } else {
            mParsedExperimentalOptions = parseExperimentalOptions(options);
        }
        return this;
    }

    @Override
    protected Set<Integer> getSupportedConfigOptions() {
        return SUPPORTED_OPTIONS;
    }

    @Override
    public ExperimentalHttpEngine build() {
        if (mParsedExperimentalOptions == null && mExperimentalOptionsPatches.isEmpty()) {
            return mDelegate.build();
        }

        if (mParsedExperimentalOptions == null) {
            mParsedExperimentalOptions = new JSONObject();
        }

        for (ExperimentalOptionsPatch patch : mExperimentalOptionsPatches) {
            try {
                patch.applyTo(mParsedExperimentalOptions);
            } catch (JSONException e) {
                throw new IllegalStateException("Unable to apply JSON patch!", e);
            }
        }

        mDelegate.setExperimentalOptions(mParsedExperimentalOptions.toString());
        return mDelegate.build();
    }

    private static JSONObject parseExperimentalOptions(String jsonString) {
        try {
            return new JSONObject(jsonString);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Experimental options parsing failed", e);
        }
    }

    private static JSONObject createDefaultIfAbsent(JSONObject jsonObject, String key) {
        JSONObject object = jsonObject.optJSONObject(key);
        if (object == null) {
            object = new JSONObject();
            try {
                jsonObject.put(key, object);
            } catch (JSONException e) {
                throw new IllegalArgumentException(
                        "Failed adding a default object for key [" + key + "]", e);
            }
        }

        return object;
    }

    @VisibleForTesting
    public IHttpEngineBuilder getDelegate() {
        return mDelegate;
    }

    @FunctionalInterface
    private interface ExperimentalOptionsPatch {
        void applyTo(JSONObject experimentalOptions) throws JSONException;
    }

    // Delegating-only methods
    @Override
    public IHttpEngineBuilder addPublicKeyPins(String hostName, Set<byte[]> pinsSha256,
            boolean includeSubdomains, Instant expirationDate) {
        mDelegate.addPublicKeyPins(hostName, pinsSha256, includeSubdomains, expirationDate);
        return this;
    }

    @Override
    public IHttpEngineBuilder addQuicHint(String host, int port, int alternatePort) {
        mDelegate.addQuicHint(host, port, alternatePort);
        return this;
    }

    @Override
    public IHttpEngineBuilder enableHttp2(boolean value) {
        mDelegate.enableHttp2(value);
        return this;
    }

    @Override
    public IHttpEngineBuilder enableHttpCache(int cacheMode, long maxSize) {
        mDelegate.enableHttpCache(cacheMode, maxSize);
        return this;
    }

    @Override
    public IHttpEngineBuilder enablePublicKeyPinningBypassForLocalTrustAnchors(boolean value) {
        mDelegate.enablePublicKeyPinningBypassForLocalTrustAnchors(value);
        return this;
    }

    @Override
    public IHttpEngineBuilder enableQuic(boolean value) {
        mDelegate.enableQuic(value);
        return this;
    }

    @Override
    public IHttpEngineBuilder enableSdch(boolean value) {
        mDelegate.enableSdch(value);
        return this;
    }

    @Override
    public IHttpEngineBuilder enableBrotli(boolean value) {
        mDelegate.enableBrotli(value);
        return this;
    }

    @Override
    public IHttpEngineBuilder setStoragePath(String value) {
        mDelegate.setStoragePath(value);
        return this;
    }

    @Override
    public IHttpEngineBuilder setUserAgent(String userAgent) {
        mDelegate.setUserAgent(userAgent);
        return this;
    }

    @Override
    public String getDefaultUserAgent() {
        return mDelegate.getDefaultUserAgent();
    }

    @Override
    public IHttpEngineBuilder enableNetworkQualityEstimator(boolean value) {
        mDelegate.enableNetworkQualityEstimator(value);
        return this;
    }

    @Override
    public IHttpEngineBuilder setThreadPriority(int priority) {
        mDelegate.setThreadPriority(priority);
        return this;
    }
}
