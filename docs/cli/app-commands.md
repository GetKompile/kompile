# App Commands

`kompile app` interacts with a running kompile-server instance. These commands let you manage the server from the CLI without using the web UI.

## Ingest

```bash
kompile app ingest file /path/to/document.pdf    # Upload a local file
kompile app ingest path /path/to/documents/       # Register a directory
kompile app ingest url https://docs.example.com   # Add a URL source
kompile app ingest status                         # Check ingestion status
kompile app ingest cancel                         # Cancel running ingestion
kompile app ingest list                           # List ingested documents
```

## Crawl

Start and manage crawl jobs:

```bash
kompile app crawl start --source=web --seed=https://example.com
kompile app crawl wizard                          # Interactive setup
kompile app crawl status                          # Current job status
kompile app crawl pause                           # Pause running job
kompile app crawl resume                          # Resume paused job
kompile app crawl cancel                          # Cancel job
kompile app crawl cleanup                         # Clean up completed jobs
kompile app crawl sources                         # List available source types
```

## Index

Manage the vector index:

```bash
kompile app index status                          # Index status
kompile app index rebuild                         # Rebuild the index
kompile app index vector                          # Vector population status
```

## Jobs

View indexing job history:

```bash
kompile app jobs list                             # List all jobs
kompile app jobs show --id=<jobId>                # Show job details
kompile app jobs logs --id=<jobId>                # View job logs
kompile app jobs stats                            # Job statistics
```

## Setup

Configure a running instance:

```bash
kompile app setup status                          # Readiness check
kompile app setup staging-server                  # Configure staging server
kompile app setup staging                         # Configure model staging
kompile app setup reload                          # Reload configuration
kompile app setup watch                           # Watch setup progress
kompile app setup run                             # Run the full setup wizard
```

## RAG pipeline

Manage RAG pipelines:

```bash
kompile app rag-pipeline list                     # List pipelines
kompile app rag-pipeline templates                # Show available templates
kompile app rag-pipeline show --id=<id>           # Show pipeline details
kompile app rag-pipeline create --template=<t>    # Create from template
kompile app rag-pipeline delete --id=<id>         # Delete a pipeline
kompile app rag-pipeline status --id=<id>         # Pipeline status
kompile app rag-pipeline execute --id=<id>        # Execute a pipeline
kompile app rag-pipeline use --id=<id>            # Set as active pipeline
```

## Subprocess

Monitor subprocesses:

```bash
kompile app subprocess list                       # List running subprocesses
kompile app subprocess status                     # Subprocess status
kompile app subprocess config                     # View subprocess config
kompile app subprocess events                     # Recent subprocess events
kompile app subprocess stats                      # Subprocess statistics
```

## Training

Manage model training:

```bash
kompile app train wizard                          # Interactive training setup
kompile app train start --config=train.json       # Start training job
kompile app train list                            # List training jobs
kompile app train status --id=<id>                # Job status
kompile app train logs --id=<id>                  # View training logs
kompile app train cancel --id=<id>                # Cancel a job
kompile app train history                         # Training history
```

## Schedule

Manage scheduled jobs:

```bash
kompile app schedule create --cron="0 0 * * *" --type=crawl
kompile app schedule list
kompile app schedule delete --id=<id>
```
