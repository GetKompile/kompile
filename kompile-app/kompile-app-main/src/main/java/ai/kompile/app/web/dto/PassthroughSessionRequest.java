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

package ai.kompile.app.web.dto;

import java.util.List;

public class PassthroughSessionRequest {

    private String agentName;
    private boolean skipPermissions = true;
    private String workingDirectory;
    private boolean injectMcpTools = true;
    private String sessionName;
    private List<String> agentArgs;

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public boolean isSkipPermissions() {
        return skipPermissions;
    }

    public void setSkipPermissions(boolean skipPermissions) {
        this.skipPermissions = skipPermissions;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public boolean isInjectMcpTools() {
        return injectMcpTools;
    }

    public void setInjectMcpTools(boolean injectMcpTools) {
        this.injectMcpTools = injectMcpTools;
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setSessionName(String sessionName) {
        this.sessionName = sessionName;
    }

    public List<String> getAgentArgs() {
        return agentArgs;
    }

    public void setAgentArgs(List<String> agentArgs) {
        this.agentArgs = agentArgs;
    }
}
