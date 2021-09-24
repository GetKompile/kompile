package ai.konduit.pipelinegenerator.main;


import java.util.List;
import java.util.Map;

public class PomGeneratorConfig {

    private Map<String,String> entries;
    private List<PomGeneratorConfig> pomGeneratorConfigList;

    public PomGeneratorConfig(Map<String, String> entries, List<PomGeneratorConfig> pomGeneratorConfigList) {
        this.entries = entries;
        this.pomGeneratorConfigList = pomGeneratorConfigList;
    }
}
