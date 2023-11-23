// Copyright 2016 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package android.net.http;

import static android.net.http.ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED;
import static android.net.http.ConnectionMigrationOptions.MIGRATION_OPTION_UNSPECIFIED;
import static android.net.http.DnsOptions.DNS_OPTION_ENABLED;
import static android.net.http.DnsOptions.DNS_OPTION_UNSPECIFIED;

import android.content.Context;

import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * {@link HttpEngine} that exposes experimental features.
 *
 * <p>{@hide since this class exposes experimental features that should be hidden.}
 *
 * @deprecated scheduled for deletion, don't use in new code.
 */
@Deprecated
public abstract class ExperimentalHttpEngine extends HttpEngine {
    /**
     * The value of a connection metric is unknown.
     */
    public static final int CONNECTION_METRIC_UNKNOWN = -1;

    /**
     * The estimate of the effective connection type is unknown.
     *
     * @see #getEffectiveConnectionType
     */
    public static final int EFFECTIVE_CONNECTION_TYPE_UNKNOWN = 0;

    /**
     * The device is offline.
     *
     * @see #getEffectiveConnectionType
     */
    public static final int EFFECTIVE_CONNECTION_TYPE_OFFLINE = 1;

    /**
     * The estimate of the effective connection type is slow 2G.
     *
     * @see #getEffectiveConnectionType
     */
    public static final int EFFECTIVE_CONNECTION_TYPE_SLOW_2G = 2;

    /**
     * The estimate of the effective connection type is 2G.
     *
     * @see #getEffectiveConnectionType
     */
    public static final int EFFECTIVE_CONNECTION_TYPE_2G = 3;

    /**
     * The estimate of the effective connection type is 3G.
     *
     * @see #getEffectiveConnectionType
     */
    public static final int EFFECTIVE_CONNECTION_TYPE_3G = 4;

    /**
     * The estimate of the effective connection type is 4G.
     *
     * @see #getEffectiveConnectionType
     */
    public static final int EFFECTIVE_CONNECTION_TYPE_4G = 5;

    /** The value to be used to undo any previous network binding. */
    public static final long UNBIND_NETWORK_HANDLE = -1;

    /**
     * A version of {@link HttpEngine.Builder} that exposes experimental features. Instances of
     * this class are not meant for general use, but instead only to access experimental features.
     * Experimental features may be deprecated in the future. Use at your own risk.
     */
    public static class Builder extends HttpEngine.Builder {
        /**
         * Constructs a {@link Builder} object that facilitates creating a {@link HttpEngine}. The
         * default configuration enables HTTP/2 and disables QUIC, SDCH and the HTTP cache.
         *
         * @param context Android {@link Context}, which is used by the Builder to retrieve the
         *     application context. A reference to only the application context will be kept, so as
         * to avoid extending the lifetime of {@code context} unnecessarily.
         */
        public Builder(Context context) {
            super(context);
        }

        /**
         * Constructs {@link Builder} with a given delegate that provides the actual implementation
         * of the {@code Builder} methods. This constructor is used only by the internal
         * implementation.
         *
         * @param builderDelegate delegate that provides the actual implementation.
         *     <p>{@hide}
         */
        public Builder(IHttpEngineBuilder builderDelegate) {
            super(builderDelegate);
        }

        /**
         * Sets experimental options to be used.
         *
         * @param options JSON formatted experimental options.
         * @return the builder to facilitate chaining.
         */
        public Builder setExperimentalOptions(String options) {
            mBuilderDelegate.setExperimentalOptions(options);
            return this;
        }

        /**
         * Enables the network quality estimator, which collects and reports
         * measurements of round trip time (RTT) and downstream throughput at
         * various layers of the network stack. After enabling the estimator,
         * listeners of RTT and throughput can be added with
         * {@link #addRttListener} and {@link #addThroughputListener} and
         * removed with {@link #removeRttListener} and
         * {@link #removeThroughputListener}. The estimator uses memory and CPU
         * only when enabled.
         * @param value {@code true} to enable network quality estimator,
         *            {@code false} to disable.
         * @return the builder to facilitate chaining.
         */
        public Builder enableNetworkQualityEstimator(boolean value) {
            mBuilderDelegate.enableNetworkQualityEstimator(value);
            return this;
        }

        /**
         * Sets the thread priority of the internal thread.
         *
         * @param priority the thread priority of the internal thread.
         *        A Linux priority level, from -20 for highest scheduling
         *        priority to 19 for lowest scheduling priority. For more
         *        information on values, see
         *        {@link android.os.Process#setThreadPriority(int, int)} and
         *        {@link android.os.Process#THREAD_PRIORITY_DEFAULT
         *        THREAD_PRIORITY_*} values.
         * @return the builder to facilitate chaining.
         */
        public Builder setThreadPriority(int priority) {
            mBuilderDelegate.setThreadPriority(priority);
            return this;
        }

        /**
         * Returns delegate, only for testing.
         *
         * @hide
         */
        @VisibleForTesting
        public IHttpEngineBuilder getBuilderDelegate() {
            return mBuilderDelegate;
        }

        // To support method chaining, override superclass methods to return an
        // instance of this class instead of the parent.

        @Override
        public Builder setUserAgent(String userAgent) {
            super.setUserAgent(userAgent);
            return this;
        }

        @Override
        public Builder setStoragePath(String value) {
            super.setStoragePath(value);
            return this;
        }

        @Override
        public Builder setEnableQuic(boolean value) {
            super.setEnableQuic(value);
            return this;
        }

        @Override
        public Builder setEnableHttp2(boolean value) {
            super.setEnableHttp2(value);
            return this;
        }

        @Override
        @QuicOptions.Experimental
        public Builder setQuicOptions(QuicOptions options) {
            super.setQuicOptions(options);
            return this;
        }

        @Override
        @DnsOptions.Experimental
        public Builder setDnsOptions(DnsOptions options) {
            super.setDnsOptions(options);
            return this;
        }

        @Override
        @ConnectionMigrationOptions.Experimental
        public Builder setConnectionMigrationOptions(ConnectionMigrationOptions options) {
            super.setConnectionMigrationOptions(options);
            return this;
        }

        @Override
        public Builder setEnableHttpCache(int cacheMode, long maxSize) {
            super.setEnableHttpCache(cacheMode, maxSize);
            return this;
        }

        @Override
        public Builder addQuicHint(String host, int port, int alternatePort) {
            super.addQuicHint(host, port, alternatePort);
            return this;
        }

        @Override
        public Builder addPublicKeyPins(String hostName, Set<byte[]> pinsSha256,
                boolean includeSubdomains, Instant expirationInstant) {
            super.addPublicKeyPins(hostName, pinsSha256, includeSubdomains, expirationInstant);
            return this;
        }

        @Override
        public Builder setEnablePublicKeyPinningBypassForLocalTrustAnchors(boolean value) {
            super.setEnablePublicKeyPinningBypassForLocalTrustAnchors(value);
            return this;
        }

        @Override
        public ExperimentalHttpEngine build() {
            return mBuilderDelegate.build();
        }
    }

    @Override
    public abstract ExperimentalBidirectionalStream.Builder newBidirectionalStreamBuilder(
            String url, Executor executor, BidirectionalStream.Callback callback);

    @Override
    public abstract ExperimentalUrlRequest.Builder newUrlRequestBuilder(
            String url, Executor executor, UrlRequest.Callback callback);

    /**
     * Starts NetLog logging to a specified directory with a bounded size. The NetLog will contain
     * events emitted by all live CronetEngines. The NetLog is useful for debugging.
     * Once logging has stopped {@link #stopNetLog}, the data will be written
     * to netlog.json in {@code dirPath}. If logging is interrupted, you can
     * stitch the files found in .inprogress subdirectory manually using:
     * https://chromium.googlesource.com/chromium/src/+/main/net/tools/stitch_net_log_files.py.
     * The log can be viewed using a Chrome browser navigated to chrome://net-internals/#import.
     * @param dirPath the directory where the netlog.json file will be created. dirPath must
     *            already exist. NetLog files must not exist in the directory. If actively
     *            logging, this method is ignored.
     * @param logAll {@code true} to include basic events, user cookies,
     *            credentials and all transferred bytes in the log. This option presents a
     *            privacy risk, since it exposes the user's credentials, and should only be
     *            used with the user's consent and in situations where the log won't be public.
     *            {@code false} to just include basic events.
     * @param maxSize the maximum total disk space in bytes that should be used by NetLog. Actual
     *            disk space usage may exceed this limit slightly.
     */
    public void startNetLogToDisk(String dirPath, boolean logAll, int maxSize) {}

    /**
     * Returns an estimate of the effective connection type computed by the network quality
     * estimator. Call {@link Builder#enableNetworkQualityEstimator} to begin computing this
     * value.
     *
     * @return the estimated connection type. The returned value is one of
     * {@link #EFFECTIVE_CONNECTION_TYPE_UNKNOWN EFFECTIVE_CONNECTION_TYPE_* }.
     */
    public int getEffectiveConnectionType() {
        return EFFECTIVE_CONNECTION_TYPE_UNKNOWN;
    }

    /**
     * Configures the network quality estimator for testing. This must be called
     * before round trip time and throughput listeners are added, and after the
     * network quality estimator has been enabled.
     * @param useLocalHostRequests include requests to localhost in estimates.
     * @param useSmallerResponses include small responses in throughput estimates.
     * @param disableOfflineCheck when set to true, disables the device offline checks when
     *        computing the effective connection type or when writing the prefs.
     */
    public void configureNetworkQualityEstimatorForTesting(boolean useLocalHostRequests,
            boolean useSmallerResponses, boolean disableOfflineCheck) {}

    /**
     * Registers a listener that gets called whenever the network quality
     * estimator witnesses a sample round trip time. This must be called
     * after {@link Builder#enableNetworkQualityEstimator}, and with throw an
     * exception otherwise. Round trip times may be recorded at various layers
     * of the network stack, including TCP, QUIC, and at the URL request layer.
     * The listener is called on the {@link java.util.concurrent.Executor} that
     * is passed to {@link Builder#enableNetworkQualityEstimator}.
     * @param listener the listener of round trip times.
     */
    public void addRttListener(NetworkQualityRttListener listener) {}

    /**
     * Removes a listener of round trip times if previously registered with
     * {@link #addRttListener}. This should be called after a
     * {@link NetworkQualityRttListener} is added in order to stop receiving
     * observations.
     * @param listener the listener of round trip times.
     */
    public void removeRttListener(NetworkQualityRttListener listener) {}

    /**
     * Registers a listener that gets called whenever the network quality
     * estimator witnesses a sample throughput measurement. This must be called
     * after {@link Builder#enableNetworkQualityEstimator}. Throughput observations
     * are computed by measuring bytes read over the active network interface
     * at times when at least one URL response is being received. The listener
     * is called on the {@link java.util.concurrent.Executor} that is passed to
     * {@link Builder#enableNetworkQualityEstimator}.
     * @param listener the listener of throughput.
     */
    public void addThroughputListener(NetworkQualityThroughputListener listener) {}

    /**
     * Removes a listener of throughput. This should be called after a
     * {@link NetworkQualityThroughputListener} is added with
     * {@link #addThroughputListener} in order to stop receiving observations.
     * @param listener the listener of throughput.
     */
    public void removeThroughputListener(NetworkQualityThroughputListener listener) {}

    /**
     * Establishes a new connection to the resource specified by the {@link URL} {@code url}
     * using the given proxy.
     * <p>
     * <b>Note:</b> this {@link java.net.HttpURLConnection} implementation is subject to certain
     * limitations, see {@link #createUrlStreamHandlerFactory} for details.
     *
     * @param url URL of resource to connect to.
     * @param proxy proxy to use when establishing connection.
     * @return an {@link java.net.HttpURLConnection} instance implemented by this HttpEngine.
     * @throws IOException if an error occurs while opening the connection.
     */
    // TODO(pauljensen): Expose once implemented, http://crbug.com/418111
    public URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        return url.openConnection(proxy);
    }

    /**
     * Registers a listener that gets called after the end of each request with the request info.
     *
     * <p>The listener is called on an {@link java.util.concurrent.Executor} provided by the
     * listener.
     *
     * @param listener the listener for finished requests.
     */
    public void addRequestFinishedListener(RequestFinishedInfo.Listener listener) {}

    /**
     * Removes a finished request listener.
     *
     * @param listener the listener to remove.
     */
    public void removeRequestFinishedListener(RequestFinishedInfo.Listener listener) {}

    /**
     * Returns the HTTP RTT estimate (in milliseconds) computed by the network
     * quality estimator. Set to {@link #CONNECTION_METRIC_UNKNOWN} if the value
     * is unavailable. This must be called after
     * {@link Builder#enableNetworkQualityEstimator}, and will throw an
     * exception otherwise.
     * @return Estimate of the HTTP RTT in milliseconds.
     */
    public int getHttpRttMs() {
        return CONNECTION_METRIC_UNKNOWN;
    }

    /**
     * Returns the transport RTT estimate (in milliseconds) computed by the
     * network quality estimator. Set to {@link #CONNECTION_METRIC_UNKNOWN} if
     * the value is unavailable. This must be called after
     * {@link Builder#enableNetworkQualityEstimator}, and will throw an
     * exception otherwise.
     * @return Estimate of the transport RTT in milliseconds.
     */
    public int getTransportRttMs() {
        return CONNECTION_METRIC_UNKNOWN;
    }

    /**
     * Returns the downstream throughput estimate (in kilobits per second)
     * computed by the network quality estimator. Set to
     * {@link #CONNECTION_METRIC_UNKNOWN} if the value is
     * unavailable. This must be called after
     * {@link Builder#enableNetworkQualityEstimator}, and will
     * throw an exception otherwise.
     * @return Estimate of the downstream throughput in kilobits per second.
     */
    public int getDownstreamThroughputKbps() {
        return CONNECTION_METRIC_UNKNOWN;
    }
}
