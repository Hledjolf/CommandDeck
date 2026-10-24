# Changelog

## 1.0.0 (2026-10-24) – first release

Tested on Hytale server 0.7.4.

- **Command browser** (`/deck`): every registered command, vanilla or from a mod, with search, categories and filters (favorites, the ones you have permission for, and each description level).
- **Three description levels:** typed (one field per argument, from the game), described (a mod's sidecar adds labels, help, ranges and examples) and raw (the help text and a free-text field with a history).
- **The exact command line is always shown** before you run it. Escaping is done for you; a value that can't be sent is marked, never changed.
- **Placeholders:** `{self}`, `{target}`, `{x} {y} {z}` and `{look}`.
- **Favorites:** star commands and filter the list to them.
- **Macros:** build them in the window from command, sequence, repeat and wait steps; your own macros, the server's shared macros, and presets that mods ship.
- **Runs tab** with a Stop button for every run.
- **Safety:** one step per tick by default; operator limits on repeats, nesting, run time, steps per tick and runs per player; commands always run with the presser's permissions; dangerous commands and marked macros ask first; every run is logged to `runs.log`.
- **Commands:** `/deck run`, `/deck list`, `/deck stop` (and `stop all` for operators), `/deck confirm`, `/deck reload`, `/deck help`.
- **Permissions:** `commanddeck.use`, `commanddeck.macros.edit`, `commanddeck.operator`. In singleplayer everyone may use it by default.
- **Settings** in `config.json`, or in Mod Settings Manager's window if it's installed; every change applies without a restart.
- **For mod authors:** the command sidecar format (`commanddeck/commands.json`) with a JSON schema and an example, and an integration API for running commands and macros from another mod.
