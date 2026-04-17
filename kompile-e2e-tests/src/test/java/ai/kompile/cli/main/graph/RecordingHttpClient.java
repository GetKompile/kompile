/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.graph;

import ai.kompile.cli.common.http.KompileHttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link KompileHttpClient} that records every call and returns
 * canned responses. Lets us exercise CLI commands without spinning up a real HTTP server.
 */
final class RecordingHttpClient extends KompileHttpClient {

    final List<Call> calls = new ArrayList<>();
    final Map<String, String> getResponses = new LinkedHashMap<>();
    final Map<String, String> postResponses = new LinkedHashMap<>();
    String defaultGet = "{}";
    String defaultPost = "{}";
    String uploadResponse = "{}";
    String downloadDispositionHeader = "";
    byte[] downloadPayload = new byte[0];

    RecordingHttpClient() {
        super("http://test");
    }

    static final class Call {
        final String method;
        final String path;
        final Object body;

        Call(String method, String path, Object body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        @Override
        public String toString() {
            return method + " " + path + (body == null ? "" : " " + body);
        }
    }

    @Override
    public String getString(String path) {
        calls.add(new Call("GET", path, null));
        return getResponses.getOrDefault(path, defaultGet);
    }

    @Override
    public String postString(String path, Object body) {
        calls.add(new Call("POST", path, body));
        return postResponses.getOrDefault(path, defaultPost);
    }

    @Override
    public String postEmpty(String path) {
        calls.add(new Call("POST", path, null));
        return postResponses.getOrDefault(path, defaultPost);
    }

    @Override
    public String delete(String path) {
        calls.add(new Call("DELETE", path, null));
        return "";
    }

    @Override
    public String uploadFile(String path, Path filePath) {
        calls.add(new Call("UPLOAD", path, filePath));
        return uploadResponse;
    }

    @Override
    public String uploadMultipart(String path, Map<String, Path> files, Map<String, String> formFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", files);
        body.put("form", formFields);
        calls.add(new Call("MULTIPART", path, body));
        return uploadResponse;
    }

    @Override
    public String downloadToFile(String path, Path outputFile) throws IOException {
        calls.add(new Call("DOWNLOAD", path, outputFile));
        Files.write(outputFile, downloadPayload);
        return downloadDispositionHeader;
    }

    Call lastCall() {
        return calls.get(calls.size() - 1);
    }

    Call findCall(String method, String pathPrefix) {
        for (Call c : calls) {
            if (c.method.equals(method) && c.path.startsWith(pathPrefix)) {
                return c;
            }
        }
        return null;
    }
}
