/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.config.updater;

import ai.konduit.serving.pipeline.util.ObjectMappers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.nd4j.autodiff.loss.LossReduce;

import java.io.Serializable;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LossDescriptor implements Serializable {

    private String lossFunctionType;
    private String inputVariable;
    private String labelVariable;
    private String weightsVariable;
    private String targetLabelLengths;
    private String logitInputsLengths;
    private LossReduce lossReduce = LossReduce.SUM;
    private int dimension;
    private String dimensionName;

    public static LossDescriptor fromJson(String json) {
        return ObjectMappers.fromJson(json,LossDescriptor.class);
    }

    public String toJson() {
        return ObjectMappers.toJson(this);
    }
}
