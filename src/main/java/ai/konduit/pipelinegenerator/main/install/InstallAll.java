package ai.konduit.pipelinegenerator.main.install;

import ai.konduit.pipelinegenerator.main.Info;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "all",mixinStandardHelpOptions = false)
public class InstallAll implements Callable<Integer> {

    public InstallAll() {
    }

    @Override
    public Integer call() throws Exception {
        int exit = new InstallGraalvm().call();
        exit = new InstallMaven().call();
        exit = new InstallPython().call();
        return exit;
    }
}
