/*
 * Copyright (C) 2012 Square, Inc.
 * Copyright (C) 2012 The Android Open Source Project
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
package com.squareup.okhttp.internal;

import dalvik.system.SocketTagger;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.net.ssl.SSLSocket;

import com.android.org.conscrypt.OpenSSLSocketImpl;
import com.squareup.okhttp.Protocol;
import okio.ByteString;

/**
 * Access to proprietary Android APIs. Doesn't use reflection.
 */
public final class Platform {
    private static final Platform PLATFORM = new Platform();

    public static Platform get() {
        return PLATFORM;
    }

    public void logW(String warning) {
        System.logW(warning);
    }

    public void tagSocket(Socket socket) throws SocketException {
        SocketTagger.get().tag(socket);
    }

    public void untagSocket(Socket socket) throws SocketException {
        SocketTagger.get().untag(socket);
    }

    public URI toUriLenient(URL url) throws URISyntaxException {
        return url.toURILenient();
    }

    public void enableTlsExtensions(SSLSocket socket, String uriHost) {
        if (socket instanceof OpenSSLSocketImpl) {
            OpenSSLSocketImpl openSSLSocket = (OpenSSLSocketImpl) socket;
            openSSLSocket.setUseSessionTickets(true);
            openSSLSocket.setHostname(uriHost);
        }
    }

    public void supportTlsIntolerantServer(SSLSocket socket) {
        // In accordance with https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00
        // the SCSV cipher is added to signal that a protocol fallback has taken place.
        final String fallbackScsv = "TLS_FALLBACK_SCSV";
        boolean socketSupportsFallbackScsv = false;
        String[] supportedCipherSuites = socket.getSupportedCipherSuites();
        for (int i = supportedCipherSuites.length - 1; i >= 0; i--) {
            String supportedCipherSuite = supportedCipherSuites[i];
            if (fallbackScsv.equals(supportedCipherSuite)) {
                socketSupportsFallbackScsv = true;
                break;
            }
        }
        if (socketSupportsFallbackScsv) {
            // Add the SCSV cipher to the set of enabled ciphers.
            String[] enabledCipherSuites = socket.getEnabledCipherSuites();
            String[] newEnabledCipherSuites = new String[enabledCipherSuites.length + 1];
            System.arraycopy(enabledCipherSuites, 0,
                    newEnabledCipherSuites, 0, enabledCipherSuites.length);
            newEnabledCipherSuites[newEnabledCipherSuites.length - 1] = fallbackScsv;
            socket.setEnabledCipherSuites(newEnabledCipherSuites);
        }
        socket.setEnabledProtocols(new String[]{"SSLv3"});
    }

    /**
     * Returns the negotiated protocol, or null if no protocol was negotiated.
     */
    public ByteString getNpnSelectedProtocol(SSLSocket socket) {
        if (!(socket instanceof OpenSSLSocketImpl)) {
            return null;
        }

        OpenSSLSocketImpl socketImpl = (OpenSSLSocketImpl) socket;
        // Prefer ALPN's result if it is present.
        byte[] alpnResult = socketImpl.getAlpnSelectedProtocol();
        if (alpnResult != null) {
            return ByteString.of(alpnResult);
        }
        byte[] npnResult = socketImpl.getNpnSelectedProtocol();
        return npnResult == null ? null : ByteString.of(npnResult);
    }

    /**
     * Sets client-supported protocols on a socket to send to a server. The
     * protocols are only sent if the socket implementation supports NPN.
     */
    public void setNpnProtocols(SSLSocket socket, List<Protocol> npnProtocols) {
        if (socket instanceof OpenSSLSocketImpl) {
            OpenSSLSocketImpl socketImpl = (OpenSSLSocketImpl) socket;
            byte[] protocols = concatLengthPrefixed(npnProtocols);
            socketImpl.setAlpnProtocols(protocols);
            socketImpl.setNpnProtocols(protocols);
        }
    }

    /**
     * Returns a deflater output stream that supports SYNC_FLUSH for SPDY name
     * value blocks. This throws an {@link UnsupportedOperationException} on
     * Java 6 and earlier where there is no built-in API to do SYNC_FLUSH.
     */
    public OutputStream newDeflaterOutputStream(
            OutputStream out, Deflater deflater, boolean syncFlush) {
        return new DeflaterOutputStream(out, deflater, syncFlush);
    }

    public void connectSocket(Socket socket, InetSocketAddress address,
              int connectTimeout) throws IOException {
        socket.connect(address, connectTimeout);
    }

    /** Prefix used on custom headers. */
    public String getPrefix() {
        return "X-Android";
    }

    /**
     * Concatenation of 8-bit, length prefixed protocol names.
     *
     * http://tools.ietf.org/html/draft-agl-tls-nextprotoneg-04#page-4
     */
    static byte[] concatLengthPrefixed(List<Protocol> protocols) {
        int size = 0;
        for (Protocol protocol : protocols) {
            size += protocol.name.size() + 1; // add a byte for 8-bit length prefix.
        }
        byte[] result = new byte[size];
        int pos = 0;
        for (Protocol protocol : protocols) {
            int nameSize = protocol.name.size();
            result[pos++] = (byte) nameSize;
            // toByteArray allocates an array, but this is only called on new connections.
            System.arraycopy(protocol.name.toByteArray(), 0, result, pos, nameSize);
            pos += nameSize;
        }
        return result;
    }
}
