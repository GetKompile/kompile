# Installed skill catalog

Snapshot captured 2026-08-05. Always query the live registries before choosing a skill.

## Kompile skill registry

`skill_manager list_skills` reported seven built-ins and no custom skills:

- git: `commit`, `pr`
- code: `review`, `simplify`, `explain`, `test`
- debug: `fix`

`skill_manager scan_provider_skills` found:

- Claude Code project command: `init`
- Qwen project command: `commit`

## Installed Codex skills visible to this session

System skills:

- `imagegen`
- `openai-docs`
- `plugin-creator`
- `skill-creator`
- `skill-installer`

Kompile/DL4J skills:

- `bench`, `build-fix`, `dispatch`, `dl4j`, `dl4j-build`, `dl4j-test`
- `dsp-debug`, `full-loop`, `investigate`
- `k-agents`, `k-config`, `k-files`, `k-memory`, `k-process`, `k-research`
- `k-search-code`, `k-todo`, `k-track`
- `regress`, `test-fix`, `workflow`

## Reconciliation procedure

1. Read the session-provided installed-skill catalog first; it defines triggerable Codex skills.
2. Call `skill_manager list_skills` to inspect kompile registry templates.
3. Call `skill_manager scan_provider_skills` to find provider-specific commands in the current project.
4. If a named skill exists, read its complete `SKILL.md` before acting.
5. Use the smallest set of skills that fully covers the request.
6. Treat name collisions as separate namespaces: Codex skills, kompile registry skills, and provider commands may share names.
