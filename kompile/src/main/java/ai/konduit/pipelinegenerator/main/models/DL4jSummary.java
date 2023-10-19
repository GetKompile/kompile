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

package ai.konduit.pipelinegenerator.main.models;

import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "dl4j-summary",description = "Print summary of a target dl4j model.")
public class DL4jSummary implements Callable<Integer> {
    @CommandLine.Option(names = {"--modelType"},description = "Model type: cg or mln. CG is shorthand for computation graph and mln is short hand for MultiLayerNetwork respectively.",required = true)
    private String modelType;

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;

    public DL4jSummary() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            System.exit(1);
        }

        switch(modelType) {
            case "mln":
                MultiLayerNetwork multiLayerNetwork = MultiLayerNetwork.load(new File(modelInputPath),false);
                System.out.println(multiLayerNetwork.summary());
                break;
            case "cg":
                ComputationGraph computationGraph = ComputationGraph.load(modelFile,false);
                System.out.println(computationGraph.summary());
                break;
        }

        return 0;
    }
}
