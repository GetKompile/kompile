/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.staging.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Authentication provider for AWS S3 using Signature Version 4.
 * Supports S3 and S3-compatible storage (MinIO, etc.).
 */
@Component
public class S3AuthProvider implements ArchiveAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(S3AuthProvider.class);

    private static final String AWS_ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    @Value("${kompile.archive.s3.access-key:}")
    private String accessKey;

    @Value("${kompile.archive.s3.secret-key:}")
    private String secretKey;

    @Value("${kompile.archive.s3.region:us-east-1}")
    private String region;

    @Override
    public String getName() {
        return "s3";
    }

    @Override
    public int getPriority() {
        return 75; // Between bearer and basic
    }

    @Override
    public boolean supportsUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // S3 and S3-compatible URLs
        return lower.contains(".s3.") ||
               lower.contains("s3.amazonaws.com") ||
               lower.contains(".s3-") ||
               lower.contains("minio") ||
               lower.contains(":9000"); // Common MinIO port
    }

    @Override
    public boolean isConfigured() {
        return accessKey != null && !accessKey.isEmpty() &&
               secretKey != null && !secretKey.isEmpty();
    }

    @Override
    public Map<String, String> getAuthHeaders(String url) {
        return getAuthHeaders(url, "GET", new HashMap<>());
    }

    @Override
    public Map<String, String> getAuthHeaders(String url, String method, Map<String, String> existingHeaders) {
        if (!isConfigured()) {
            return new HashMap<>();
        }

        try {
            return signRequest(url, method, existingHeaders);
        } catch (Exception e) {
            log.error("Failed to sign S3 request", e);
            return new HashMap<>();
        }
    }

    /**
     * Sign an S3 request using AWS Signature Version 4.
     */
    private Map<String, String> signRequest(String url, String method, Map<String, String> existingHeaders)
            throws Exception {
        URI uri = new URI(url);
        String host = uri.getHost();
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getQuery() != null ? uri.getQuery() : "";

        Instant now = Instant.now();
        String dateStamp = DATE_FORMAT.format(now.atZone(ZoneOffset.UTC));
        String amzDate = DATETIME_FORMAT.format(now.atZone(ZoneOffset.UTC));

        // Headers to sign
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("host", host);
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", "UNSIGNED-PAYLOAD");

        // Create canonical request
        String signedHeaders = headers.keySet().stream()
                .map(String::toLowerCase)
                .sorted()
                .collect(Collectors.joining(";"));

        String canonicalHeaders = headers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(e -> e.getKey().toLowerCase() + ":" + e.getValue().trim())
                .collect(Collectors.joining("\n")) + "\n";

        String canonicalRequest = method + "\n" +
                urlEncodePath(path) + "\n" +
                query + "\n" +
                canonicalHeaders + "\n" +
                signedHeaders + "\n" +
                "UNSIGNED-PAYLOAD";

        // Create string to sign
        String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = AWS_ALGORITHM + "\n" +
                amzDate + "\n" +
                credentialScope + "\n" +
                sha256Hex(canonicalRequest);

        // Calculate signature
        byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, SERVICE);
        String signature = hmacSha256Hex(signingKey, stringToSign);

        // Create authorization header
        String authorization = AWS_ALGORITHM + " " +
                "Credential=" + accessKey + "/" + credentialScope + ", " +
                "SignedHeaders=" + signedHeaders + ", " +
                "Signature=" + signature;

        // Return headers
        Map<String, String> result = new HashMap<>();
        result.put("Authorization", authorization);
        result.put("x-amz-date", amzDate);
        result.put("x-amz-content-sha256", "UNSIGNED-PAYLOAD");

        return result;
    }

    private byte[] getSignatureKey(String key, String dateStamp, String region, String service)
            throws Exception {
        byte[] kSecret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256Hex(byte[] key, String data) throws Exception {
        return bytesToHex(hmacSha256(key, data));
    }

    private String sha256Hex(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String urlEncodePath(String path) {
        // Simple URL encoding for path segments
        return path.replace(" ", "%20");
    }

    /**
     * Set S3 credentials programmatically.
     */
    public void setCredentials(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    /**
     * Create a provider with specific credentials.
     */
    public static S3AuthProvider withCredentials(String accessKey, String secretKey, String region) {
        S3AuthProvider provider = new S3AuthProvider();
        provider.setCredentials(accessKey, secretKey, region);
        return provider;
    }
}
