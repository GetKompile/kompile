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
package ai.kompile.app.web.dto.modelregistry;

public class StagingContext {
    public boolean connected;
    public String endpointUrl;
    public String uiUrl;

    public StagingContext(boolean connected, String endpointUrl, String uiUrl) {
        this.connected = connected;
        this.endpointUrl = endpointUrl;
        this.uiUrl = uiUrl;
    }
}
