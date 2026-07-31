---
name: todo
description: >-
  Read and edit this project's task list. Use whenever the user says "add this
  to the todo", "what's next / what's on the todo", "mark X done", "edit the
  todo", "how many tasks / how many bugs are left", or otherwise wants to query
  or change TODO.toml / FINISHED.toml. The task list is TOML, not Markdown —
  drive it through this skill's CLI so ids, ordering, and metadata stay
  consistent.
---

# Todo skill

The active task list lives in **`TODO.toml`** and the completed-work archive in
**`FINISHED.toml`** at the repo root. They are the single source of truth for
what's in flight and what has shipped (see `CLAUDE.md` → documentation map).
Both are plain TOML, edited through a small dependency-free Python CLI so every
write stays diff-friendly and every task carries full metadata.

## How to run it

```sh
scripts/todo.sh <command> [options]          # operator wrapper (from repo root)
# or directly:
python3 .claude/skills/todo/scripts/todo.py <command> [options]
```

Run `scripts/todo.sh <command> --help` for a command's options.

## The schema

Each file is a document: a `[meta]` header (title, purpose, rules) then an
array of `[[task]]` tables. A task carries **more** metadata rather than less:

| Field         | Where            | Meaning                                             |
|---------------|------------------|-----------------------------------------------------|
| `id`          | both             | stable kebab-case slug (the handle for every command) |
| `title`       | both             | short one-line summary                              |
| `description` | both             | the detail                                          |
| `status`      | both             | active · in-progress · blocked (TODO) / finished (archive) |
| `category`    | both             | feature · bug · docs · refactor · test · chore     |
| `urgency`     | TODO             | low · medium · high · critical                     |
| `order`       | TODO             | manual sort key (10, 20, 30…); lower = sooner       |
| `created`     | both             | date the task was added (`YYYY-MM-DD`)             |
| `completed`   | archive          | date it shipped                                     |
| `tags`        | TODO             | freeform string list                                |
| `rebuild`     | TODO             | rebuild the Android app for this task? (default `true`) |
| `emulator_debug` | TODO          | run the full emulator verify loop for this task? (default `true`) |

`rebuild` and `emulator_debug` are **build hints** for whoever works the task,
both defaulting to **`true`** (yes / yes) — the normal "build the APK, then
install and exercise it on the emulator" loop. Set them **`false`** for a run of
rapid-fire tweaks that don't each need a fresh rebuild or a full emulator pass;
you still build/verify once at the end. Like `urgency`/`order`, they're
TODO-only and are dropped when a task is archived.

Active tasks list most-urgent-first, then by `order`. The archive is
newest-`completed`-first.

## Commands

- **`list`** `[--finished] [--status S] [--category C] [--json]` — list tasks.
- **`show <id>`** `[--json]` — print one task with its full description.
- **`stats`** `[--json]` — totals plus counts by status, category, and urgency
  (active) and by category (finished). This is the "how many …" answer.
- **`count`** `[--finished] [--by status|category|urgency]` — a raw count, or
  a grouped tally.
- **`add --title T --description D`** `[--category C] [--urgency U]
  [--status S] [--tag t …] [--id ID] [--no-rebuild] [--no-emulator-debug]` —
  append an active task. The `id` is a slug of the title (made unique) and
  `order` auto-increments unless given. `rebuild`/`emulator_debug` default to
  yes; pass `--no-rebuild` / `--no-emulator-debug` to turn either off.
- **`edit <id>`** `[--title|--description|--status|--category|--urgency|--order
  …] [--add-tag t] [--rebuild|--no-rebuild] [--emulator-debug|--no-emulator-debug]`
  — change fields on an active task.
- **`done <id>`** `[--date YYYY-MM-DD]` — move an active task into
  `FINISHED.toml`, stamped `completed` (today unless `--date`), newest-first.
- **`remove <id>`** `[--reason "…"]` — drop an active task (e.g. descoped).
- **`validate`** — lint both files: required fields, unique ids, valid enum
  values, archive tasks have a `completed` date. Exits non-zero on any problem.
- **`migrate`** `[--created YYYY-MM-DD]` — one-shot cleanup of legacy/rough TOML
  into the current schema. Already run during the Markdown→TOML migration; kept
  for re-runs.

## Working rules (mirror of `CLAUDE.md`)

- When you finish a task (built, tested, documented), run **`done <id>`** in the
  same commit that completes the work — don't leave shipped items in `TODO.toml`.
- When you notice the next thing to build, **`add`** it rather than losing it.
- Prefer the CLI over hand-editing so metadata and ordering stay consistent; if
  you do hand-edit, run **`validate`** afterward.
