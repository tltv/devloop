---
name: vaadin-devloop
description: Make Java or CSS edits live in the already-running Vaadin app and verify them in the browser. Use after editing anything under src/main/java or src/main/resources, to start or restart the app, or when asked whether a change is actually live. A daemon owns the app process, so this replaces running the app through Maven.
when_to_use: Triggers include "make this live", "is the change running", "start the app", "reload", "hot reload", "apply my edits", "why doesn't the page show my change", any edit to a Vaadin view, component or stylesheet, and any request to check the app in a browser.
allowed-tools: Bash(./vaadin-dev *), Read
---

# Vaadin dev loop

The instructions are shared with every other agent on this repository and live in
**[`.agents/skills/vaadin-devloop/SKILL.md`](../../../.agents/skills/vaadin-devloop/SKILL.md)**.

**Read that file now** — it is the cycle, the command set, what is in the loop, and how to read
an `apply` outcome in one line each. Its
[reference.md](../../../.agents/skills/vaadin-devloop/reference.md) carries the detail: the full
output vocabulary, which edits need a page reload, pom/classpath semantics, the `--json` schema,
environment variables, and what to do when the loop goes wrong.

## Bindings for this session

The shared file is deliberately tool-agnostic. These are the tools to use for it here.

- Verify with the **Playwright MCP** tools: `browser_navigate` once, then `browser_snapshot` /
  `browser_evaluate` for the assertions the shared reference describes, and
  `browser_console_messages` after each change (a `/favicon.ico` 404 is normal noise). The
  first snapshot after `browser_navigate` is usually empty — Vaadin renders client-side.
- Use the **Vaadin MCP server** (`search_vaadin_docs`, `get_component_java_api`,
  `get_component_styling`, `get_theme_css_properties`) instead of recalling API from memory;
  check the Vaadin version in the target application's `pom.xml`.
