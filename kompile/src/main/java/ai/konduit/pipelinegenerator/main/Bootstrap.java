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

package ai.konduit.pipelinegenerator.main;

import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "bootstrap",description = "Sets up SDK for building images.")
public class Bootstrap implements Callable<Integer> {
    @CommandLine.Option(names = {"--createFolder"},description = "Whether to create home folder or not, defaults to true",required = false)
    private boolean createFolder = true;

    @CommandLine.Option(names = {"--forceBootstrap"},description = "Whether to force creation of directory or not.",required = false)
    private boolean forceBootstrap = false;

    public Bootstrap() {
    }

    @Override
    public Integer call() throws Exception {
       if(createFolder) {
           File user = new File(System.getProperty("user.home"),".kompile");
           if(user.exists() && forceBootstrap) {
               FileUtils.deleteDirectory(user);
               System.err.println("Forcing recreation of " + user.getAbsolutePath());
           }

           if(!user.exists() && !user.mkdirs()) {
               System.err.println("Unable to create directory.");
           } else {
               System.out.println("Created directory " + user.getAbsolutePath());
           }
       } else {
           System.err.println("Not creating folder. createFolder was false.");
       }


        return 0;
    }
}
