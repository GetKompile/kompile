package ai.kompile.project.server.web;

/** REST representation of a hosted project (plus convenience clone fields for the UI/CLI). */
public class ProjectDto {
    public String id;
    public String namespace;
    public String slug;
    public String fullName;
    public String repoType;
    public String visibility;
    public String defaultBranch;
    public String description;
    public String cloneUrl;
    public String cliCloneCommand;
    public String manifest;
}
