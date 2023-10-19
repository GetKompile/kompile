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

package ai.konduit.pipelinegenerator.main.uninstall;

import ai.konduit.pipelinegenerator.main.Info;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "python",mixinStandardHelpOptions = false)
public class UnInstallPython implements Callable<Integer> {
    public UnInstallPython() {
    }

    @Override
    public Integer call() throws Exception {
        UnInstallMain.deleteDirectory(new File(Info.pythonDirectory().getAbsolutePath()));
        return 0;
    }
}