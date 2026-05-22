# Skill: Parallel Multi-Role Code Review

Run a parallel code review using four independent agents, each adopting a distinct engineering role. Findings from all agents are consolidated into a single prioritised table.

## When to invoke

Use this skill when the user asks for a code review, PR review, or quality check using phrases like:
- "run your parallel code review"
- "do a multi-role review"
- "review this with different roles"
- "security / architecture / resilience review"

## Roles

| # | Role | Focus |
|---|------|-------|
| 1 | **Security Engineer** | Auth gaps, injection risks, secrets in code, header trust, error message leakage, OWASP Top 10 |
| 2 | **Resilience Engineer** | Race conditions, missing timeouts, unbounded collections, transactions wrapping I/O, retry storms, SPOF |
| 3 | **Software Architect** | Coupling (shared types across services), layer violations, God classes, missing abstractions, domain boundary drift |
| 4 | **QA / Test Engineer** | Missing test coverage, untested error paths, test state leakage, assertions that don't validate behaviour |

## Execution steps

### Step 1 — Launch four background agents in parallel

Start all four agents in a **single response** with `mode: "background"`. Each agent gets:
- The diff or files to review (provide full context)
- Its assigned role and focus areas
- Instructions to return findings as a markdown table: `| File | Line | Severity | Finding | Recommendation |`
- Severity scale: CRITICAL / HIGH / MEDIUM / LOW

**Agent prompt template:**

```
You are a <ROLE> reviewing a pull request / set of changed files for the NeoBank workshop
(Java 21, Spring Boot 4, jOOQ, PostgreSQL microservices).

Your focus areas: <FOCUS_AREAS>

Files / diff to review:
<DIFF_OR_FILE_CONTENTS>

Return ONLY findings that matter — skip style, formatting, and trivial nits.
Format each finding as a row in this markdown table:
| File | Line | Severity | Finding | Recommendation |

Severity: CRITICAL (data loss / security breach) > HIGH (significant bug / design flaw)
         > MEDIUM (notable improvement) > LOW (minor concern)

If you find nothing significant, say so explicitly.
```

### Step 2 — Wait for all agents

After launching, end your response. When all four agents complete, collect results with `read_agent`.

### Step 3 — Consolidate findings

Merge the four tables into one, deduplicate near-identical findings, and sort by severity. Output:

```markdown
## Parallel Code Review — <PR/branch name>

### Consolidated Findings

| Severity | Role | File | Line | Finding | Recommendation |
|----------|------|------|------|---------|----------------|
| CRITICAL | Security | ... | ... | ... | ... |
...

### Summary
- **CRITICAL**: N  **HIGH**: N  **MEDIUM**: N  **LOW**: N
- Top action: <single most important thing to fix>
```

### Step 4 — Triage

Ask the user:
> "Fix the highest-priority findings now, and create GitHub issues for the rest?"

If yes:
- Fix CRITICAL/HIGH items in the current branch
- Run `gh issue create` for MEDIUM/LOW items with label `code-review`

## Project-specific reviewer hints

- **Security**: Check `@Value` defaults (should be in `application.yml` only), `X-Forwarded-For` trust, open `permitAll()` wildcards in `SecurityConfig`, KYC fail-open paths
- **Resilience**: HTTP calls inside `@Transactional`, missing `RestClient` timeouts, `ConcurrentHashMap` without eviction, TOCTOU on SUM queries
- **Architecture**: Types shared via `common` module that belong to a single service, missing `TransactionTemplate` pattern for mixed I/O+DB workflows
- **QA**: `@MockitoBean` vs real integration, filter tests needing `.addFilters()` explicitly, test state leakage via singleton beans (rate limiter map)
