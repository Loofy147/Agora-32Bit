# Local Model Runtime Contract

Status: authoritative embedded llama.cpp lifecycle contract, 2026-08-28.

This document owns process-wide admission, native model residency, identity changes, cancellation,
and idle offload for Agora's embedded llama.cpp Chat and Embedding paths. Conversation/Run lifecycle
remains owned by [message-generation.md](message-generation.md), while portability of the device-local
retention setting remains owned by [import-export.md](import-export.md).

## 1. Canonical owners

- `LocalModelRuntime` is the one process-wide owner of embedded llama.cpp admission and the one
  resident native model/context.
- `LocalModelTaskQueue` is the one FIFO admission boundary shared by Local Chat, Local title
  generation, and Local Embedding work.
- `LlamaChatEngine` owns a resident Chat native handle and its replaceable multimodal projector
  substate. `LlamaEngine` owns a resident Embedding native handle only while the runtime identifies
  that resident as Embedding.
- `AppContainer` binds the one app-lifetime idle-retention settings flow to the runtime.

No caller may load, unload, reset, replace, or generate with an embedded model outside this owner.
Remote Providers, including a PC-hosted Qwen endpoint, do not enter this queue.

## 2. Resident identity and switching

Exactly one of these identities may be resident:

- `Chat(canonicalModelPath, nCtx)`;
- `Embedding(canonicalModelPath)` using the fixed native Embedding context parameters.

Chat and Embedding are different identities even when their canonical model paths match. Chat
sampling values such as temperature, top P, and maximum output tokens do not construct the native
context and therefore do not change identity.

A task requesting the current identity reuses its resident model. Reused Chat identity clears its
context before the new request; reused Embedding identity clears per-input context memory through
the native Embedding path. A different path, mode, or Chat `nCtx` closes the old resident completely
before the replacement load begins. If replacement loading fails, no model remains resident and the
request fails through its ordinary Local error path.

The multimodal projector is replaceable Chat substate rather than process identity. It is loaded only
for an image request, reused only for the same projector path, replaced when that path changes, and
never permits concurrent mutation of the resident Chat engine.

## 3. Strict FIFO admission

Every submitted Local task is counted as queued-or-active before it waits for the process permit.
The permit is fair FIFO: one complete Local request owns it from identity selection/load through all
native work, callbacks, and request cleanup. There is no concurrent Chat/Chat, Chat/Embedding, or
Embedding/Embedding native execution.

An active task is never preempted by a newer task or a different requested model. A cancelled waiter
is removed from admission and cannot disturb the relative order of remaining waiters. Task failure or
cancellation still releases its queue ownership and participates in the same final idle transition.

Stop targets only the currently active Chat engine through its thread-safe native cancellation path.
It does not cancel Embedding work, unload a model directly, cancel waiting Local tasks, or acquire the
permit held by the active native generation.

## 4. Idle offload lifecycle

Idle means there are no queued or active Local tasks. A model may remain resident while idle for the
configured retention duration.

1. Arrival of any Local task invalidates and cancels the current idle deadline before admission.
2. A deadline starts only when the final queued-or-active task has completely unwound.
3. Expiry re-enters the same FIFO permit as conditional maintenance and unloads only after proving
   that the queue is still empty for the captured idle epoch.
4. A task arriving at the expiry boundary linearizes either before unload, cancelling or invalidating
   it, or after the completed unload and then loads its requested identity normally.
5. A setting change while idle invalidates the old epoch and restarts the deadline from the change.
   A setting change during work affects the deadline created after the final task completes.
6. Zero minutes unloads immediately after the final queued-or-active task completes. It never unloads
   between already submitted tasks.

Only the configured duration persists. An in-flight deadline or remaining elapsed time is not
restored after process death; the new process begins with no resident model and no inherited timer.

## 5. Setting and UI contract

`local_model_idle_retention_minutes` accepts only `0, 1, 2, 5, 10, 15, 30`; invalid or absent values
normalize to the five-minute default. It is stored in this device's DataStore and exposed at
Provider -> Local -> Advanced as the discrete `Model idle retention` slider. Zero is presented as
immediate offload; positive presets are presented in minutes.

The default resource and every supported locale define the same localized key and placeholder set.
The setting is device-local: it is excluded from portable Settings export/import and survives a
Settings `REPLACE`, as specified by [import-export.md](import-export.md).

## 6. Prohibited behavior

Never introduce a second Local lock, model cache, lifecycle manager, offload timer, Provider-local
fallback, per-caller unload callback, or identity definition. Never unload directly from a timer
without reacquiring the canonical permit and revalidating the idle epoch. Do not restore idle
deadlines across processes, interrupt active native work to honor a deadline, or export this setting.

## 7. Required verification

Focused verification must cover FIFO ordering, no native overlap, cancelled-waiter removal,
Chat/Embedding/path/context identity changes, unload-before-load, failed replacement, same-identity
reuse, active-Chat-only Stop, and Embedding input isolation. Idle tests must cover arrival
cancellation, no countdown while queued/active, last-task deadline start, setting-change restart,
zero-minute behavior, expiry-versus-arrival linearization, and unload through the same permit.

Settings tests must cover the exact presets/default/normalization, DataStore read/write, one
AppContainer binding, Local Advanced placement and slider commit behavior, locale key/placeholder
parity, portable-export absence, and Settings Replace preservation. The project full build remains
required; build success alone does not prove real-device memory release or model reload latency.
