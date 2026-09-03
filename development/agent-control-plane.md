# Agent Control Plane Contract

Status: implementation contract, 2026-09-03.

This module adds a mission-level control boundary above Agora's existing conversation generation
runtime. It must not create a second generation, queue, Stop, settlement, or durable Run lifecycle.

## 1. Ownership

The control plane owns only:

- mission objective, constraints, requested capabilities, and bounded mission budget;
- task requirements and declared side-effect level;
- policy admission decisions;
- mission-level lifecycle and evidence references.

The existing Conversation runtime remains authoritative for execution of individual generations,
Provider passes, tool rounds, Stop/cancellation, durable settlement, recovery, and per-conversation
Run identity.

A Mission may span multiple existing Runs. A Run must never be reactivated to satisfy a Mission.

## 2. Admission invariants

1. A task is denied when any required capability is outside the effective policy.
2. Destructive effects are denied unless explicitly enabled by policy.
3. Reversible/non-read-only effects require approval when policy requires approval.
4. Blank task or mission objectives are invalid.
5. Mission step count cannot exceed the smaller of mission and policy budgets.
6. Mission timeout and step ceilings are validated before execution admission.
7. Policy evaluation is pure and does not call Android, Room, Provider, tool, network, or UI code.
8. The LLM does not own authorization decisions.

## 3. Lifecycle invariants

- `PROPOSED -> PLANNED -> EXECUTING` is the normal admission path.
- Execution may pass through `WAITING_TOOL`, `OBSERVING`, and `VERIFYING`.
- Terminal states are `SUCCEEDED`, `FAILED`, `BLOCKED`, and `CANCELLED`.
- A terminal mission cannot resume.
- This lifecycle is not a substitute for `ConversationRuntimeReducer`/`RunState`.

## 4. Evidence boundary

Evidence is a structured control-plane record, not generated prose. Future runtime adapters must
attach tool/provider/test/verification results to missions without allowing an unverified model
claim to become evidence automatically.

The minimal portable evidence fields are mission/task identity, kind, source, summary, optional
content hash, verification flag, and timestamp. Durable schema design is deferred until one real
consumer exists and should reuse an existing evidence/provenance contract where practical.

## 5. Integration rule

The next runtime slice must adapt existing Agora capability surfaces instead of duplicating them:

`Mission -> policy gate -> existing Generation/Tool/MCP/Task runtime -> observation -> verification -> evidence`

Do not add speculative capability descriptors, project registries, adapter factories, or multi-agent
schedulers until two or more real consumers demonstrate a shared invariant or side-effect boundary.

## 6. Required verification

Focused tests must cover capability denial, side-effect approval, destructive denial, invalid lifecycle
transitions, terminal immutability, budget enforcement, and invalid mission/task input. Integration
work must additionally prove stale-result rejection and that the control plane does not create a
parallel Run owner.
