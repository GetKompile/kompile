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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kompile-managed pointer to an external project store server (the self-hostable
 * {@code kompile-project-store-server}). Persisted as JSON under the Kompile config
 * directory — this is intentionally <em>not</em> a Spring {@code @ConfigurationProperties}
 * binding, so it travels with the project / Kompile home rather than the app's runtime
 * properties, and can be changed at runtime without restarting.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectStoreConfig {

    /** Base URL of the external project store, e.g. {@code http://localhost:8088}. */
    private String url;

    /** Run {@code git xet install} after cloning so xet-tracked large data materializes. */
    private boolean gitXet = true;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isGitXet() {
        return gitXet;
    }

    public void setGitXet(boolean gitXet) {
        this.gitXet = gitXet;
    }

    /** True when a non-blank store URL has been configured (serialized as {@code configured}). */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }
}
