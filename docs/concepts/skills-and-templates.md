# Skills and Prompt Templates

## Skills

A skill is a named, reusable agent capability -- similar to a slash command. Skills define what an agent can do and are exposed as MCP prompts.

### Managing skills

```bash
# List available skills
kompile skills list

# Show a skill
kompile skills show --name=rag_query

# Create a skill
kompile skills create --name=my-skill --description="Custom skill" \
  --tools=rag_search,code_search --template="Analyze: {{query}}"

# Delete a skill
kompile skills delete --name=my-skill

# Generate Markdown documentation for all skills
kompile skills generate-md

# Auto-expand a skill into sub-skills
# (via web UI: Tools > Skills > Expand)
```

### Skill properties

| Property | Description |
|----------|------------|
| Name | Unique identifier |
| Description | What the skill does |
| Category | Organizational grouping |
| Tools | Which MCP tools the skill requires |
| Template | The prompt template the skill uses |
| Project | Optional project association |

### From the web UI

Tools tab > Skills. Browse, create, edit, and expand skills. Skills can be organized by category and associated with specific projects.

## Prompt templates

Prompt templates are Jinja-style text templates with named variables. They provide reusable, structured prompts for LLM interactions.

### Managing templates

```bash
# Via REST API
# List templates
curl http://localhost:8080/api/prompts

# Create a template
curl -X POST http://localhost:8080/api/prompts \
  -H "Content-Type: application/json" \
  -d '{"name": "code_review", "template": "Review this {{language}} code:\n{{code}}", "category": "development"}'

# Render a template with variables
curl -X POST http://localhost:8080/api/prompts/code_review/render \
  -H "Content-Type: application/json" \
  -d '{"language": "Java", "code": "public void foo() { ... }"}'

# Duplicate a template
curl -X POST http://localhost:8080/api/prompts/code_review/duplicate
```

### Pre-seeded templates

Generated applications come with pre-seeded templates: `rag_query`, `code_review`, `extract_entities`, and others in `data/prompt-templates/`.

### Variable extraction

Templates automatically detect `{{variable}}` placeholders. The render endpoint validates that all required variables are provided.

## When to use skills vs. templates

**Skills** are agent-facing. Use them when you want an agent to have a
named capability it can invoke — "analyze code", "search documentation",
"extract entities". Skills are exposed as MCP prompts.

**Templates** are prompt-facing. Use them when you want reusable prompt
structures that can be filled with different variables — the code review
template, the entity extraction template, the RAG query template.

A skill typically *uses* a template. The skill defines what the agent can
do, the template defines the prompt structure.

## Related concepts

- **[Agents](agents.md)** — agents discover skills through MCP and use
  them as capabilities
- **[MCP Integration](../mcp/README.md)** — skills are exposed as MCP
  prompts via the `skill_manager` tool
- **[Fact Sheets](fact-sheets.md)** — fact sheet data can be injected
  into prompt templates as variables
