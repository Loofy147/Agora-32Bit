# Skills Architecture Contract

Status: authoritative development contract, 2026-08-16.

This document is required context for changes to Agora's persistent Skill library, Skill catalog
prompt projection, Skill tools, Skill settings, or Skill archive transport.

## 1. Product model

A Skill is a named Markdown instruction file plus an optional short description. Skills are durable,
global, user-owned resources. They are not memories, conversation messages, Providers, MCP servers,
system prompts, or a second generation pipeline.

Agora has no Active Skill. There is no `active_skill.md`, active-Skill singleton, active-Skill
toggle, active-Skill prompt variable, or `update_active_skill` tool. A single active item would waste
context, limit composition, and inject irrelevant instructions into unrelated requests.

Instead, when Skill access is enabled, one compact catalog containing only each saved file's name and
description is frozen into the ordinary generation request. The model may then read one or more
relevant Skill bodies through the ordinary tool loop. An empty library contributes an empty catalog.
The current user message and ordinary system prompt remain authoritative over conflicting Skill
content.

## 2. Storage and safety

`SkillManager` owns app-private `skill_db` Markdown files and `skill_meta.json` descriptions. Its
create/read/edit/rename/delete/list and exact-once patch behavior mirrors the established saved-Memory
file store, including deterministic name ordering, `.md` normalization, separator sanitization,
canonical containment checks, synchronized metadata access, and atomic metadata replacement.

Skill content never enters Room. File operations run off Main. A failed write/edit/delete does not
claim success. Renaming preserves metadata, duplicate targets fail, blank descriptions are removed,
and malformed metadata fails closed to an empty description map without deleting Skill files.

A monotonic catalog revision changes whenever a mutation can alter catalog text. It invalidates only
the exact context projection/request preview that consumes the catalog; it is not generation state.

## 3. Access and generation admission

One default-enabled `accessSkills` setting controls both catalog injection and Skill tool
availability. The value and the exact catalog text are captured before Run/message graph admission
and frozen into `GenerationContext`/the effective system prompt used by that Run. Settings or
catalog changes after admission cannot rewrite an in-flight Provider request or tool-definition set.

The default system prompt contains one `<skill_catalog>` block and the predefined
`{skill_catalog}` variable. The block explains that catalog entries are untrusted task resources:
read only relevant Skills, treat their bodies as instructions subordinate to the current user/system
request, and do not claim a Skill was applied without reading it. Custom prompt templates may place
the same predefined variable. Empty or disabled catalog projection resolves to an empty string.

Ordinary foreground generation, queued sends, Compact projection/accounting, Tasks, and Loops reuse
the same immutable request builder and tool executor. Skills never create a second context builder,
Provider call, queue, Run, settlement, or automation path. Compact's own tool-disabled generation
context remains tool-disabled and does not read Skills unless the ordinary shared contract explicitly
allows those tools.

## 4. Tools

When `accessSkills` is true, the ordinary `GenerationToolExecutor` exposes exactly:

- `list_skill_files`
- `read_skill_file`
- `create_skill_file`
- `edit_skill_file`
- `delete_skill_file`

Definitions, argument validation, multi-read formatting, exact-once patching, result JSON, timeout,
accepted tool-batch identity, durable tool/result persistence, and error handling mirror the saved
Memory tools. `edit_skill_file` requires an explicit `operation` of `replace`, `patch`, `rename`, or
`describe`. The executor reads only the selected operation's value, so runtime-required empty
placeholders for unrelated fields cannot create conflicting edits. Empty replacement content clears
the file, an empty patch replacement deletes the unique match, and an empty description removes the
catalog description. Unknown operations and missing patch/rename inputs fail before `SkillManager`
is called. When access is false, no Skill definitions are advertised and Skill names are not handled
through a hidden path. There is no active-Skill update tool.

Tool presentation owns localized labels/icons and structured list-result formatting. Unknown,
malformed, or failed operations return ordinary tool failure content; they do not mutate generation
state or silently fall back to Memory storage.

## 5. Settings and localization

Settings includes one Skills destination beside Memory in the Memory & Data group. Its page mirrors
the saved-file portion of the Memory page and uses the same shared Settings components and visual
language. The Access group contains the one Skill-access row with the Settings document icon and Memory-equivalent title casing. The Saved Skills group owns an explicit
loading state, a descriptive empty state, saved-file rows, and the canonical centered
`SettingsAddItem`; it does not introduce a full-width Material Button.

A saved row displays the normalized name without the `.md` suffix, uses the same medium title,
description typography, subdued primary document icon, and right-side overflow menu as saved Memory.
The menu exposes Edit and destructive Delete. Create and Edit are separate dialogs with the Memory
dialog container, bold titles, 16 dp field corners, 12 dp field spacing, body-small monospace content,
and the same bounded content heights. Delete confirmation names the target file and uses destructive
button tint. Loading and every mutation are guarded against overlapping operations, all file I/O
remains off Main, successful CRUD refreshes the visible list, and failures retain a usable state while
reporting the actual error through the established fallback. Documentation-FAB bottom spacing matches
Memory and the FAB opens `skills.md`. No Active Skill section is shown.

Every app-owned Skills destination, switch, empty-state hint, field label, action, dialog,
filename-bearing confirmation, error fallback, and tool-presentation string exists in every supported
locale with matching format placeholders. External file content is never translated.

## 6. Export, import, backup, and compatibility

Native export and automatic backup add `skills/skill_db/*.md` and
`skills/skill_db/skill_meta.json`. Portable settings include `accessSkills`. Archives created
before Skills remain valid and import as an empty Skill library with the current/default setting.
Unknown archive entries remain ignored by the existing importer.

Replace import clears existing Skills before applying archive Skill entries. Merge import preserves
existing files according to the same conflict policy as saved Memory files. Import validates paths
through `SkillManager`; archive names cannot escape app-private storage. A failed Skill entry does
not corrupt Memory, Room, settings, or unrelated archive categories.

## 7. Ownership

| Owner | Responsibility | Prohibited responsibility |
|---|---|---|
| SkillManager | Private files, metadata, catalog text/revision, safe CRUD. | Prompt compilation, Provider calls, Room, or generation lifecycle. |
| SettingsManager/Repository | Persist and expose the access toggle. | File bodies or catalog construction. |
| GenerationRequestBuilder | Freeze access and exact catalog into the ordinary request/prompt. | Live re-read after admission or tool execution. |
| SkillToolProvider | Definitions, validation, and manager calls. | Independent tool loop, persistence, or admission. |
| GenerationToolExecutor | Register/execute/present Skill tools through the shared tool path. | Skill-specific Run or settlement. |
| Import/export/backup owners | Additive archive transport with Memory-equivalent conflict rules. | Silent destructive fallback. |
| Settings Skills UI | User CRUD and access presentation. | File safety, prompt authority, or generation state. |

## 8. Required verification

Focused verification covers path containment, extension normalization, create/read/list/edit/rename/
patch/delete and metadata behavior, catalog ordering/escaping/revision, tool visibility and all five
definitions, disabled access, multi-read and validation failures, immutable request catalog capture,
default/custom prompt variable compilation, context-projection invalidation, ordinary/automation
wiring, localized tool presentation, settings navigation/CRUD, Memory-parity loading/empty/list/add
states, overflow actions, dialog geometry, mutation guarding and list refresh, export/import merge and
replace, auto-backup, old-archive compatibility, locale key and format-placeholder parity, and the
explicit absence of every Active Skill artifact. The project-defined full build gate remains required.
