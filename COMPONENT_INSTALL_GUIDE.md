# Kompile CLI - Component Install & Manage Guide

## Overview

The Kompile CLI now includes comprehensive component management capabilities:
- **Install components** from GitHub Releases, Maven Central, or custom URLs
- **Start/stop/restart** components as managed services
- **Monitor** component health and status
- **Centralized configuration** for repositories and versions

## New Commands

### Install Commands

#### Install kompile-app-main (Spring Boot RAG Application)

```bash
# Install from GitHub Releases (default)
kompile install kompile-app

# Install specific version
kompile install kompile-app --version 0.2.0

# Install from Maven Central
kompile install kompile-app --source maven

# Install from custom URL
kompile install kompile-app --source custom --url https://my-server.com/kompile-app-main-0.2.0.tar.gz

# Force re-download
kompile install kompile-app --force

# Build from local source
kompile install kompile-app --build-from-source /path/to/kompile-app/kompile-app-main

# Install with custom port
kompile install kompile-app --port 9090

# Verbose output
kompile install kompile-app --verbose
```

#### Install kompile-model-staging (Model Lifecycle Service)

```bash
# Install from GitHub Releases
kompile install kompile-model-staging

# Install from Maven
kompile install kompile-model-staging --source maven

# Install with custom port
kompile install kompile-model-staging --port 9090

# Build from source
kompile install kompile-model-staging --build-from-source /path/to/kompile-model-staging
```

### Manage Commands

#### Start Components

```bash
# Start kompile-app-main on default port (8080)
kompile manage start kompile-app-main

# Start on custom port
kompile manage start kompile-app-main --port 9090

# Start with JVM arguments
kompile manage start kompile-app-main --jvm-arg "-Xmx8g" --jvm-arg "-Xms4g"

# Start with application arguments
kompile manage start kompile-app-main --app-arg "--spring.profiles.active=prod"

# Start model staging service
kompile manage start kompile-model-staging --port 8081
```

#### Stop Components

```bash
# Stop a running component
kompile manage stop kompile-app-main
kompile manage stop kompile-model-staging
```

#### Restart Components

```bash
# Restart a component (stop + start)
kompile manage restart kompile-app-main
kompile manage restart kompile-model-staging --port 9090
```

#### Check Status

```bash
# Check status of a specific component
kompile manage status kompile-app-main

# Output as JSON
kompile manage status kompile-app-main --json
```

#### List All Components

```bash
# List all components and their statuses
kompile manage list

# JSON output
kompile manage list --json
```

Example output:
```
Kompile Components
==================

COMPONENT                      STATUS          INSTALLED  PID      PORT           
------------------------------------------------------------------------------------------
kompile-app-main               ✓ running       yes        12345    8080           
kompile-model-staging          ✗ not_running   yes        -        -              
kompile-cli                    ✗ not_running   yes        -        -              

Summary: 1/3 components running
```

#### View Logs

```bash
# View component logs
kompile manage logs kompile-app-main

# Follow log output
kompile manage logs kompile-app-main --follow
```

## Architecture

### Component Registry (`ComponentRegistry.java`)

Centralized management of component metadata:
- **Component descriptors**: ID, name, description, type, default port, main class
- **URL resolution**: GitHub Releases, Maven Central, custom URLs
- **Install paths**: `~/.kompile/components/<component-id>/<version>/`
- **Version management**: Configurable per-component

**Supported Components:**
- `kompile-app-main`: Spring Boot RAG application (default port: 8080)
- `kompile-model-staging`: Model lifecycle service (default port: 8081)
- `kompile-cli`: Command-line interface

### Component Installer (`ComponentInstaller.java`)

Base installer with:
- **Download**: HTTP downloads with progress bars (reuses `InstallMain.downloadTo()`)
- **Extraction**: Handles `.jar`, `.tar.gz`, `.tgz`, `.zip` formats
- **Validation**: JAR manifest verification, main class detection
- **Source builds**: Maven builds from local source directories

### Service Manager (`ServiceManager.java`)

Process lifecycle management:
- **Start**: Launches Java processes, registers instances, waits for health checks
- **Stop**: Graceful shutdown (SIGTERM) with force kill fallback
- **Health checks**: HTTP probes to `/actuator/health` or root endpoint
- **Instance registry**: Tracks running instances in `~/.kompile/instances/`

### File Structure

```
~/.kompile/
├── components/
│   ├── kompile-app-main/
│   │   └── 0.1.0-SNAPSHOT/
│   │       └── kompile-app-main-0.1.0-SNAPSHOT.jar
│   └── kompile-model-staging/
│       └── 0.1.0-SNAPSHOT/
│           └── kompile-model-staging-0.1.0-SNAPSHOT.jar
└── instances/
    ├── kompile-app-main.json
    └── kompile-model-staging.json
```

## Configuration

### Release Sources

1. **GitHub Releases** (default)
   - URL format: `https://github.com/{repo}/download/{tag}/{component}-{version}-{platform}.tar.gz`
   - Default repo: `KonduitAI/kompile`
   - Platform detection: `linux-x86_64`, `macosx-arm64`, `windows-x86_64`

2. **Maven Central**
   - URL format: `https://repo1.maven.org/maven2/{group}/{artifact}/{version}/{artifact}-{version}.jar`

3. **Custom URLs**
   - Direct HTTP/HTTPS URLs to JAR or archive files

### Custom Repository Configuration

To use a custom GitHub repository or Maven repository:

```java
ComponentRegistry registry = new ComponentRegistry();
registry.setGithubRepo("MyOrg/kompile-fork");
registry.setMavenRepoUrl("https://my-nexus.com/repository/maven/");
registry.setVersion("0.2.0");
registry.setCustomUrl("kompile-app-main", "https://my-cdn.com/app.jar");
```

## Typical Workflows

### Fresh Installation

```bash
# 1. Install kompile-app-main
kompile install kompile-app

# 2. Start the application
kompile manage start kompile-app-main

# 3. Check status
kompile manage status kompile-app-main

# 4. Access the application
# Open browser to http://localhost:8080
```

### Development Cycle

```bash
# 1. Build from local source
kompile install kompile-app --build-from-source ~/projects/kompile/kompile-app/kompile-app-main

# 2. Start with debug settings
kompile manage start kompile-app-main --jvm-arg "-Xdebug" --jvm-arg "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"

# 3. Monitor logs
kompile manage logs kompile-app-main --follow
```

### Production Deployment

```bash
# 1. Install specific version
kompile install kompile-app --version 0.2.0 --source maven

# 2. Start with production settings
kompile manage start kompile-app-main \
  --port 8080 \
  --jvm-arg "-Xmx16g" \
  --jvm-arg "-Xms16g" \
  --app-arg "--spring.profiles.active=prod"

# 3. Verify health
kompile manage status kompile-app-main --json
```

### Multi-Component Setup

```bash
# Install both components
kompile install kompile-app
kompile install kompile-model-staging

# Start both services
kompile manage start kompile-app-main --port 8080
kompile manage start kompile-model-staging --port 8081

# List all components
kompile manage list
```

### Restart After Configuration Change

```bash
# Restart with new settings
kompile manage restart kompile-app-main \
  --port 9090 \
  --jvm-arg "-Xmx8g"
```

## Error Handling

### Common Issues

1. **Component not installed**
   ```
   Error: Component not installed: kompile-app-main
   Install it with: kompile install kompile-app-main
   ```

2. **Component already running**
   ```
   Error: Component already running: kompile-app-main
     PID: 12345
     Port: 8080
     URL: http://localhost:8080
   ```

3. **Health check failed**
   ```
   ⚠ Service started but health check failed
   Check logs with: kompile manage logs kompile-app-main
   ```

### Troubleshooting

```bash
# Check if process is actually running
ps -p <PID>

# Check port availability
lsof -i :8080

# View detailed status with verbose flag
kompile manage status kompile-app-main

# Manual health check
curl http://localhost:8080/actuator/health
```

## Programmatic API

The components can also be used programmatically:

```java
// Install a component
ComponentRegistry registry = new ComponentRegistry();
ComponentInstaller installer = new ComponentInstaller(registry);
File jar = installer.installComponent("kompile-app-main", ReleaseSource.GITHUB_RELEASES);

// Start a service
ServiceManager manager = new ServiceManager();
ProcessResult result = manager.startComponent("kompile-app-main", 8080, 
    Arrays.asList("-Xmx4g"), 
    Arrays.asList("--spring.profiles.active=prod"));

// Check status
ComponentStatus status = manager.getComponentStatus("kompile-app-main");
System.out.println("Status: " + status.getStatus());

// Stop the service
manager.stopComponent("kompile-app-main");
```

## Future Enhancements

Potential additions:
- **Auto-restart** on failure with configurable retry policies
- **Log rotation** and persistence
- **Metrics export** to Prometheus/Datadog
- **Cluster mode** with multiple instances
- **Docker integration** for containerized deployments
- **Environment variable** management per-component
- **Configuration hot-reload** without restart

## Implementation Files

### New Files Created
1. `kompile-cli/src/main/java/ai/kompile/cli/main/install/registry/ComponentRegistry.java`
2. `kompile-cli/src/main/java/ai/kompile/cli/main/install/ComponentInstaller.java`
3. `kompile-cli/src/main/java/ai/kompile/cli/main/install/InstallKompileApp.java`
4. `kompile-cli/src/main/java/ai/kompile/cli/main/install/InstallModelStaging.java`
5. `kompile-cli/src/main/java/ai/kompile/cli/main/manage/ServiceManager.java`
6. `kompile-cli/src/main/java/ai/kompile/cli/main/manage/ManageComponents.java`

### Modified Files
1. `kompile-cli/src/main/java/ai/kompile/cli/main/install/InstallMain.java` - Added new install subcommands
2. `kompile-cli/src/main/java/ai/kompile/cli/main/MainCommand.java` - Added manage command

## Testing the Implementation

Once Maven is available:

```bash
# Build the CLI
cd kompile-cli
mvn clean package -DskipTests

# Test install commands
./kompile install kompile-app --help
./kompile install kompile-model-staging --help

# Test manage commands
./kompile manage --help
./kompile manage start --help
./kompile manage list
```
