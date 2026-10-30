# Changelog

## 1.1.0 (not released yet)

- **Run for another player (integration API 1.1):** another mod can name a target for a run, and `{target}` is filled with that player's name. The commands still run as the player who pressed the button, with their own permissions. Naming someone else needs the new `commanddeck.run_others` permission; a target who isn't online, or who leaves during the run, stops it. The confirmation covers the target, and `runs.log` records it.
- **List what a player can run (integration API 1.1):** `Deck.macros(presser, category)` returns a player's own macros, the server's macros and the presets, in the order `/deck run` looks them up, so other mods can build their own buttons.
- **Preset permissions (command sidecar 1.1):** a preset can name a `permission` node. Players without it don't see the preset in the window or `/deck list`, and running it is refused. A copy saved with "Save as mine" keeps the check, and the editor can't remove it.
- **Scoped macro references (integration API 1.1):** `player:<id>`, `server:<id>` and `preset:<mod id>/<id>` each name exactly one macro, wherever ids collide. `/deck run` takes them too, and `Deck.macros` gives each macro its reference. The window's Run button now runs exactly the macro that's open, even when another shares its id.
- **Permission hint in the preview (integration API 1.1):** each previewed line says whether the presser has the game's permission for its command, so another mod can grey out what a player can't fully run. It's only a hint; the game still decides.
- **`Deck.macros` says what will ask first:** a macro is marked when running it will ask for confirmation (its own setting, a step marked "ask first", or a command a sidecar marks).
- **Categories for your own and the server's macros** (macro format 1.1): a new Category field in the editor.
- **The console has its own run limit:** setting `MaxRunningForConsole` (default 4), so mods acting for the server don't block each other.
- **`{look}`:** the id of the block in your crosshair, within 10 blocks. Nothing in reach: the step isn't sent.
- **Your own macros that need a permission you don't have** are shown, marked with it, and can be deleted, but not run or changed.
- **Sidecars:** a mistake in one command or preset now skips only that entry, with a warning in the server log, instead of the whole sidecar.
- **The list is in blocks, each in alphabetical order.** Commands are grouped under Favorites, No permission, and their description level. Macros and presets are grouped by category, headings in alphabetical order, with "Other" last for those without one. The buttons show just the name, and a name too long for one line wraps onto the next instead of being cut off.
- Condition loops and calling other macros are now planned for 1.2; a macro that uses them is refused with "needs CommandDeck 1.2".

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
