# CommandDeck

**An in-game command browser and macro builder for Hytale.** Type `/deck`, pick any command (vanilla or from a mod), fill in its form, and press Run. Save favorites, and chain commands into macros with repeats and waits.

Every button shows the exact command line it runs, so the window teaches the commands instead of hiding them.

Status: **1.0.0**, the first release. Tested on Hytale server **0.7.4**.

## What it does

- **Command browser.** Every registered command is listed, with search and categories. Each command has one of three description levels:
  - **typed:** the game lists its arguments and their types, so the form has one field per argument;
  - **described:** a mod's sidecar file adds labels, help text, ranges and examples;
  - **raw:** the game gives only a name and help text, so the form shows the help and one free-text field, with a history.
- **A preview of the exact line**, always visible. Escaping is done for you. If a value can't be sent through the game's command parser at all, the form marks the characters and says so; it never changes your value to fit.
- **Placeholders:** `{self}`, `{target}`, `{x} {y} {z}` and `{look}`, filled in when the command runs.
- **Favorites.** Star a command, and filter the list to your favorites.
- **Macros.** Build them in the Macros tab from four kinds of steps: a command, a sequence, a repeat (N times) and a wait (ticks or seconds). Each player has their own macros; the server can have shared ones, and mods can ship ready-made presets.
- **Runs tab.** Every run going now, with a Stop button.

## Safety

- **A run never freezes the game.** One step per tick by default, on the server's scheduler.
- **Limits.** The server owner sets the most repeats, the deepest nesting, the longest run time, the steps per tick and the runs per player. A macro that breaks a limit is refused or stopped, with the reason.
- **Stop always works:** the Stop button, `/deck stop`, and `/deck stop all` for operators. Commands already sent are not undone.
- **Your permissions, never the author's.** Every command runs as the player who pressed the button, and the game checks it as usual. If the game refuses a step, the run stops there.
- **Dangerous commands ask first.** A command marked "confirm" or "can damage your save", or a macro marked "Ask before running", shows a confirmation every time, inside macros too.
- **Everything is logged** to `runs.log` in CommandDeck's data folder: who ran what, and when.

## Install

1. Put `CommandDeck-1.0.0.jar` in your server's `mods` folder (singleplayer: `%APPDATA%\Hytale\UserData\Mods\`).
2. Nothing else is needed.
3. Type `/deck`.

## Commands and permissions

| Command | What it does |
| --- | --- |
| `/deck` | Opens the window |
| `/deck run <id>` | Runs one of your macros, a server macro or a preset |
| `/deck confirm` | Runs what's waiting for your confirmation (within 60 seconds) |
| `/deck stop` | Stops your runs |
| `/deck stop all` | Stops everyone's runs (operators) |
| `/deck list` | Lists what you can run, and your runs |
| `/deck reload` | Reads the sidecars, macros and settings again (operators) |
| `/deck help` | Shows this list |

`/deck` works from the server console too, apart from opening the window.

**Permissions:**

- `commanddeck.use`: use CommandDeck on a server. In singleplayer everyone may use it by default (see the settings).
- `commanddeck.macros.edit`: save your own macros. Operators may always.
- `commanddeck.operator`: `/deck stop all` and `/deck reload`. The singleplayer world's owner and the console have it.
- `commanddeck.run_others` (from 1.1.0): lets another mod run a macro *for* another player, filling `{target}` with their name (Generous Operator's gifts). The commands still run with your own permissions.

CommandDeck never grants a command: the game still checks every command as the player who runs it.

## Settings

The settings are in `mods/Hledjolf_CommandDeck/config.json`. With [Mod Settings Manager](https://github.com/Hledjolf/ModSettingsManager) installed (optional), operators can change them from `/modsettings`, with labels and ranges, and every change applies without a restart.

| Setting | Default | What it does |
| --- | --- | --- |
| `Enabled` | true | Off: no new run starts. Runs already going keep going. |
| `EveryoneInSingleplayer` | true | In singleplayer, every player may use CommandDeck and save macros. |
| `MaxIterations` | 100 | The highest count a repeat may have (1 to 100000). |
| `MaxDepth` | 8 | How many repeats and groups may sit inside each other (1 to 64). |
| `MaxRuntimeSeconds` | 300 | A run that takes longer stops before its next step. |
| `StepsPerTick` | 1 | How many commands one run may send in a tick. |
| `MaxRunningPerPlayer` | 1 | How many runs one player may have going at once. |
| `MaxRunningForConsole` | 4 | How many runs the server console may have going at once. Other mods use the console to act for the server. |
| `PlainSender` | false | Send through the server's own handler. Refusals and replies can't be seen then, so every step is marked uncertain. Only for a mod whose commands misbehave. |
| `CommandTimeoutSeconds` | 10 | A command that hasn't finished after this long counts as sent (uncertain), and the run moves on. |

Saved data lives next to it: `macros/server/` (the server's macros), `macros/players/<uuid>/` (each player's), `prefs/` (favorites and history), `sidecars/` (the owner's sidecar overrides) and `runs.log`.

## Compatibility

- Hytale server **0.7.4**.
- Dedicated servers and singleplayer.
- One jar, no dependencies.
- Macro steps in 1.0.0: command, sequence, repeat and wait. Loops on a condition and calling other macros are not in this version.

## For mod authors

Your mod's commands show up in CommandDeck as they are. To make them better, ship a **command sidecar**: `commanddeck/commands.json` inside your jar. It adds labels, descriptions, argument types and ranges, examples, categories, a danger flag (`confirm` or `save_risk`), and preset macros that appear as buttons. When CommandDeck isn't installed, nothing reads it, so it costs your mod nothing.

From 1.1.0, a preset can carry a `permission` node: players without it don't see the preset and can't run it, and a copy they save keeps the check. A mistake in one command or preset of a sidecar skips only that entry, with a warning in the server log, instead of the whole file.

The format, its JSON schema and a complete example are in [`template/`](template/README.md). Server owners can override a mod's sidecar with their own file in `mods/Hledjolf_CommandDeck/sidecars/`.

Other mods can also run command lines and macros through CommandDeck's integration API (`commanddeck.api.Deck`), with the same rules as a button press. API 1.1 (CommandDeck 1.1.0) adds a run's target (`{target}`) and `macros(presser, category)`, which lists what a player can run so your mod can build its own buttons. Check `apiVersion()` before using them.

## License

MIT, copyright (c) 2026 Hledjolf. See [LICENSE](LICENSE). The sidecar format and its schema are MIT too.
