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

/**
 * A project as advertised by an external project store server. Mirrors the server's
 * {@code ProjectDto} so the response deserializes directly; unknown fields are ignored to
 * stay forward-compatible with newer servers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteProjectDto {
    public String id;
    public String namespace;
    public String slug;
    public String fullName;
    public String repoType;
    public String visibility;
    public String defaultBranch;
    public String description;
    public String cloneUrl;
    public String cliCloneCommand;
    public String manifest;
}
