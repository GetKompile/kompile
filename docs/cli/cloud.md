# Cloud Commands

`kompile cloud` manages your Kompile cloud account, instances, and applications.

## Authentication

```bash
kompile cloud login --user=adam --password=***
kompile cloud register --user=adam --email=adam@example.com --password=***
kompile cloud logout
kompile cloud status                              # Show auth status
```

## Cloud instances

```bash
kompile cloud instances                           # List cloud instances
kompile cloud local list                          # List locally running instances
kompile cloud local sync                          # Sync local instance state
```

## Cloud applications

```bash
kompile cloud apps list                           # List applications
kompile cloud apps show --id=<id>                 # Application details
kompile cloud apps types                          # Available app types
kompile cloud apps create --type=rag --name=myapp # Create application
kompile cloud apps deploy --id=<id>               # Deploy application
kompile cloud apps deploy-model-staging --id=<id> # Deploy model staging
kompile cloud apps delete --id=<id>               # Delete application
```

## Cloud build jobs

```bash
kompile cloud jobs list                           # List build jobs
kompile cloud jobs credits                        # Check credit balance
kompile cloud jobs logs --id=<id>                 # View job logs
kompile cloud jobs cancel --id=<id>               # Cancel a job
```
