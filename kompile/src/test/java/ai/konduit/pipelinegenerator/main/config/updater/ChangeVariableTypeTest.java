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

import ai.konduit.pipelinegenerator.main.MainCommand;
import org.codehaus.plexus.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.VariableType;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ChangeVariableTypeTest {

    @Test
    public void testChangeVariable() throws IOException {
        SameDiff sameDiff = SameDiff.create();
        sameDiff.constant("0",0.0);
        File tmpFile = new File("tmp_test.fb");
        sameDiff.asFlatFile(tmpFile,true);
        FileUtils.copyFile(tmpFile,new File("test-constant.fb"));
        tmpFile.deleteOnExit();
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.execute(
                "model",
                "samediff",
                "samediff-change-variable-type",
                "--modelInputPath=tmp_test.fb",
                "--newModelOutputPath=tmp_test_2.fb",
                "--variableToChange=0",
                "--convertTo=VARIABLE"
        );
        File outputFile = new File("tmp_test_2.fb");
        SameDiff load = SameDiff.load(outputFile,true);
        assertEquals(VariableType.VARIABLE,load.getVariable("0").getVariableType());
        outputFile.deleteOnExit();

    }

}
