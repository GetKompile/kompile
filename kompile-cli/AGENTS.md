

---

# Kompile Skills

The following skills are available. Follow the instructions for the relevant skill when asked.

## Skill: commit (Commit)

Create a git commit for the current changes. {{args}}

Follow these steps:
1. Run `git status` to see all changed files (never use -uall flag)
2. Run `git diff` to see staged and unstaged changes
3. Run `git log --oneline -5` to see recent commit message style
4. Analyze the changes and draft a concise commit message that:
   - Summarizes what changed and why (not just "what")
   - Follows the repository's existing commit message conventions
   - Is 1-2 sentences for the subject line
5. Stage the relevant files (prefer specific files over `git add -A`)
   - Do NOT stage files that look like secrets (.env, credentials, keys)
6. Create the commit using a heredoc for the message:
   ```
   git commit -m "$(cat <<'EOF'
   Your commit message here.

   Co-Authored-By: kompile-cli <noreply@kompile.ai>
   EOF
   )"
   ```
7. Run `git status` to verify the commit succeeded

If no changes are found, inform the user. Do not create empty commits.


## Skill: pr (Pull Request)

Create or update a pull request. {{args}}

Follow these steps:
1. Run `git status` to check for uncommitted changes
2. Run `git branch --show-current` to get the current branch
3. Run `git log main..HEAD --oneline` (or appropriate base branch) to see all commits
4. Run `git diff main...HEAD` to see all changes relative to base
5. Check if branch has a remote tracking branch: `git rev-parse --abbrev-ref @{upstream} 2>/dev/null`
6. Draft a PR title (under 70 chars) and description with:
   - ## Summary: 1-3 bullet points of key changes
   - ## Test plan: How to verify the changes
7. Push the branch if needed: `git push -u origin HEAD`
8. Create the PR:
   ```
   gh pr create --title "title" --body "$(cat <<'EOF'
   ## Summary
   - ...

   ## Test plan
   - ...
   EOF
   )"
   ```

If uncommitted changes exist, ask the user if they want to commit first.
Return the PR URL when done.


## Skill: review (Review)

Review the current uncommitted changes for code quality. {{args}}

Follow these steps:
1. Run `git diff` to see unstaged changes
2. Run `git diff --cached` to see staged changes
3. If no local changes, run `git log -1 --format=%H` and `git diff HEAD~1` to review the last commit
4. Read any changed files that need more context
5. Analyze for:
   - **Bugs**: Logic errors, off-by-one, null safety, race conditions
   - **Security**: Injection, hardcoded secrets, unsafe operations
   - **Performance**: Unnecessary allocations, N+1 queries, missing caching
   - **Style**: Naming, organization, DRY violations, dead code
   - **Error handling**: Missing try/catch, unclosed resources, swallowed errors
   - **Tests**: Missing test coverage for new/changed code paths

Format your review as:
- **Critical** (must fix): ...
- **Important** (should fix): ...
- **Minor** (nice to fix): ...
- **Positive**: Good patterns worth noting


## Skill: simplify (Simplify)

Review and simplify recent code changes. {{args}}

Follow these steps:
1. Run `git diff` to see current changes (or `git diff HEAD~1` if no uncommitted changes)
2. Read the changed files to understand context
3. Look for opportunities to:
   - Remove unnecessary complexity or over-engineering
   - Eliminate dead code or unused imports
   - Simplify control flow (reduce nesting, early returns)
   - Replace verbose patterns with idiomatic alternatives
   - Consolidate duplicate logic
   - Remove unnecessary abstractions
4. Apply the simplifications using edit tools
5. Verify the changes don't break anything (check for compilation/syntax errors)

Keep changes focused — only simplify, don't add new features or restructure.


## Skill: explain (Explain)

Explain the code or recent changes. {{args}}

Follow these steps:
1. If args specify a file or function, read that directly
2. Otherwise, run `git diff` to see recent changes
3. If no uncommitted changes, run `git log -1 --format=%H` and `git show HEAD` to see the last commit
4. Read relevant source files for full context
5. Provide a clear explanation covering:
   - **What**: What the code does at a high level
   - **How**: Key implementation details and algorithms
   - **Why**: Design decisions and trade-offs
   - **Dependencies**: What this code interacts with
   - **Edge cases**: Important boundary conditions

Use simple language. Reference specific file:line locations.
If explaining changes, focus on what changed and why.


## Skill: test (Test)

Generate or run tests for recent changes. {{args}}

Follow these steps:
1. Run `git diff` to identify changed files (or `git diff HEAD~1` if no uncommitted changes)
2. Identify the testing framework used in this project:
   - Look for existing test files near the changed code
   - Check build config (pom.xml, package.json, etc.) for test dependencies
3. Read the changed files and existing tests
4. If args say "run": execute the existing tests for the changed modules
5. If args say "generate" or no specific instruction:
   - Generate test cases covering the changed code paths
   - Follow the project's existing test conventions
   - Include both happy path and edge case tests
   - Write tests to the appropriate test directory
6. Run the tests to verify they pass

Match existing test style and conventions. Don't over-test simple getters/setters.


## Skill: fix (Fix)

Fix build or test failures. {{args}}

Follow these steps:
1. If args describe the error, start there
2. Otherwise, try to reproduce the failure:
   - Run the build command (check pom.xml, package.json, Makefile, etc.)
   - Run the test suite
3. Read the error output carefully:
   - Identify the failing file and line number
   - Understand the error message
4. Read the relevant source files
5. Diagnose the root cause (don't just suppress the error)
6. Apply the fix using edit tools
7. Re-run the build/tests to verify the fix works
8. If the fix introduced new warnings, address them too

Focus on fixing the root cause, not symptoms. If multiple failures exist,
fix them one at a time, re-running tests after each fix.


