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

import ai.konduit.pipelinegenerator.main.build.*;
import ai.konduit.pipelinegenerator.main.config.ConfigMain;
import ai.konduit.pipelinegenerator.main.exec.*;
import ai.konduit.pipelinegenerator.main.helpers.HelperEntry;
import ai.konduit.pipelinegenerator.main.install.InstallMain;
import ai.konduit.pipelinegenerator.main.models.Convert;
import ai.konduit.pipelinegenerator.main.models.ModelMain;
import ai.konduit.pipelinegenerator.main.models.samediff.AddOp;
import ai.konduit.pipelinegenerator.main.uninstall.UnInstallMain;
import org.nd4j.common.config.ND4JSystemProperties;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "kompile",subcommands = {
        ExecMain.class,
        BuildMain.class,
        ConfigMain.class,
        HelperEntry.class,
        Info.class,
        InstallMain.class,
        UnInstallMain.class,
        Bootstrap.class,
        ModelMain.class,
        BackendEnvironmentInfo.class
})
public class MainCommand implements Callable<Integer> {

    public static void main(String...args) throws Exception {
        CommandLine commandLine = new CommandLine(new MainCommand());
        try {
            NativeOps nativeOps = null;
            System.setProperty(ND4JSystemProperties.INIT_NATIVEOPS_HOLDER,"false");
            Nd4jBackend load = Nd4jBackend.load();

            if(load.getClass().getName().toLowerCase().contains("cpu")) {
                Class<? extends NativeOps> nativeOpsClazz = (Class<? extends NativeOps>) Class.forName("org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu");
                nativeOps = nativeOpsClazz.newInstance();
            } else if(load.getClass().getName().toLowerCase().contains("cuda")) {
                Class<? extends NativeOps> nativeOpsClazz = (Class<? extends NativeOps>) Class.forName("org.nd4j.linalg.jcublas.bindings.Nd4jCuda");
                nativeOps = nativeOpsClazz.newInstance();

            } else if(load.getClass().getName().toLowerCase().contains("aurora")) {
                Class<? extends NativeOps> nativeOpsClazz = (Class<? extends NativeOps>) Class.forName("org.nd4j.aurora.Nd4jAuroraOps");
                nativeOps = nativeOpsClazz.newInstance();
            }

            NativeOpsHolder.getInstance().setDeviceNativeOps(nativeOps);
            NativeOpsHolder.getInstance().initOps();

        }catch (Exception e) {
            throw new RuntimeException(e);
        }

        if(args.length < 1) {
            commandLine.usage(System.err);
            System.exit(0);
        }

        //creation step is dynamically generated and needs special support
        if(Arrays.asList(args).contains("step-create")) {
            commandLine.setExecutionStrategy(parseResult -> {
                try {
                    return StepCreator.run(parseResult);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                return 1;
            });
        } else if(Arrays.asList(args).contains("add-op")) {
            commandLine.setExecutionStrategy(parseResult -> {
                try {
                    return AddOp.run(parseResult);
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            });

        }

        //user wants to know flags for a subcommand
        if(args.length == 1 || args.length == 2 && args[1].equals("-h")) {
            switch(args[0]) {
                case "exec":
                    System.exit(new ExecMain().call());
                    break;
                case "build":
                    System.exit(new BuildMain().call());
                    break;
                case "config":
                    System.exit(new ConfigMain().call());
                    break;
                case "helper":
                    System.exit(new HelperEntry().call());
                    break;
                case "info":
                    System.exit(new Info().call());
                    break;
                case "install":
                    System.exit(new InstallMain().call());
                    break;
                case "uninstall":
                    System.exit(new UnInstallMain().call());
                    break;
            }
        }

        int exit = commandLine.execute(args);
        if(args.length > 0 && !args[0].equals("serve") && args.length > 1 && !args[1].equals("serve"))
            System.exit(exit);
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.usage(System.err);
        return 0;
    }
}
