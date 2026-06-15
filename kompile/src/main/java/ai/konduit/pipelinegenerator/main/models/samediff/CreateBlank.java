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

package ai.konduit.pipelinegenerator.main.models.samediff;

import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "create-blank",description = "Creates a blank model")
public class CreateBlank implements Callable<Integer> {


    @CommandLine.Option(names = {"--modelOutputPath"},description = "Output path to new model file for saving a new model.",required = true)
    private File modelOutputPath;


    public CreateBlank() {
    }

    @Override
    public Integer call() throws Exception {
        SameDiff.create().asFlatFile(modelOutputPath);
        System.out.println("Saved model at " + modelOutputPath);
        return 0;
    }


}
