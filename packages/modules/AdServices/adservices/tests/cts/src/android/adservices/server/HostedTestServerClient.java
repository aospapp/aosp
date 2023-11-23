package android.adservices.server;

import android.net.Uri;
import android.util.Log;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

class HostedTestServerClient {
    private static final String TAG = "HostedTestServerClient";
    private URL mBaseUrl;
    private String mSessionId;

    public HostedTestServerClient(Uri baseUri, String sessionId) throws MalformedURLException {
        mBaseUrl = new URL("https", baseUri.getHost(), "");
        mSessionId = sessionId;
    }

    void clearAllMocks() throws IOException {
        String pathPrefix = String.format("/%s", mSessionId);
        URL url = new URL("https", mBaseUrl.getHost(), "/_/ClearAllMocks");

        String urlEncodedData = String.format("path=%s", encodeParam(pathPrefix));
        makeRequest(url, urlEncodedData);
    }

    void setMock(MatchingHttpRequest request, MockHttpResponse response) throws IOException {
        URL url = new URL("https", mBaseUrl.getHost(), "/_/SetMock");
        if (!request.getUri().getPath().contains(mSessionId)) {
            throw new UnsupportedOperationException("mock path should contain session id.");
        }
        String urlEncodedData =
                String.format(
                        "path=/%s&response_body=%s",
                        encodeParam(request.getUri().getPath()), encodeParam(response.getBody()));
        makeRequest(url, urlEncodedData);
    }

    private String encodeParam(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
    }

    public boolean isValid() {
        return mBaseUrl.getProtocol().equals("https") && mBaseUrl.getPath().equals("");
    }

    private static int makeRequest(URL url, String urlEncodedData) throws IOException {
        byte[] data = urlEncodedData.getBytes(StandardCharsets.UTF_8);

        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Content-Length", Integer.toString(data.length));
        conn.setRequestProperty("charset", "utf-8");
        conn.setUseCaches(false);
        try (DataOutputStream wr = new DataOutputStream(conn.getOutputStream())) {
            wr.write(data);
        }

        int status = conn.getResponseCode();
        Log.v(
                TAG,
                String.format(
                        "[HTTP %s] %s (%s)",
                        status,
                        url.getPath(),
                        urlEncodedData.length() > 100
                                ? urlEncodedData.substring(0, 100)
                                : urlEncodedData));
        return status;
    }
}
