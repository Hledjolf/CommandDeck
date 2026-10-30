# CommandDeck command sidecar (format 1.1)

CommandDeck turns every command in the game into a button: a form with a field per argument, help for each one, and a preview of the exact command line it will run. A **command sidecar** is a small JSON file your mod ships to make its commands into better buttons.

**Your mod works without one, and better with one.** Where the game lists commands at runtime (Hytale, for example), CommandDeck already shows yours. A sidecar adds the labels, descriptions, examples, ranges and safety flags that the game doesn't know. On games where commands can't be listed at runtime, the sidecar is how your commands appear at all.

## Files in this folder

| File | What it is |
| --- | --- |
| `README.md` | This spec. |
| `command-sidecar.schema.json` | JSON Schema (2020-12) for the format. Point your editor at it to get completion and checks. |
| `commands.json` | A complete example. Copy it into your mod and edit it. |

## Quick start

1. Copy `commands.json` into your mod at `commanddeck/commands.json` (inside the jar, or inside the mod's folder on games without jars).
2. Set `mod.id` to your mod's id, and describe your commands.
3. Validate it against `command-sidecar.schema.json`.
4. Load the game with CommandDeck installed. Problems are written to CommandDeck's log, with the reason.

## Where CommandDeck looks

- **Your mod:** `commanddeck/commands.json` inside the mod.
- **A server owner's override:** `sidecars/<mod id>.json` in CommandDeck's data folder. On Hytale that's `mods/Hledjolf_CommandDeck/sidecars/`, and the `:` in a mod id is written `_`, since file names can't hold it: `Hledjolf_CalendarSeasons.json`. An override wins over the mod's own sidecar, so owners can fix or translate descriptions without touching your mod.
- Sidecars are read when the game starts and on `/deck reload`.

## Three description levels

CommandDeck gives every command one of three levels:

| Level | Where it comes from | What the form shows |
| --- | --- | --- |
| **typed** | The game's command registry lists the command with its argument names and types | One field per argument, matched to its type |
| **described** | Your sidecar adds types and text to a listed command, or describes a command the game can't list | The same, plus your labels, help and examples |
| **raw** | The game gives only a name and help text | The help text and one free-text field |

The raw level is common outside Java games, and it works: the help text is shown in full, the text field keeps a history, and the preview still applies the game's escaping rules. A sidecar is how a raw command becomes a described one.

## How your sidecar is merged with what the game reports

The game decides which commands exist. Your sidecar refines them.

1. **Existence:** a command must be registered in the running game. A sidecar entry for a command that isn't registered (its mod isn't installed, or the command was renamed) is ignored, with a warning in the log (`sidecar_unknown_command`). On games that can't list commands, the sidecar is the list.
2. **Detail:** your label, description, category, examples, danger flag and arguments are added.
3. **Argument types:** if the game reports a type, your sidecar may narrow it (an `int` gets `min`/`max`, a `string` becomes an `enum`) but not change it to something incompatible. A narrowing is the same type, or a `string` made into `enum`, `player`, `item`, `block`, `entity` or `duration`. Anything else logs a warning (`sidecar_type_conflict`), and the game's type wins; your bounds, options, default and text still apply.
4. **Help text:** the game's help text is kept. Your `description` is shown as well, not instead.
5. **Permission:** your `permission` is shown to the player, nothing more. The game always decides who may run a command.
6. **Arguments are matched by `name`,** not by position. On a raw command, your arguments are used as written, and the command becomes described. On a typed command, an argument name the game doesn't know is ignored, with a warning (`sidecar_unknown_arg`), and the game's `style` and `required` win without a warning.
7. **Variants are matched by their positional argument names,** in order. Each of your arguments counts with the game's style wherever that form knows its name, so restyling an argument can't break a match. A variant that matches none of the game's forms is ignored, with a warning (`sidecar_unknown_variant`). On games that can't list commands, your variants are the list.

## Fields

Top level:

- `format`: always `"commanddeck.commands"`.
- `format_version`: `"1.0"`, or `"1.1"` if you use a 1.1 field (preset `permission`).
- `mod`: `id` (required), `name`, `version`.
- `commands[]`: your commands (below).
- `presets[]`: ready-made macros (below). Optional.

Each entry in `commands[]`:

- `name` (required): without the slash. A subcommand is written with spaces: `calendar set day`.
- `aliases`, `label` (up to 60 characters), `description` (up to 1000), `category` (up to 40).
- `danger`: `none` (default), `confirm` (ask before running), or `save_risk` (ask, and warn that it can damage a save).
- `permission`: the permission node the game checks, if you know it. Shown only.
- `args[]`: the arguments (below).
- `variants[]`: other forms of the same command (below).
- `examples[]`: `line`, plus an optional `explanation`. Write `line` exactly as a player would type it in chat.
- `hidden`: `true` keeps a command out of the browser. It can still be run from a macro.

Each entry in `args[]`:

- `name` and `type` (both required).
- `style`: `positional` (default), `named`, or `flag` (see below).
- `required` (default `true`), `default`.
- `min`, `max`, `step`: for numbers.
- `options`: the allowed values, for type `enum`.
- `description`: help shown next to the field (up to 500 characters).
- `placeholders` (default `true`): whether `{self}`, `{target}`, `{x}` and the others may be used in this field.

### Argument types

`string` (one word), `greedy_string` (the rest of the line), `int`, `float`, `bool`, `enum`, `player`, `item`, `block`, `entity`, `position` (three numbers or placeholders), `duration`.

Which types get a picker (a player list, an item search) depends on the game. Anything without a picker is a text field.

### Argument styles and variants

Some games have more than positional arguments:

- `style`:
  - `positional`: by place in the line.
  - `named`: `--name value`, in any order after the positional arguments.
  - `flag`: `--name` on its own. Type `bool`; present means true.

  CommandDeck writes named arguments and flags after the positional ones, in the order you list them, and leaves out any that are empty or false.
- `variants[]`: other forms of the same command, each with its own `args` and an optional `label` and `description`. For example, a teleport command might take `<targetPlayer>`, or `<x> <y> <z>`. The command's own `args` are the main form. The window shows a variant picker above the form. Every variant runs the same command name.

## Escaping

You never write escape characters into a field's value. CommandDeck escapes values it puts into a line itself, using the game's rules. If a value can't be sent through the game's command parser at all, the form says so and won't run it. It never sends a changed value silently.

**Command lines you write are sent as written.** That covers `examples[].line` and the `line` of a preset's `command` steps. Write them exactly as a player would type them in chat, including any escaping the game needs there. CommandDeck only fills placeholders. Test them in chat, not at the server console: some games' consoles change a line before the game reads it.

## Placeholders

Filled in when the command runs, from the player who pressed the button:

| Placeholder | Value |
| --- | --- |
| `{self}` | The player's name |
| `{target}` | The player chosen in the window, or the player who pressed the button |
| `{x} {y} {z}` | The player's position, in the game's own units (whole blocks on Hytale) |
| `{look}` | What the player is looking at, where the game supports it |

An unknown placeholder, or one the game can't fill, stops the command with a message. It's never sent as text.

## Presets

A preset is a ready-made macro your mod ships: a button that runs several commands. Each entry in `presets[]` has `id` (required: lowercase letters, digits, `_` and `-`), `name` (required), `description`, `category`, `permission` (1.1, optional), and `macro` (required).

**`permission` (since 1.1):** a permission node a player must hold to see or run the preset, for example `"permission": "cheatmenu.use"`. Players without it don't see the preset, and running it is refused with a message. It only adds a check: the game still checks every command, and the field never grants anything. Operators and the singleplayer world owner pass. When a player saves the preset as their own, the copy keeps the check, and they can't remove it.

**Categories:** CommandDeck's Macros tab groups presets under a heading per `category`, so give related presets the same one (for example `My Mod: Items`).

**Naming a preset from another mod (since 1.1):** a preset's full reference is `preset:<your mod id>/<preset id>`, for example `preset:MyGroup:MyMod/jump-to-spring`. Other mods (a gift catalog, a test plan) should use that form, so two mods' presets with the same id never get mixed up. People typing `/deck run` can still use the plain id.

### Preset macros

`macro` is a tree of steps. Format 1.0 has four kinds:

| `type` | Fields | What it does |
| --- | --- | --- |
| `command` | `line`, optional `confirm` | Runs one command line, after filling placeholders. |
| `sequence` | `steps[]` | Runs its steps in order. |
| `repeat` | `times`, `body` | Runs `body` that many times. |
| `wait` | `ticks` or `seconds` (not both) | Pauses. |

Every step may have a `note`, shown in the editor and ignored when running.

How presets run:

- One step per game tick by default. A preset never freezes the game.
- Every command runs with the permissions of the player who pressed the button, never yours. If the game refuses a command, the preset stops there.
- A stop button and `/deck stop` always work. Commands already sent are not undone.
- The server owner sets limits on repeats, nesting depth and running time. A preset that breaks one is refused or stopped, with the reason.
- A command whose sidecar entry says `confirm` or `save_risk` always asks first, inside a preset too.

## Versions

`format_version` is `major.minor`. A minor version only adds optional things, so a 1.0 sidecar stays valid in every 1.x.

**Using a newer field needs a newer CommandDeck.** CommandDeck 1.0 refuses a sidecar with a field it doesn't know, so a sidecar that uses `permission` won't load on 1.0 at all. If you use it, set `format_version` to `"1.1"` and require CommandDeck 1.1 or later in your mod's manifest (on Hytale, `"Hledjolf:CommandDeck": ">=1.1.0"`). From CommandDeck 1.1 on, a problem in one command or preset skips only that entry, with a warning in the log, and the rest of the sidecar loads. If a preset uses a step type newer than the installed CommandDeck understands, that preset is refused with a clear message. CommandDeck never guesses.

## License

MIT, like the rest of CommandDeck's published code. That covers this README, the schema and the example, so copy `commands.json` into your mod and change it however you like.
