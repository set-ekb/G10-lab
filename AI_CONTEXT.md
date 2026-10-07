# AI Project Context v1 — G10 Companion

This file is the shared entry point for ChatGPT, Codex, Claude, Gemini and other AI agents working on this repository.

## Single-source-of-truth rule

Do not create a separate project reality for each model. Model-specific files only explain how to enter the shared context. Stable project facts belong in existing canonical project documents.

## Required reading order

1. The model adapter for the current agent, if present.
2. `AGENTS.md` — mandatory engineering and safety rules.
3. `docs/PROJECT_STATE.md` — compact stable state.
4. `docs/NEXT_BUILD.md` — approved next work only.
5. Only the domain files directly required by the task.

Do not read the whole repository by default.

## Context map

| Layer | Canonical source |
| --- | --- |
| Product purpose and user-visible capabilities | `README.md`, `docs/PROJECT_STATE.md` |
| Current approved implementation package | `docs/NEXT_BUILD.md` |
| Architecture / integration truth | relevant source files listed in `AGENTS.md` |
| BLE protocol and safety boundaries | `README.md`, `docs/PROJECT_STATE.md`, `G10BleManager.java` when required |
| Route / battery model | `RouteEnergyEstimator.java`, `BatteryCoach.java`, relevant tests |
| User flow / screens | `README.md`, `MainActivity.java`, `route_map.html` when required |
| Acceptance evidence | `docs/NEXT_BUILD.md`, `core-tests/`, relevant build checks |
| Cross-agent transfer | `AI_HANDOFF.md` |

## Precedence

When sources disagree:
1. explicit current user instruction;
2. safety/invariant rules in `AGENTS.md`;
3. stable state in `docs/PROJECT_STATE.md`;
4. approved task in `docs/NEXT_BUILD.md`;
5. tests and current implementation for implementation truth;
6. README and older notes.

Do not silently resolve a material conflict. Record it in `AI_HANDOFF.md`.

## Agent responsibilities

- ChatGPT: requirements, architecture, decomposition, review, documentation and decision support.
- Codex: repository changes, tests, diffs, build-oriented implementation.
- Claude: deep review of specifications, logic, edge cases and consistency.
- Gemini: independent review, alternative solution analysis and large-context cross-checking.
- Other/local models: use the same canonical files and declare their agent id in handoff notes.

These are default strengths, not exclusive permissions.

## Iteration protocol

Before work:
- identify the approved task;
- state the minimal files needed;
- preserve all G10 safety constraints.

After work:
- run the smallest relevant validation;
- update stable project state only for durable changes;
- update `docs/NEXT_BUILD.md` when the approved package changes;
- write a concise handoff when another agent may continue the task.

## No duplication

Do not copy stable facts from `PROJECT_STATE.md` into model-specific files. Do not turn `AI_HANDOFF.md` into a permanent changelog.
