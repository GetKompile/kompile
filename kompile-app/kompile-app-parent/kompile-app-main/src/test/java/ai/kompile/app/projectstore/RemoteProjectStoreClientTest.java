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

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Verifies the remote store client builds the right URLs and deserializes the store's ProjectDto. */
class RemoteProjectStoreClientTest {

    @Test
    void listProjectsParsesStoreResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        RemoteProjectStoreClient client = new RemoteProjectStoreClient(restTemplate);

        String body = "[{\"namespace\":\"alice\",\"slug\":\"widgets\",\"fullName\":\"alice/widgets\","
                + "\"repoType\":\"model\",\"visibility\":\"public\",\"defaultBranch\":\"main\","
                + "\"cloneUrl\":\"http://store/git/alice/widgets.git\","
                + "\"cliCloneCommand\":\"kompile clone alice/widgets\"}]";
        server.expect(requestTo("http://localhost:8088/api/projects"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Trailing slash on the base URL should be trimmed.
        List<RemoteProjectDto> projects = client.listProjects("http://localhost:8088/");
        assertEquals(1, projects.size());
        assertEquals("alice/widgets", projects.get(0).fullName);
        assertEquals("http://store/git/alice/widgets.git", projects.get(0).cloneUrl);
        server.verify();
    }

    @Test
    void getProjectExpandsPathAndParsesManifest() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        RemoteProjectStoreClient client = new RemoteProjectStoreClient(restTemplate);

        String body = "{\"namespace\":\"alice\",\"slug\":\"widgets\",\"fullName\":\"alice/widgets\","
                + "\"cloneUrl\":\"http://store/git/alice/widgets.git\","
                + "\"manifest\":\"{\\\"name\\\":\\\"widgets\\\"}\"}";
        server.expect(requestTo("http://localhost:8088/api/projects/alice/widgets"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        RemoteProjectDto dto = client.getProject("http://localhost:8088", "alice", "widgets");
        assertNotNull(dto);
        assertEquals("alice/widgets", dto.fullName);
        assertTrue(dto.manifest.contains("widgets"));
        server.verify();
    }

    @Test
    void schemelessBaseUrlDefaultsToHttp() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        RemoteProjectStoreClient client = new RemoteProjectStoreClient(restTemplate);

        server.expect(requestTo("http://localhost:8088/api/projects"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertTrue(client.listProjects("localhost:8088").isEmpty());
        server.verify();
    }
}
