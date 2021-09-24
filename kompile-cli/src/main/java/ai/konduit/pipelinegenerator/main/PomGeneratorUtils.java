package ai.konduit.pipelinegenerator.main;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PomGeneratorUtils {
    public static void addDependency(@NotNull List<Dependency> addTo, @NotNull String groupId,@NotNull  String artifactId, String version) {
        addDependency(addTo,groupId,artifactId,version,"compile");
    }

    public static void addDependency(@NotNull List<Dependency> addTo, @NotNull String groupId,@NotNull  String artifactId, @NotNull String version, String scope) {
        addDependency(addTo,groupId,artifactId,version,scope,null, Collections.emptyList());
    }

    public static Dependency getDependency(@NotNull String groupId, @NotNull String artifactId, @NotNull String version, String scope, String classifier) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        dependency.setScope(scope);
        dependency.setClassifier(classifier);
        return dependency;
    }

    public static void addDependency(@NotNull List<Dependency> addTo,@NotNull String groupId, @NotNull String artifactId, @NotNull String version, @NotNull String scope, @NotNull String classifier) {
        addDependency(addTo,groupId,artifactId,version,scope,classifier,Collections.emptyList());
    }

    public static void addDependency(@NotNull List<Dependency> addTo, @NotNull String groupId, @NotNull String artifactId, @NotNull String version, @NotNull String scope, @NotNull String classifier, List<GeneratorExclusion> exclusions) {
        Dependency dependency = getDependency(groupId,artifactId,version,scope,classifier);
        if(exclusions != null) {
            for (GeneratorExclusion g : exclusions) {
                Exclusion exclusion = new Exclusion();
                exclusion.setArtifactId(g.getArtifactId());
                exclusion.setGroupId(g.getGroupId());
                dependency.addExclusion(exclusion);
            }
        }
        addTo.add(dependency);

    }

    public static Xpp3Dom getHybridMultiListConfig(Map<String,String> configuration,List<PomListConfig> entries) throws IOException, XmlPullParserException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("<configuration>");
        for(Map.Entry<String,String> configEntry : configuration.entrySet()) {
            stringBuilder.append(String.format("<%s>%s</%s>\n",configEntry.getKey(),configEntry.getValue(),configEntry.getKey()));
        }

        for(PomListConfig pomGeneratorConfig : entries) {
            stringBuilder.append(pomGeneratorConfig.toConfigString() + "\n");
        }

        stringBuilder.append("</configuration>");
        StringReader stringReader = new StringReader(stringBuilder.toString());
        Xpp3Dom ret = Xpp3DomBuilder.build(stringReader);
        return ret;
    }

    public static Xpp3Dom getHybridConfig(Map<String,String> configuration,List<String> entries,String singular,String listName) throws IOException, XmlPullParserException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("<configuration>");
        for(Map.Entry<String,String> configEntry : configuration.entrySet()) {
            stringBuilder.append(String.format("<%s>%s</%s>\n",configEntry.getKey(),configEntry.getValue(),configEntry.getKey()));
        }

        stringBuilder.append("<" + listName + ">");
        for(String entry : entries) {
            stringBuilder.append("<" + singular + ">" + entry + "</" + singular + ">");
        }

        stringBuilder.append("</" + listName + ">");

        stringBuilder.append("</configuration>");
        StringReader stringReader = new StringReader(stringBuilder.toString());
        Xpp3Dom ret = Xpp3DomBuilder.build(stringReader);
        return ret;
    }

    public static Xpp3Dom getExclusionList(List<String> exclusions) throws IOException, XmlPullParserException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("<configuration>");
        stringBuilder.append("<excludes>");
        for(String exclusion : exclusions) {
            stringBuilder.append("<exclude>" + exclusion + "</exclude>");
        }
        stringBuilder.append("</excludes>");
        stringBuilder.append("</configuration>");
        StringReader stringReader = new StringReader(stringBuilder.toString());
        Xpp3Dom ret = Xpp3DomBuilder.build(stringReader);
        return ret;

    }


    public static Xpp3Dom getPluginConfigObject(Map<String,String> configuration) throws IOException, XmlPullParserException {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("<configuration>\n");
        for(Map.Entry<String,String> configEntry : configuration.entrySet()) {
            stringBuilder.append(String.format("<%s>%s</%s>\n",configEntry.getKey(),configEntry.getValue(),configEntry.getKey()));
        }
        stringBuilder.append("</configuration>");
        StringReader stringReader = new StringReader(stringBuilder.toString());
        Xpp3Dom ret = Xpp3DomBuilder.build(stringReader);
        return ret;
    }
}
