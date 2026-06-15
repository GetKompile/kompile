# Knowledge Commands

`kompile knowledge` manages knowledge stores and Markdown notes.

## Markdown knowledge store

Sync notes from a local folder, Git repository, or Obsidian vault:

```bash
# List notes
kompile knowledge markdown list --store=my-notes

# Read a note
kompile knowledge markdown read --store=my-notes --note-id=<id>

# Create a note
kompile knowledge markdown create --store=my-notes \
  --title="Meeting Notes" \
  --content="Key decisions: ..." \
  --tags=meetings,q4

# Update a note
kompile knowledge markdown update --store=my-notes --note-id=<id> \
  --content="Updated content"

# Delete a note
kompile knowledge markdown delete --store=my-notes --note-id=<id>

# Sync with remote
kompile knowledge markdown sync --store=my-notes

# Test connection
kompile knowledge markdown test --store=my-notes
```

## Store configuration

| Option | Description |
|--------|------------|
| `--store` | Store name |
| `--path` | Local folder path |
| `--repo-url` | Git repository URL |
| `--branch` | Git branch |
| `--no-remote-sync` | Disable remote sync |
| `--no-auto-commit` | Disable auto-commit |
| `--auth-mode` | Authentication mode |
| `--git-username` / `--git-token` | Git credentials |
| `--obsidian-api-url` / `--obsidian-token` | Obsidian REST API |
| `--with-frontmatter` | Include YAML frontmatter |
| `--fact-sheet-id` | Link notes to a fact sheet |

## How it fits together

Markdown notes can be linked to fact sheets, which in turn feed into RAG queries and knowledge graph construction. This creates a pipeline from notes to structured knowledge to AI-augmented retrieval.
