# Component Management

`kompile manage` starts, stops, and monitors Kompile components (the server, model staging, etc.).

## Commands

```bash
# Start a component
kompile manage start <component> --port=8080 --verbose
kompile manage start <component> --jvm-arg="-Xmx8g"

# Stop a component
kompile manage stop <component>

# Restart a component
kompile manage restart <component> --port=8080

# Check status
kompile manage status <component>
kompile manage status <component> --json

# List all components
kompile manage list
kompile manage list --json

# View logs
kompile manage logs <component> --lines=100
kompile manage logs <component> --follow
```

## Web application

`kompile web` launches the full web application (server + staging + UI):

```bash
kompile web --project-dir=. --port=8080
kompile web --staging-port=8090
kompile web --no-staging                          # Skip model staging
kompile web --build                               # Build before launching
kompile web --no-open                             # Don't open browser
kompile web --jvm-args="-Xmx16g"

kompile web status                                # Check status
kompile web stop                                  # Stop the web app
```

## Daemon

The kompile daemon multiplexes MCP sessions over a Unix socket:

```bash
kompile serve --idle-timeout=30m --max-sessions=10 --detach

kompile daemon status                             # Daemon status
kompile daemon stop                               # Stop the daemon
kompile daemon log --lines=50                     # View daemon logs
```

## Deploy

Deploy a built project to `~/.kompile/instances/`:

```bash
kompile deploy --projectDir=. --name=myapp --port=8080
kompile deploy --with-staging --staging-port=8090
kompile deploy --container                        # Deploy as container
kompile deploy --force                            # Overwrite existing
```
