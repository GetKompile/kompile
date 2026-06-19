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

package ai.kompile.crawler.remote;

import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S3-compatible remote folder client using the standard Java HTTP client
 * with AWS Signature V4 authentication. Supports AWS S3, MinIO, and other
 * S3-compatible object stores.
 *
 * <p>Properties:</p>
 * <ul>
 *   <li>{@code accessKey} — AWS access key ID (required)</li>
 *   <li>{@code secretKey} — AWS secret access key (required)</li>
 *   <li>{@code region} — AWS region (default: us-east-1)</li>
 *   <li>{@code endpoint} — custom endpoint URL for S3-compatible stores (optional)</li>
 * </ul>
 *
 * <p>The {@code pathOrUrl} is parsed as {@code s3://bucket/prefix} or just {@code bucket/prefix}.</p>
 */
public class S3FolderClient implements RemoteFolderClient {

    private static final Logger log = LoggerFactory.getLogger(S3FolderClient.class);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private HttpClient httpClient;
    private String accessKey;
    private String secretKey;
    private String region;
    private String bucket;
    private String prefix;
    private String baseUrl;

    @Override
    public SourceType sourceType() {
        return SourceType.S3;
    }

    @Override
    public void connect(String pathOrUrl, Map<String, Object> properties) throws IOException {
        this.accessKey = requireProp(properties, "accessKey");
        this.secretKey = requireProp(properties, "secretKey");
        this.region = stringProp(properties, "region", "us-east-1");
        String endpoint = stringProp(properties, "endpoint", null);

        // Parse bucket and prefix from pathOrUrl: "s3://bucket/prefix" or "bucket/prefix"
        String path = pathOrUrl;
        if (path.startsWith("s3://")) {
            path = path.substring(5);
        }
        int slash = path.indexOf('/');
        if (slash > 0) {
            this.bucket = path.substring(0, slash);
            this.prefix = path.substring(slash + 1);
            if (this.prefix.endsWith("/")) {
                this.prefix = this.prefix.substring(0, this.prefix.length() - 1);
            }
        } else {
            this.bucket = path;
            this.prefix = "";
        }

        if (endpoint != null && !endpoint.isBlank()) {
            this.baseUrl = endpoint.endsWith("/")
                    ? endpoint.substring(0, endpoint.length() - 1)
                    : endpoint;
            // Path-style: endpoint/bucket
            this.baseUrl = this.baseUrl + "/" + bucket;
        } else {
            this.baseUrl = "https://" + bucket + ".s3." + region + ".amazonaws.com";
        }

        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        log.info("S3 client connected: bucket={}, prefix={}, region={}", bucket, prefix, region);
    }

    @Override
    public List<RemoteFileEntry> listFiles(int maxDepth) throws IOException {
        List<RemoteFileEntry> entries = new ArrayList<>();
        String continuationToken = null;

        do {
            StringBuilder query = new StringBuilder("?list-type=2&max-keys=1000");
            if (prefix != null && !prefix.isEmpty()) {
                query.append("&prefix=").append(urlEncode(prefix + "/"));
            }
            if (continuationToken != null) {
                query.append("&continuation-token=").append(urlEncode(continuationToken));
            }

            try {
                HttpRequest request = signedGet("/" + query);
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new IOException("S3 ListObjectsV2 failed: HTTP " + response.statusCode()
                            + " — " + response.body().substring(0, Math.min(500, response.body().length())));
                }

                String body = response.body();
                entries.addAll(parseListResponse(body, maxDepth));

                // Check for continuation
                if (body.contains("<IsTruncated>true</IsTruncated>")) {
                    continuationToken = extractXml(body, "NextContinuationToken");
                } else {
                    continuationToken = null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("S3 listing interrupted", e);
            }
        } while (continuationToken != null);

        log.info("S3 listing complete: {} files in {}/{}", entries.size(), bucket, prefix);
        return entries;
    }

    @Override
    public void download(String remoteKey, Path localDest) throws IOException {
        try {
            Files.createDirectories(localDest.getParent());
            HttpRequest request = signedGet("/" + urlEncode(remoteKey).replace("%2F", "/"));
            HttpResponse<Path> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofFile(localDest));

            if (response.statusCode() != 200) {
                Files.deleteIfExists(localDest);
                throw new IOException("S3 GET failed for " + remoteKey + ": HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 download interrupted for " + remoteKey, e);
        }
    }

    @Override
    public void close() {
        // HttpClient does not require explicit close
    }

    // ---- S3 XML parsing (minimal, no dependency on XML libraries) ----

    private List<RemoteFileEntry> parseListResponse(String xml, int maxDepth) {
        List<RemoteFileEntry> entries = new ArrayList<>();
        int idx = 0;
        while (true) {
            int start = xml.indexOf("<Contents>", idx);
            if (start < 0) break;
            int end = xml.indexOf("</Contents>", start);
            if (end < 0) break;
            String item = xml.substring(start, end);
            idx = end + 11;

            String key = extractXml(item, "Key");
            if (key == null || key.endsWith("/")) continue; // skip "directory" markers

            // Apply depth filtering relative to prefix
            if (maxDepth > 0 && prefix != null && !prefix.isEmpty()) {
                String relative = key.startsWith(prefix + "/") ? key.substring(prefix.length() + 1) : key;
                long depth = relative.chars().filter(c -> c == '/').count();
                if (depth >= maxDepth) continue;
            }

            String sizeStr = extractXml(item, "Size");
            long size = sizeStr != null ? Long.parseLong(sizeStr) : -1;

            String lastMod = extractXml(item, "LastModified");
            long lastModMs = 0;
            if (lastMod != null) {
                try {
                    lastModMs = Instant.parse(lastMod).toEpochMilli();
                } catch (Exception e) {
                    log.debug("Failed to parse S3 LastModified timestamp '{}': {}", lastMod, e.getMessage());
                }
            }

            String etag = extractXml(item, "ETag");
            if (etag != null) {
                etag = etag.replace("\"", "");
            }

            String fileName = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;

            entries.add(new RemoteFileEntry(key, fileName, size, lastModMs, null, etag));
        }
        return entries;
    }

    private static String extractXml(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int s = xml.indexOf(open);
        if (s < 0) return null;
        int e = xml.indexOf(close, s);
        if (e < 0) return null;
        return xml.substring(s + open.length(), e);
    }

    // ---- AWS Signature V4 ----

    private HttpRequest signedGet(String pathAndQuery) {
        Instant now = Instant.now();
        String dateStamp = ISO_DATE.format(now.atOffset(ZoneOffset.UTC));
        String amzDate = AMZ_DATE.format(now.atOffset(ZoneOffset.UTC));
        URI uri = URI.create(baseUrl + pathAndQuery);
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

        String canonicalHeaders = "host:" + host + "\nx-amz-content-sha256:" + EMPTY_SHA256
                + "\nx-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";

        String canonicalQuery = uri.getRawQuery() != null ? uri.getRawQuery() : "";
        String canonicalRequest = "GET\n" + uri.getRawPath() + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_SHA256;

        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, "s3");
        String signature = hexEncode(hmacSha256(signingKey, stringToSign));
        String authHeader = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Host", host)
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", EMPTY_SHA256)
                .header("Authorization", authHeader)
                .GET()
                .build();
    }

    private static byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName) {
        byte[] kSecret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp);
        byte[] kRegion = hmacSha256(kDate, regionName);
        byte[] kService = hmacSha256(kRegion, serviceName);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HmacSHA256 failed", e);
        }
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hexEncode(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireProp(Map<String, Object> props, String key) throws IOException {
        Object v = props.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IOException("Required property '" + key + "' is missing");
        }
        return v.toString();
    }

    private static String stringProp(Map<String, Object> props, String key, String defaultValue) {
        Object v = props.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : defaultValue;
    }
}
