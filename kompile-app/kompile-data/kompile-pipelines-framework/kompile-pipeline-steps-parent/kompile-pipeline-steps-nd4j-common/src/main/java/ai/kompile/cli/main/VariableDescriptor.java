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

package ai.kompile.cli.main;

import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import lombok.*;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.linalg.api.buffer.DataType;


import java.io.Serializable;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VariableDescriptor implements Serializable {
    private String varName;
    private VariableType variableType;
    private long[] shape;
    private DataType dataType;

    public static VariableDescriptor fromJson(String json) {
        return  ObjectMappers.getJsonMapper().convertValue(json,VariableDescriptor.class);
    }

    @SneakyThrows
    public String toJson() {
        return  ObjectMappers.getJsonMapper().writeValueAsString(this);
    }

}
