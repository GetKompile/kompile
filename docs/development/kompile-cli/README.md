# kompile-cli

The command-line interface, built with [Picocli](https://picocli.info/). This is the main entry point for users and the source of every `kompile <command>` invocation.

## Package structure

All commands live under `ai.kompile.cli.main`:

| Package | Purpose |
|---------|---------|
| `build/` | `BuildAppCommand`, `BuildDistCommand`, `RagPomGenerator`, `PomModelBuilder` |
| `chat/` | Chat modes (direct, server-connected, agent passthrough), TUI rendering |
| `config/` / `configure/` | Configuration wizards and JSON config management |
| `project/` | Project init, open, status, lifecycle, Git operations |
| `mcp/` | MCP stdio transport, tool profiles, schema compression |
| `serve/` | Shared MCP daemon over Unix socket |
| `run/` | Local LLM download and inference |
| `graph/` | Knowledge graph CLI (nodes, edges, traverse, search) |
| `codeindex/` | Local code search and indexing |
| `app/` | Commands for managing a running server |
| `install/` / `uninstall/` | Dependency management (GraalVM, Maven, Python) |
| `models/` / `converter/` | Model conversion and management |
| `pipeline/` | Pipeline execution commands |
| `coordination/` | Multi-agent coordination |
| `a2a/` | Agent-to-Agent protocol |

Entry point: `MainCommand.java`.

## Building

```bash
cd kompile-cli
mvn clean package

# Native image
mvn clean package -Pnative
```

## Adding a new command

1. Create a command class implementing `Callable<Integer>` with `@Command` annotation
2. Register it as a subcommand in the appropriate parent command
3. Commands are auto-discovered via ClassGraph at runtime for GraalVM native image reflection config

```java
@Command(name = "mycommand", description = "...")
public class MyCommand implements Callable<Integer> {
    @Option(names = "--option")
    private String option;

    @Override
    public Integer call() throws Exception {
        // Implementation
        return 0;
    }
}
```

## RagPomGenerator

`RagPomGenerator` generates custom Maven POMs for RAG applications. When adding a new kompile-app module that should be selectable by users, you must:

1. Add a `@CommandLine.Option` field (e.g., `--includeXxx`, defaultValue `"true"`, negatable)
2. Add the corresponding `addDependency()` call in `addApplicationDependencies()`
3. Update the sample project POM at `kompile-rag-builds/kompile-sample/project/pom.xml`
