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
package ai.konduit.pipelinegenerator.main.install;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.TextProgressMonitor;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "kompile-components",mixinStandardHelpOptions = false)
public class InstallKompileComponents implements Callable<Integer> {
    @CommandLine.Option(names = {"--kompileGitUrl"},description = "The git URL to use for kompile",required = false)
    private String kompileGitUrl = "https://github.com/KonduitAI/kompile";

    @CommandLine.Option(names = {"--kompileLocation"},description = "The path to clone the kompile repository to",required = false)
    private String kompileLocation = "";
    @CommandLine.Option(names = {"--kompileBranch"},description = "The branch to clone from.",required = false)
    private String kompileBranch = "main";
    @CommandLine.Option(names = {"--forceClone"},description = "Whether to delete the local kompile install before cloning.",required = false)
    private boolean forceClone = false;

    @Override
    public Integer call() throws Exception {
        File cloneRepo = new File(kompileLocation);
        if(cloneRepo.exists() && !forceClone) {
            System.err.println("Kompile directory already found at: " + kompileLocation + ". Please either use --forceClone to force a new clone, delete this directory manually, or specify a different location.");
            return 1;
        }

        Git.cloneRepository()
                .setURI(kompileGitUrl)
                .setDirectory(cloneRepo)
                .setBranch(kompileBranch)
                .setProgressMonitor(new TextProgressMonitor())
                .call();
        return 0;
    }
}
