# Agent Control Plane v0.1

This fork keeps Agora's existing generation, tool, memory/RAG, MCP, automation, and local-inference stacks intact. The control plane is introduced as a thin domain layer above them.

## Objective

Move from a chat-centric execution model to a mission-centric model without coupling planning and policy to any specific LLM provider.

## Initial domain contracts

- `MissionSpec` — immutable objective, constraints, capability requirements, model preference, and execution budget.
- `Mission` — lifecycle state and bounded execution counters.
- `AgentTask` — one executable unit inside a mission, including required capabilities, dependency IDs, and effect level.
- `AgentCapability` — provider-neutral capability vocabulary.
- `AgentPolicy` — local authorization boundary for capabilities, side effects, step count, and timeout.
- `Evidence` — normalized proof/observation record for future durable execution traces.

## Invariants

1. A mission cannot enter a terminal state and then resume.
2. A mission cannot skip directly from `PROPOSED` to `SUCCEEDED`.
3. Every task is evaluated against the effective policy before execution.
4. A capability absent from the policy is denied, regardless of model preference.
5. Destructive effects are denied by default.
6. Non-read-only effects can require explicit approval.
7. Mission and policy step budgets are both enforced; the effective budget is the smaller value.
8. Domain contracts have no Android, Room, Compose, provider, or network dependency.

## Integration boundary

The next layer will adapt these contracts to Agora's existing runtime:

```text
Mission
  |
  +--> Planner / Task Graph
  |
  +--> Policy Evaluator
  |
  +--> Existing GenerationManager + ToolProvider + MCP + RAG
  |
  +--> Verification / Evidence collector
  |
  +--> Durable run/evidence repository
```

The existing `TaskExecutionEngine` already provides a headless path that reuses the same generation pipeline as foreground execution. It should be treated as the first execution adapter rather than replaced.

## Deferred deliberately

The following are not part of v0.1:

- automatic model routing;
- Room migrations for mission/evidence persistence;
- autonomous planning loops;
- automatic side-effect approval;
- new Compose screens;
- changes to provider wire protocols.

Those features depend on the contracts above remaining stable and testable.
