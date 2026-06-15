# Projects

A project is the central organizing concept in Kompile. Every project is a directory backed by a `kompile.project.json` manifest that tracks its documents, models, code repositories, crawl profiles, pipelines, prompt templates, and chat sessions.

## Creating a project

```bash
# Initialize in the current directory
kompile project init --name my-rag-project

# Or generate a complete application from a preset
kompile build app --configName=myapp --preset=hosted-llm-rag
```

## Project lifecycle

Projects move through lifecycle states:

```
DRAFT -> ACTIVE -> PAUSED -> ARCHIVED | DEPRECATED
```

```bash
kompile project lifecycle --state=ACTIVE
```

## Project management commands

```bash
kompile project init --name myproject          # Initialize a project
kompile project open .                         # Start the server for this project
kompile project status                         # Show manifest, components, Git state
kompile project add-model --path=model.onnx    # Register a model
kompile project add-crawl-profile              # Add an ingestion profile
kompile project add-code-project --dir=./src   # Register code for semantic search
kompile project index-code-project <id>        # Index code for search
kompile project commit / pull / push           # Git operations on the project
```

## Version control

Projects can be backed by Git or Git-XET for version control with optional auto-commit.

## Build app vs project init

`kompile project init` creates a lightweight project wrapper around existing assets. `kompile build app` generates a complete, self-contained application as a Maven project with exactly the modules you selected baked in.

The output from `build app` lives at `kompile-rag-builds/<configName>/project/` and includes a compiled artifact (JAR or native binary).

See the [Build Applications](../cli/build-app.md) section for preset details and build options.
