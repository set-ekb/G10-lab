# AI Project Context v1 — G10 Companion

global_protocol: 2.0

## Global control plane — AI Workspace Protocol 2.0

Before project work, prefer the AI Workspace Launcher or otherwise load the canonical global context from Google Drive:
- Global AGENTS: https://docs.google.com/document/d/1n4TBLBpyX3DlfBmY3Yc4K7JDZDc0nYP5QAjouTCyzaA/edit
- Index: https://docs.google.com/document/d/1N1dlTKxp_NFTxoKAXtrdJ5TWYOsk8WwzTVEkxs_xsho/edit
- Coordination protocol: https://docs.google.com/document/d/1oQ1pw0DmKLYpcDtOO1UDiGoe1t_bSi6wkheG0tUIAIQ/edit
- AI registry: https://docs.google.com/document/d/17T6I1JXQHfqN-F-GbRqMqxAlFY7E14YjcViRCZ3XKo8/edit

The global control plane defines identity, access, coordination, handshake and conflict rules. This repository is the project plane. Project rules may narrow but never broaden global rights.

If the global protocol cannot be verified, do not perform deploy, destructive operations, or writes to shared global memory. Do not elevate permissions by inference.

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
