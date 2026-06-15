# Kompile Component CLI

Standalone CLI for querying and outputting Kompile component information in multiple formats. Built with Java 21 and optimized for GraalVM native-image compilation.

## Features

- **Multi-format output**: Text, JSON, YAML, CSV, and ASCII table formats
- **Component introspection**: Query installation status, runtime state, and configuration
- **Native-image ready**: Compiles to fast, lightweight native binaries
- **Java 21**: Built with modern Java features
- **Zero dependencies**: Self-contained native binary

## Supported Output Formats

| Format | Flag | Description |
|--------|------|-------------|
| Text | `--format text` | Human-readable key-value pairs (default) |
| JSON | `--format json` | Pretty-printed JSON |
| YAML | `--format yaml` | YAML format |
| CSV | `--format csv` | Comma-separated values with headers |
| Table | `--format table` | ASCII table with borders |

## Commands

### `list` - List All Components

Display all available Kompile components with their details.

```bash
# List all components (text format)
kompile-component list

# List as JSON
kompile-component list --format json

# List as YAML
kompile-component list --format yaml

# List as ASCII table
kompile-component list --format table

# Show only installed components
kompile-component list --installed-only

# Show only running components
kompile-component list --running-only

# Combine filters
kompile-component list --installed-only --format json
```

**Example Output (table format):**

```
| COMPONENT                | NAME                     | TYPE  | DEFAULT PORT | INSTALLED | STATUS  |
|--------------------------|--------------------------|-------|--------------|-----------|---------|
| kompile-app-main         | Kompile App Main         | app   | 8080         | true      | running |
| kompile-model-staging    | Kompile Model Staging    | staging | 8081       | true      | not_running |
| kompile-cli              | Kompile CLI              | cli   | N/A          | true      | not_running |
```

### `status` - Check Component Status

Check the status of specific component(s).

```bash
# Check all components
kompile-component status

# Check specific component
kompile-component status kompile-app-main

# Check with JSON output
kompile-component status kompile-app-main --format json

# Perform HTTP health check
kompile-component status kompile-app-main --health-check

# Verbose output with uptime and resource usage
kompile-component status kompile-app-main --verbose

# Combine options
kompile-component status kompile-app-main --format yaml --health-check --verbose
```

**Example Output (detailed text):**

```
Component: kompile-app-main
  Status: ✓ running
  Installed: true
  PID: 12345
  Port: 8080
  URL: http://localhost:8080
  Health: ✓ healthy
  Started: 2025-04-08T10:30:00Z
  Uptime: 2 hours, 15 minutes
  JAR: /home/user/.kompile/components/kompile-app-main/0.1.0-SNAPSHOT/kompile-app-main-0.1.0-SNAPSHOT.jar
```

### `config` - Show Component Configuration

Display detailed component configuration.

```bash
# Show config for specific component
kompile-component config kompile-app-main

# Show config as YAML
kompile-component config kompile-app-main --format yaml

# Show config for all components
kompile-component config --all

# Include file system paths
kompile-component config kompile-app-main --include-paths

# CSV output for all components
kompile-component config --all --format csv
```

**Example Output (YAML format):**

```yaml
id: kompile-app-main
name: Kompile App Main
description: Spring Boot RAG application with web UI
type: spring-boot-app
installed: true
installPath: /home/user/.kompile/components/kompile-app-main/0.1.0-SNAPSHOT
runtime:
  defaultPort: 8080
  mainClass: ai.kompile.app.MainApplication
  jvmArgs:
  - "-Xmx4g"
  - "-Xms2g"
  healthEndpoint: /actuator/health
repository:
  githubRepo: KonduitAI/kompile
  mavenGroupId: ai.kompile
  mavenArtifactId: kompile-app-main
  releaseSource: github
```

## Installation

### Build from Source

```bash
# Build JAR
cd kompile-component-cli
mvn clean package -DskipTests

# Build native image (requires GraalVM 21)
mvn -Pnative clean package -DskipTests
```

### Using SDKMAN GraalVM 21

```bash
# Install GraalVM 21 via SDKMAN
sdk install java 21.0.2-graal

# Set as current Java
sdk use java 21.0.2-graal

# Verify
java -version
# Should show: openjdk version "21.0.2" ... GraalVM CE ...

# Build native image
mvn -Pnative clean package -DskipTests

# The native binary will be at:
# target/kompile-component
```

### Manual GraalVM Setup

If not using SDKMAN:

```bash
# Download GraalVM 21
# From: https://github.com/graalvm/graalvm-ce-builds/releases

# Extract and set JAVA_HOME
export JAVA_HOME=/path/to/graalvm-21
export PATH=$JAVA_HOME/bin:$PATH

# Build native image
mvn -Pnative clean package -DskipTests
```

## Usage Examples

### Scripting and Automation

```bash
# Get JSON for all components
kompile-component list --format json | jq '.[] | select(.installed == true)'

# Check if specific component is running
if kompile-component status kompile-app-main --format json | jq -e '.[] | select(.status == "running")' > /dev/null; then
    echo "Component is running"
else
    echo "Component is not running"
fi

# Generate CSV report
kompile-component list --format csv > components.csv

# Generate YAML config backup
kompile-component config --all --format yaml > kompile-config.yaml
```

### Monitoring and Health Checks

```bash
# Quick health check
kompile-component status kompile-app-main --health-check

# Monitor all running services
watch -n 5 'kompile-component list --running-only --format table'

# Get component PIDs for process monitoring
kompile-component status --format json | jq '.[] | select(.status == "running") | {component: .component, pid: .pid}'
```

### Configuration Management

```bash
# Export all component configs as JSON
kompile-component config --all --format json > config-export.json

# Compare installed vs available
kompile-component list --installed-only --format json | jq '.[].id'

# Find components by type
kompile-component list --format json | jq '.[] | select(.type == "cli")'
```

## Architecture

### Module Structure

```
kompile-component-cli/
├── pom.xml                                    # Maven configuration (Java 21, native-image)
├── src/main/java/ai/kompile/cli/component/
│   ├── ComponentCliMain.java                  # Main entry point
│   ├── cmd/
│   │   ├── ComponentListCommand.java          # List command
│   │   ├── ComponentStatusCommand.java        # Status command
│   │   └── ComponentConfigCommand.java        # Config command
│   └── output/
│       └── OutputFormatter.java               # Multi-format output engine
└── src/main/resources/META-INF/native-image/
    └── component-cli/
        ├── reflect-config.json                # GraalVM reflection config
        ├── jni-config.json                    # GraalVM JNI config
        ├── resource-config.json               # GraalVM resource config
        └── proxy-config.json                  # GraalVM proxy config
```

### Key Design Decisions

1. **Java 21**: Uses modern Java features (records, switch expressions, pattern matching)
2. **Picocli**: Industry-standard CLI framework with native-image support
3. **Jackson**: Robust JSON/YAML/CSV processing
4. **No Spring**: Lightweight, fast startup, smaller native binary
5. **Standalone**: No dependencies on other Kompile modules (except kompile-cli-common)

### Native-Image Optimization

The module is optimized for GraalVM native-image compilation:

- **Build-time initialization**: Logging (Logback) initialized at build time
- **Run-time initialization**: Netty initialized at runtime (if used)
- **Reflection config**: Explicit registration of Picocli command classes
- **Resource inclusion**: Services and native-image configs bundled
- **Memory efficient**: 4GB max heap for native builds (vs 18GB for Spring Boot apps)

## Building with Different GraalVM Versions

### SDKMAN GraalVM 21 (Recommended)

```bash
sdk install java 21.0.2-graal
sdk use java 21.0.2-graal
mvn -Pnative clean package -DskipTests
```

### GraalVM 17 (Legacy)

```bash
# Update pom.xml Java version
# <java.version>17</java.version>
# <maven.compiler.source>17</maven.compiler.source>
# <maven.compiler.target>17</maven.compiler.target>
# <maven.compiler.release>17</maven.compiler.release>

sdk install java 17.0.12-graal
sdk use java 17.0.12-graal
mvn -Pnative clean package -DskipTests
```

### Oracle GraalVM (non-CE)

```bash
# Download from Oracle
# Set JAVA_HOME accordingly
mvn -Pnative clean package -DskipTests
```

## Integration with Kompile CLI

The component CLI integrates with the main Kompile CLI:

```bash
# From main CLI
kompile manage list              # Uses instance registry
kompile manage status kompile-app-main

# Direct component CLI usage
kompile-component list --format json
kompile-component status kompile-app-main --health-check
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success (all queried components are running) |
| 1 | Error (component not running, not installed, or command failed) |
| 2 | Usage error (invalid parameters) |

## Troubleshooting

### Native Image Build Fails

```bash
# Check GraalVM installation
java -version
# Should show GraalVM

# Check native-image tool
native-image --version

# Increase build memory
export MAVEN_OPTS="-Xmx8g"
mvn -Pnative clean package -DskipTests
```

### Binary Not Found

```bash
# JAR location
target/kompile-component-cli-0.1.0-SNAPSHOT.jar

# Native binary location
target/kompile-component
```

### JSON Parsing Errors

```bash
# Validate JSON output
kompile-component list --format json | jq .

# Pretty print with custom formatting
kompile-component list --format json | jq '.[] | {name: .name, status: .status}'
```

## Future Enhancements

- [ ] Export configs to files (JSON/YAML export)
- [ ] Import configs from files
- [ ] Component dependency graph visualization
- [ ] Performance metrics (CPU, memory, I/O)
- [ ] Remote component querying (via HTTP API)
- [ ] Plugin system for custom output formats
- [ ] Watch mode with auto-refresh
- [ ] Comparison mode (diff configs or statuses)

## License

Apache License 2.0 - See LICENSE file for details.
