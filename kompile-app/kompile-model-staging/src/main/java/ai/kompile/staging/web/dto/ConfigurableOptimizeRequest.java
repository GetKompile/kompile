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
 *  limitations under the License.
 */

package ai.kompile.staging.web.dto;

import java.util.List;

public class ConfigurableOptimizeRequest {
    private List<String> enabledOptimizations;
    private String quantizationType;
    private boolean quantizePerChannel = false;
    private boolean createBackup = true;
    private boolean force = false;
    private String preset;

    public List<String> getEnabledOptimizations() { return enabledOptimizations; }
    public void setEnabledOptimizations(List<String> opts) { this.enabledOptimizations = opts; }
    public String getQuantizationType() { return quantizationType; }
    public void setQuantizationType(String type) { this.quantizationType = type; }
    public boolean isQuantizePerChannel() { return quantizePerChannel; }
    public void setQuantizePerChannel(boolean perChannel) { this.quantizePerChannel = perChannel; }
    public boolean isCreateBackup() { return createBackup; }
    public void setCreateBackup(boolean backup) { this.createBackup = backup; }
    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }
    public String getPreset() { return preset; }
    public void setPreset(String preset) { this.preset = preset; }
}