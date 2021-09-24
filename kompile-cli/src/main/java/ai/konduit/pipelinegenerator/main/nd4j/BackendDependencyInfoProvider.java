package ai.konduit.pipelinegenerator.main.nd4j;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Profile;

import java.util.List;

public interface BackendDependencyInfoProvider {

    List<Dependency> dependencies();

    List<String> javacppIncludePaths();

    List<String> javacppLinksPaths();

    List<String> javacppBuildResources();

    List<String> javacppIncludeResources();

    List<String> javacppLinkResources();

    List<Dependency> javacppDependencies();

    List<Profile> profiles();


}
