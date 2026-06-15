# Guardrails and Evaluation

## Guardrails

Guardrails are input and output filters that protect against unsafe or low-quality interactions.

### Input guardrails

| Guardrail | What it detects |
|-----------|----------------|
| Prompt injection | Attempts to override system instructions |
| Jailbreak | Attempts to bypass safety filters |
| PII detection | Personal identifiable information in queries |
| Toxicity | Toxic or abusive language |
| Topic | Off-topic queries |
| Business rule | Violations of custom business rules |
| Competitor mention | References to competitors |
| Copyright | Copyrighted content |

### Output guardrails

| Guardrail | What it detects |
|-----------|----------------|
| Hallucination | Responses not supported by retrieved context |
| Relevancy | Responses that don't address the query |
| Format | Responses that don't match expected format |

### Configuration

Each guardrail can be toggled individually:

```bash
# Via REST API
curl -X POST http://localhost:8080/api/guardrails/toggle \
  -H "Content-Type: application/json" \
  -d '{"guardrailType": "PROMPT_INJECTION", "enabled": true}'

# View guardrail status
curl http://localhost:8080/api/guardrails
```

Enable/disable guardrails via the web UI: Settings > Guardrails, or via `feature-flags-config.json`.

## Evaluation

The evaluation harness measures RAG pipeline quality through structured test suites.

### CLI usage

```bash
# Run an evaluation suite
kompile eval run --suite=my-suite --agent=claude-code

# View results
kompile eval report --suite=my-suite

# Compare runs
kompile eval compare --suite=my-suite

# List suites
kompile eval list

# Inspect a specific run
kompile eval inspect --id=<run-id>

# Create a new suite
kompile eval create --name=my-suite

# Delete a suite
kompile eval delete --name=my-suite
```

### Experiments

Experiments group evaluation runs for A/B testing:

```bash
# Via REST API
curl http://localhost:8080/api/experiments

# Create an experiment
curl -X POST http://localhost:8080/api/experiments \
  -H "Content-Type: application/json" \
  -d '{"name": "Compare embedding models", "datasetId": "<id>"}'
```

### Scheduled evaluation

Run evaluation suites on a schedule to detect regressions:

```bash
kompile app schedule create --type=eval --cron="0 0 * * *" --suite=my-suite
```

## Tool gateway

The tool gateway uses an LLM judge to evaluate whether tool calls are appropriate before executing them.

```bash
# Configure the gateway
kompile config tool-gateway

# Or via the CLI wizard
kompile configure gateway
```

Configuration options: model source, fail-open behavior, evaluation timeout, judge scoring thresholds.

## Performance harness

Evaluate agent performance across standardized tasks:

```bash
kompile perf report                    # View performance report
kompile perf recommend                 # Get optimization recommendations
kompile perf config                    # Configure settings
kompile perf reset                     # Reset collected data
```
