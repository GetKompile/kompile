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
           File user = Info.homeDirectory();
           if(user.exists() && forceBootstrap) {
               FileUtils.deleteDirectory(user);
               System.err.println("Forcing recreation of " + user.getAbsolutePath());
           }

           GlobalBootstrap.ensureHomeDirectory();
           GlobalBootstrap.ensureConfigs();
           System.out.println("Initialized kompile home at " + user.getAbsolutePath());
       } else {
           System.err.println("Not creating folder. createFolder was false.");
       }


        return 0;
    }
}
