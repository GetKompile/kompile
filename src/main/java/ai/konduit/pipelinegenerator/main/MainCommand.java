package ai.konduit.pipelinegenerator.main;

import ai.konduit.pipelinegenerator.main.build.*;
import ai.konduit.pipelinegenerator.main.config.ConfigMain;
import ai.konduit.pipelinegenerator.main.exec.*;
import ai.konduit.pipelinegenerator.main.helpers.HelperEntry;
import ai.konduit.pipelinegenerator.main.install.InstallMain;
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
        Convert.class
},
        mixinStandardHelpOptions = false)
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
