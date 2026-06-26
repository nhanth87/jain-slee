---
name: AI Test Plan request
about: Ask the AI Test Agent to plan tests for an in-flight PR or feature
title: '[ai-test] '
labels: ai-test
assignees: ''
---

## What to test

A description of the change you want tested. Either:

- A PR URL (e.g. `https://github.com/nhanth87/jain-slee/pull/42`)
- A branch name (e.g. `feat/my-change`)
- A diff pasted inline

## Context

Anything the AI agent should know:

- Related issues (`Fixes #123`, `Blocked by #45`)
- Performance constraints (e.g. "must not regress the 100K stress test")
- Test infrastructure preferences (JUnit 4 vs 5, AssertJ, Mockito)

## Workflow

The AI Test Agent (`.github/workflows/ai-test-agent.yml`) will:

1. Diff the PR against the base branch.
2. Call DeerFlow to produce a structured test plan.
3. Comment the plan back on this issue / the linked PR.
4. Run `mvn test` to confirm the plan is executable.
5. Report any failures back to DeerFlow for analysis.

You need to have `DEERFLOW_URL` and `OPENHANDS_URL` secrets configured
on this repo for the workflow to fire. See
[`CONTRIBUTING.md`](../../CONTRIBUTING.md#ai-assisted-ci-openhands--deerflow).
