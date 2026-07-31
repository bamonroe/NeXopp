"""Load, query, and persist the TOML task files (TODO.toml / FINISHED.toml).

The two files share one schema (see ``SKILL.md``). Each is a document with a
``[meta]`` header and an array of ``[[task]]`` tables. Active work lives in
TODO.toml; finished work is archived newest-first in FINISHED.toml. This module
is the single place that knows the field order and file layout, so every write
produces a byte-stable, diff-friendly file.
"""

from __future__ import annotations

import datetime
import os
import re

import tomlio

SCHEMA_VERSION = 1

# Enumerations. `add`/`edit` validate against these; `validate` flags strays.
STATUSES_ACTIVE = ("active", "in-progress", "blocked")
STATUS_FINISHED = "finished"
CATEGORIES = ("feature", "bug", "docs", "refactor", "test", "chore")
URGENCIES = ("low", "medium", "high", "critical")
_URGENCY_RANK = {name: i for i, name in enumerate(URGENCIES)}

# Field write-order within a [[task]] block. `completed` only appears in the
# archive; absent fields are simply skipped. `rebuild`/`emulator_debug` are
# TODO-only build hints (like `urgency`/`order`) dropped when a task is archived.
_TASK_FIELDS = (
    "id",
    "title",
    "description",
    "status",
    "category",
    "urgency",
    "order",
    "created",
    "completed",
    "tags",
    "rebuild",
    "emulator_debug",
)

_TODO_HEADER = """\
# TODO — active task list for xopp_android.
#
# Single source of truth for active work (see CLAUDE.md -> documentation map).
# Edit through the todo skill so ids, ordering, and metadata stay consistent:
#   scripts/todo.sh <command>      (see .claude/skills/todo/SKILL.md)
# Hand-editing works too — it is plain TOML — but the skill keeps it tidy.
"""

_FINISHED_HEADER = """\
# FINISHED — completed-work archive for xopp_android.
#
# Append-only, newest first (see CLAUDE.md -> documentation map). Tasks land
# here when `scripts/todo.sh done <id>` moves them out of TODO.toml.
"""


def find_repo_root(start=None):
    """Walk up from *start* (default: CWD) until a directory holds .git."""
    path = os.path.abspath(start or os.getcwd())
    while True:
        if os.path.isdir(os.path.join(path, ".git")):
            return path
        parent = os.path.dirname(path)
        if parent == path:
            raise FileNotFoundError("not inside a git repository")
        path = parent


def todo_path(root=None):
    return os.path.join(root or find_repo_root(), "TODO.toml")


def finished_path(root=None):
    return os.path.join(root or find_repo_root(), "FINISHED.toml")


def load(path):
    """Read a task document into ``{"meta": {...}, "tasks": [...]}``."""
    raw = tomlio.load(path)
    return {"meta": raw.get("meta", {}), "tasks": raw.get("task", [])}


def _dump(doc, header, meta_order):
    """Serialize a task document to TOML text with a stable layout."""
    out = [header.rstrip("\n"), "",
           tomlio.fmt_kv("schema_version", SCHEMA_VERSION), "", "[meta]"]
    meta = doc["meta"]
    for key in meta_order:
        if key in meta and key != "rules":
            out.append(tomlio.fmt_kv(key, meta[key]))
    rules = meta.get("rules")
    if rules:
        out.append("")
        out.append("[meta.rules]")
        for key, value in rules.items():
            out.append(tomlio.fmt_kv(key, value))
    for task in doc["tasks"]:
        out.append("")
        out.append("[[task]]")
        for field in _TASK_FIELDS:
            if field in task and task[field] is not None:
                out.append(tomlio.fmt_kv(field, task[field]))
    return "\n".join(out) + "\n"


def save_todo(doc, path):
    order = ("title", "kind", "purpose", "documentation_map", "archive")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(_dump(doc, _TODO_HEADER, order))


def save_finished(doc, path):
    order = ("title", "kind", "purpose", "documentation_map", "source", "order")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(_dump(doc, _FINISHED_HEADER, order))


# --- queries ---------------------------------------------------------------

def urgency_rank(task):
    """Higher = more urgent; unknown urgencies sort lowest."""
    return _URGENCY_RANK.get(task.get("urgency"), -1)


def sort_active(tasks):
    """Most urgent first, then by explicit `order`, then by id."""
    return sorted(
        tasks,
        key=lambda t: (-urgency_rank(t), t.get("order", 1_000_000), t.get("id", "")),
    )


def counts_by(tasks, field):
    """Count tasks grouped by one field, as an ordered dict (desc by count)."""
    tally = {}
    for task in tasks:
        key = task.get(field, "(none)")
        tally[key] = tally.get(key, 0) + 1
    return dict(sorted(tally.items(), key=lambda kv: (-kv[1], str(kv[0]))))


def find(tasks, task_id):
    for task in tasks:
        if task.get("id") == task_id:
            return task
    return None


# --- mutations -------------------------------------------------------------

def slugify(text, existing=()):
    """Kebab-case slug from *text*, made unique against *existing* ids."""
    base = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-") or "task"
    slug, n = base, 2
    taken = set(existing)
    while slug in taken:
        slug, n = f"{base}-{n}", n + 1
    return slug


def next_order(tasks):
    """Next `order` value, 10 past the current max (10, 20, 30, ...)."""
    orders = [t.get("order", 0) for t in tasks]
    return (max(orders) + 10) if orders else 10
