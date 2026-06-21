/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.projectstore;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Thin HTTP client for the external project store's REST API ({@code /api/projects}). Used to
 * discover projects and resolve a project's git clone URL before cloning it locally.
 */
@Service
public class RemoteProjectStoreClient {

    private final RestTemplate restTemplate;

    public RemoteProjectStoreClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} bind to the underlying template. */
    RemoteProjectStoreClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** List every project advertised by the store. */
    public List<RemoteProjectDto> listProjects(String baseUrl) {
        URI uri = UriComponentsBuilder.fromHttpUrl(normalize(baseUrl)).path("/api/projects").build().toUri();
        RemoteProjectDto[] projects = restTemplate.getForObject(uri, RemoteProjectDto[].class);
        return projects == null ? List.of() : Arrays.asList(projects);
    }

    /** Fetch a single project (including its manifest), or null if the store returns nothing. */
    public RemoteProjectDto getProject(String baseUrl, String namespace, String slug) {
        URI uri = UriComponentsBuilder.fromHttpUrl(normalize(baseUrl))
                .path("/api/projects/{namespace}/{slug}")
                .buildAndExpand(namespace, slug)
                .encode()
                .toUri();
        return restTemplate.getForObject(uri, RemoteProjectDto.class);
    }

    /** Trim trailing slashes and default to http:// when the user omits a scheme. */
    private static String normalize(String baseUrl) {
        String url = baseUrl.trim();
        if (!url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
