package ai.konduit.pipelinegenerator.main.uninstall;

import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "uninstall",mixinStandardHelpOptions = false,subcommands = {
        UnInstallGraalvm.class,
        UnInstallPython.class,
        UnInstallMaven.class,
        UnInstallAll.class
})
public class UnInstallMain implements Callable<Integer> {
    public UnInstallMain() {
    }

    public static void deleteDirectory(File dir) throws IOException {
        if(dir.exists()) {
            FileUtils.deleteDirectory(dir);
            System.out.println("Uninstalled " + dir.getName());
        } else {
            System.err.println("No directory at " + dir.getAbsolutePath() + " found. Skipping. ");
        }
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new UnInstallMain());
        commandLine.usage(System.err);
        return 0;
    }
}
