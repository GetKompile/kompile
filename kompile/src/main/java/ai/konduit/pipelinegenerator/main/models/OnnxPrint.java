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

import onnx.Onnx;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.tensorflow.framework.NodeDef;
import picocli.CommandLine;

import java.io.File;
import java.io.FileInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "onnx-print",description = "Print summary of a target onnx model.")
public class OnnxPrint implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;

    @CommandLine.Option(names = {"--printNodes"},description = "Whether to print only the node names in the graph",required = false)
    private boolean printNodes = false;
    @CommandLine.Option(names = {"--printVarNames"},description = "Whether to print the graph variable names",required = false)
    private boolean printVarNames = false;
    @CommandLine.Option(names = {"--printFullGraph"},description = "Whether to print the full graph protobuf txt",required = false)
    private boolean printFullGraph = false;
    @CommandLine.Option(names = {"--nodeNameToPrint"},description = "A node name to print ",required = false)
    private List<String> nodeNameToPrint;
    @CommandLine.Option(names = {"--printInputs"},description = "Whether to print inputs for graph. ",required = false)
    private boolean printInputs;

    @CommandLine.Option(names = {"--printOutputs"},description = "Whether to print inputs for graph. ",required = false)
    private boolean printOutputs;

    public OnnxPrint() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            System.exit(1);
        }

        Onnx.ModelProto onnxModelProto = Onnx.ModelProto.parseFrom(new FileInputStream(modelFile));
        Onnx.GraphProto graphDef = onnxModelProto.getGraph();
        if(printNodes) {
            StringBuilder stringBuilder = new StringBuilder();
            for(int i = 0; i < graphDef.getNodeCount(); i++) {
                Onnx.NodeProto nodeDef = graphDef.getNode(i);
                stringBuilder.append(nodeDef.getName());
                if(i < graphDef.getNodeCount() - 1)
                    stringBuilder.append(",");
            }

            System.out.println(stringBuilder);

        }

        if(printVarNames) {
            Set<String> graphVars = new LinkedHashSet<>();
            for(int i = 0; i < graphDef.getNodeCount(); i++) {
                Onnx.NodeProto nodeDef = graphDef.getNode(i);
                for(String input : nodeDef.getInputList()) {
                    graphVars.add(input);
                }
                graphVars.add(nodeDef.getName());
            }

            System.out.println(graphVars);
        }

        if(nodeNameToPrint != null) {
            graphDef.getNodeList().forEach(node -> {
                if(nodeNameToPrint.contains(node.getName())) {
                    System.out.println(node);
                }
            });
        }

        if(printInputs) {
            System.out.println(onnxModelProto.getGraph().getInputList().stream().map(input -> input.getName()).collect(Collectors.toList()));
        }

        if(printOutputs) {
            System.out.println(onnxModelProto.getGraph().getOutputList().stream().map(input -> input.getName()).collect(Collectors.toList()));
        }

        if(printFullGraph)
            System.out.println(onnxModelProto);



        return 0;
    }
}
