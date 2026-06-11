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

/**
 * Request body for creating a new enforcer session.
 */
public class EnforcerSessionRequest {

    private String agentName;
    private String rules;
    private int maxCorrections = 2;
    private String judgeBackend;
    private String workingDirectory;
    private boolean skipPermissions = true;
    private boolean injectMcpTools = true;
    private String codingProjectId;

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getRules() {
        return rules;
    }

    public void setRules(String rules) {
        this.rules = rules;
    }

    public int getMaxCorrections() {
        return maxCorrections;
    }

    public void setMaxCorrections(int maxCorrections) {
        this.maxCorrections = maxCorrections;
    }

    public String getJudgeBackend() {
        return judgeBackend;
    }

    public void setJudgeBackend(String judgeBackend) {
        this.judgeBackend = judgeBackend;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public boolean isSkipPermissions() {
        return skipPermissions;
    }

    public void setSkipPermissions(boolean skipPermissions) {
        this.skipPermissions = skipPermissions;
    }

    public boolean isInjectMcpTools() {
        return injectMcpTools;
    }

    public void setInjectMcpTools(boolean injectMcpTools) {
        this.injectMcpTools = injectMcpTools;
    }

    public String getCodingProjectId() {
        return codingProjectId;
    }

    public void setCodingProjectId(String codingProjectId) {
        this.codingProjectId = codingProjectId;
    }
}
