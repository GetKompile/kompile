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

import org.tensorflow.framework.GraphDef;
import org.tensorflow.framework.NodeDef;
import picocli.CommandLine;

import java.io.File;
import java.io.FileInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "tensorflow-print",description = "Print summary of a target tensorflow model.")
public class TensorflowPrint implements Callable<Integer> {

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


    public TensorflowPrint() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            System.exit(1);
        }

        GraphDef graphDef = GraphDef.parseFrom(new FileInputStream(modelFile));
        if(printNodes) {
            StringBuilder stringBuilder = new StringBuilder();
            for(int i = 0; i < graphDef.getNodeCount(); i++) {
                NodeDef nodeDef = graphDef.getNode(i);
                stringBuilder.append(nodeDef.getName());
                if(i < graphDef.getNodeCount() - 1)
                    stringBuilder.append(",");
            }

            System.out.println(stringBuilder);

        }

        if(printVarNames) {
            Set<String> graphVars = new LinkedHashSet<>();
            for(int i = 0; i < graphDef.getNodeCount(); i++) {
                NodeDef nodeDef = graphDef.getNode(i);
                for(String input : nodeDef.getInputList()) {
                    graphVars.add(input);
                }
                graphVars.add(nodeDef.getName());
            }

            System.out.println(graphVars);
        }

        if(nodeNameToPrint != null) {
            StringBuilder stringBuilder = new StringBuilder();
            graphDef.getNodeList().forEach(node -> {
                if(nodeNameToPrint.contains(node.getName())) {
                    System.out.println(node);
                }
            });
        }



        if(printFullGraph)
            System.out.println(graphDef);

        return 0;
    }
}
