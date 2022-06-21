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
