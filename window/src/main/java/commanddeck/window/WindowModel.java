package commanddeck.window;

import commanddeck.api.CheckResult;
import commanddeck.api.RunItem;
import commanddeck.api.RunRequest;
import commanddeck.api.RunStatus;
import commanddeck.api.StartResult;
import commanddeck.command.Arg;
import commanddeck.command.ArgStyle;
import commanddeck.command.ArgType;
import commanddeck.command.CommandCatalog;
import commanddeck.command.CommandInfo;
import commanddeck.command.Danger;
import commanddeck.command.Example;
import commanddeck.command.Level;
import commanddeck.command.Preset;
import commanddeck.command.Variant;
import commanddeck.deck.MacroLibrary;
import commanddeck.deck.MacroLookup;
import commanddeck.json.Json;
import commanddeck.macro.Calls;
import commanddeck.macro.ConditionPort;
import commanddeck.macro.Macro;
import commanddeck.macro.MacroReader;
import commanddeck.macro.MacroValidator;
import commanddeck.macro.MacroWriter;
import commanddeck.macro.Placeholders;
import commanddeck.macro.Problem;
import commanddeck.window.MacroShelf.Scope;
import commanddeck.window.ViewOps.Child;
import commanddeck.window.ViewOps.Entry;
import commanddeck.window.ViewOps.Part;
import commanddeck.window.ViewOps.Ref;
import commanddeck.window.ViewOps.RowKind;
import commanddeck.window.ViewOps.Trigger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One player's CommandDeck window, as plain state plus the commands that draw it ({@link ViewOps}). Three tabs:
 *
 * <ul>
 * <li><b>Commands:</b> the command browser on the left (search, a filter by favorites, category or level),
 *     the selected command's form on the right (one field per argument, a variant picker, or the free-text
 *     field with its history for a raw command), the line it would send, and Run.
 * <li><b>Macros:</b> the player's own macros, the server's and the mods' presets on the left; the editor on
 *     the right (a list of steps with up, down, in, out and delete buttons), with Save, Run and Delete.
 * <li><b>Runs:</b> the runs going now, each with Stop.
 * </ul>
 *
 * Every run goes through the deck, so the window has no rules of its own: a step that needs confirmation
 * shows the deck's prompt, and only Yes starts it, with the deck's token. A form value that can't be sent is
 * shown with the offending characters marked, and is never changed to fit.
 *
 * <p>Fields send their values as they change, but clicks also carry every field's current value, because
 * a client may drop events while an update is unacknowledged. Each redraw of the right side bumps a
 * generation number, and events from an older one are ignored.
 *
 * <p>The model has no game or toolkit in it: it talks to the deck through a {@link DeckSession}, builds lines
 * through the game's {@link LineRules}, and draws through a renderer that follows its {@link ViewOps}.
 */
public final class WindowModel {
    /** Shown on a command the game says this player may not run. Running stays possible: the game decides. */
    public static final String NO_PERMISSION = "You don't have the game's permission for this command. You can still press Run, but the game "
        + "will probably refuse it.";
    public static final String NOT_SENT = "Not sent. The message above says what to change.";

    /** The most commands the list shows at once; the search narrows it. */
    public static final int MAX_LIST = 150;
    /** The most ids an item or block picker offers at once; typing in the field narrows them. */
    public static final int MAX_MATCHES = 40;
    /** The editor's rows before the first step: id, name, ask first, where it is saved, description, category, show notes. */
    public static final int HEADER_ROWS = 7;
    /** The header fields captured with every editor click, in row order (the notes switch is view state). */
    private static final String[] HEADER_FIELDS = {"id", "name", "danger", "where", "description", "category"};
    /** The Macros tab's heading for macros and presets without a category; it comes last. */
    public static final String OTHER = "Other";
    public static final String SOURCE = "window";

    public enum Tab {
        COMMANDS, MACROS, RUNS
    }

    /** What a form row holds. */
    private enum Kind {
        NOTE, VARIANT, FIELD, RAW, RECENT, MATCHES
    }

    private record Row(Kind kind, Arg arg) {}

    /** Waiting for the presser's Yes: a run with its token, deleting a macro, or leaving unsaved changes for another macro. */
    private record Pending(RunRequest request, String token, Scope deleteScope, String deleteId, String switchTo) {
        static Pending run(RunRequest request, String token) {
            return new Pending(request, token, null, null, null);
        }

        static Pending delete(Scope scope, String id) {
            return new Pending(null, null, scope, id, null);
        }

        static Pending leave(String key) {
            return new Pending(null, null, null, null, key);
        }
    }

    private final DeckSession session;
    private final LineRules lines;
    private Tab tab = Tab.COMMANDS;
    private String query = "";
    private String filter = "all";
    private int gen;
    private String status = "";
    private Pending pending;

    // Commands tab.
    private String commandName;
    private int variant = -1;
    private final Map<String, String> values = new LinkedHashMap<>();
    private String rawText = "";
    private final List<Row> rows = new ArrayList<>();

    // Macros tab.
    private String macroKey;
    private MacroDraft draft;
    private String draftOriginalId;
    private Scope draftScope = Scope.PLAYER;
    private boolean draftReadOnly;
    /**
     * The permission node the presser needs for the open macro, when it is their own and they lack it: they see
     * it and may delete it, but can't run or change it. Null otherwise.
     */
    private String draftNeeds;
    private boolean dirty;
    private boolean showNotes;
    /** The editor's rows after the header, as last drawn. */
    private final List<EditorRow> layout = new ArrayList<>();
    /** The condition types this game offers, by type, in the order offered, as last drawn. */
    private final Map<String, ConditionPort.Form> forms = new LinkedHashMap<>();
    private CheckFields checkFields = new CheckFields(null, 3);

    public WindowModel(DeckSession session, LineRules lines) {
        this.session = session;
        this.lines = lines;
    }

    public Tab tab() {
        return tab;
    }

    public String status() {
        return status;
    }

    public int generation() {
        return gen;
    }

    public MacroDraft draft() {
        return draft;
    }

    /** Whether the macro being edited has changes that aren't saved. */
    public boolean unsaved() {
        return dirty && draft != null;
    }

    public String selectedCommand() {
        return commandName;
    }

    /** Everything after the window's own layout: the static bindings plus both sides. */
    public ViewOps open() {
        ViewOps ops = new ViewOps();
        for (Tab t : Tab.values()) ops.bind(tabRef(t), Trigger.ACTIVATE, "tab", Map.of("Tab", t.name()), Map.of());
        ops.value(Ref.of(Part.SEARCH), query);
        ops.bind(Ref.of(Part.SEARCH), Trigger.CHANGE, "search", Map.of(), Map.of("Query", Ref.of(Part.SEARCH)));
        ops.bind(Ref.of(Part.FILTER), Trigger.CHANGE, "filter", Map.of(), Map.of("Value", Ref.of(Part.FILTER)));
        ops.bind(Ref.of(Part.CONFIRM_YES), Trigger.ACTIVATE, "confirm", Map.of(), Map.of());
        ops.bind(Ref.of(Part.CONFIRM_NO), Trigger.ACTIVATE, "cancel", Map.of(), Map.of());
        // The window outlives its page: a question left open when it was closed is dropped, and unsaved edits wait.
        pending = null;
        if (dirty && draft != null) status = "Your unsaved changes to " + label(draft) + " are still here, in the Macros tab.";
        renderAll(ops);
        return ops;
    }

    /** Whether a renderer should redraw this event's result only once typing pauses (see {@link #deferredOps}). */
    public static boolean deferred(String action) {
        return "search".equals(action) || "field".equals(action) || "stepEdit".equals(action) || "header".equals(action)
            || "checkEdit".equals(action);
    }

    /** The redraw for a deferred event, built from the latest state. */
    public ViewOps deferredOps(String action) {
        ViewOps ops = new ViewOps();
        if ("search".equals(action)) {
            renderList(ops);
        } else if ("field".equals(action)) {
            renderMatches(ops);
            renderPreview(ops);
        } else {
            renderValidation(ops);
        }
        return ops;
    }

    /**
     * Handles one event: its action, its params and the captured values by name. The returned commands are a
     * partial update (possibly none).
     */
    public ViewOps onEvent(String action, Map<String, Object> data) {
        ViewOps ops = new ViewOps();
        if (action == null) return ops;
        switch (action) {
            case "tab":
                switchTab(ops, str(data.get("Tab")));
                break;
            case "search":
                query = str(data.get("Query"));
                break;
            case "filter":
                filter = str(data.get("Value"));
                if (filter.isEmpty()) filter = "all";
                renderList(ops);
                break;
            case "pick":
                pick(ops, str(data.get("Key")));
                break;
            case "variant":
                if (!sameGen(data)) break;
                captureForm(data);
                variant = parseInt(str(data.get("Value")), -1);
                renderRight(ops);
                break;
            case "field":
                if (!sameGen(data)) break;
                storeField(parseInt(str(data.get("Row")), -1), data.get("Value"));
                break;
            case "recent":
                if (!sameGen(data)) break;
                recent(ops, str(data.get("Value")));
                break;
            case "match":
                if (!sameGen(data)) break;
                match(ops, parseInt(str(data.get("Row")), -1), str(data.get("Value")));
                break;
            case "run":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureForm(data);
                runForm(ops);
                break;
            case "favorite":
                favorite(ops);
                break;
            case "addToMacro":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureForm(data);
                addToMacro(ops);
                break;
            case "confirm":
                confirm(ops);
                break;
            case "cancel": {
                Pending was = pending;
                pending = null;
                hideConfirm(ops);
                setStatus(ops, was != null && was.switchTo() != null ? "Kept your changes." : "Cancelled. Nothing ran.");
                break;
            }
            case "header":
                if (!sameGen(data)) break;
                header(str(data.get("Field")), data.get("Value"));
                break;
            case "stepEdit":
                if (!sameGen(data)) break;
                stepEdit(parseInt(str(data.get("Row")), -1), str(data.get("Field")), data.get("Value"));
                break;
            case "notes":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureEditor(data);
                showNotes = "true".equalsIgnoreCase(str(data.get("Value")));
                renderRight(ops);
                break;
            case "stepOp":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureEditor(data);
                stepOp(ops, parseInt(str(data.get("Row")), -1), str(data.get("Op")));
                break;
            case "stepAdd":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureEditor(data);
                stepAdd(ops, str(data.get("Kind")));
                break;
            case "loopKind":
            case "checkType":
            case "checkAdd":
            case "checkChoice":
            case "checkHere":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureEditor(data);
                checkOp(ops, action, parseInt(str(data.get("Row")), -1), parseInt(str(data.get("Field")), -1), data.get("Value"));
                break;
            case "checkEdit":
                if (!sameGen(data)) break;
                checkEdit(parseInt(str(data.get("Row")), -1), parseInt(str(data.get("Field")), -1), str(data.get("Part")), data.get("Value"));
                break;
            case "save":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureEditor(data);
                save(ops);
                break;
            case "runMacro":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureEditor(data);
                runMacro(ops);
                break;
            case "deleteMacro":
                askDelete(ops);
                break;
            case "stopRun":
                stopRun(ops, str(data.get("Run")));
                break;
            case "stopAll":
                stopAll(ops);
                break;
            case "refresh":
                renderRight(ops);
                break;
            default:
                break;
        }
        return ops;
    }

    private boolean sameGen(Map<String, Object> data) {
        return String.valueOf(gen).equals(str(data.get("Gen")));
    }

    /** Run or Add to macro on a line that can't be sent: the preview note already says why, so the status
     *  line only says that nothing was sent, instead of repeating the reason under it. */
    private void notSent(ViewOps ops) {
        renderPreview(ops);
        setStatus(ops, NOT_SENT);
    }

    private void stale(ViewOps ops) {
        setStatus(ops, "The window was redrawn before that click arrived. Please click again.");
    }

    private void setStatus(ViewOps ops, String s) {
        status = s;
        ops.text(Ref.of(Part.STATUS), s);
    }

    // ---- tabs and the list ----------------------------------------------------------------------------

    private static Ref tabRef(Tab t) {
        return new Ref(Part.TAB, t.ordinal(), Child.NONE);
    }

    private void switchTab(ViewOps ops, String name) {
        Tab t;
        try {
            t = Tab.valueOf(name);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (t == tab) return;
        tab = t;
        filter = "all";
        pending = null;
        status = "";
        renderAll(ops);
    }

    private void renderAll(ViewOps ops) {
        for (Tab t : Tab.values()) ops.disabled(tabRef(t), t == tab);
        ops.visible(Ref.of(Part.SEARCH), tab != Tab.RUNS);
        ops.visible(Ref.of(Part.FILTER_ROW), tab != Tab.RUNS);
        ops.entries(Ref.of(Part.FILTER), filterEntries());
        ops.value(Ref.of(Part.FILTER), filter);
        hideConfirm(ops);
        renderList(ops);
        renderRight(ops);
    }

    private List<Entry> filterEntries() {
        List<Entry> out = new ArrayList<>();
        if (tab == Tab.MACROS) {
            out.add(new Entry("All macros", "all"));
            out.add(new Entry("Mine", "mine"));
            out.add(new Entry("Server", "server"));
            out.add(new Entry("Presets", "presets"));
            return out;
        }
        out.add(new Entry("All commands", "all"));
        out.add(new Entry("Favorites", "favorites"));
        out.add(new Entry("Ones I have permission for", "allowed"));
        out.add(new Entry("Described by a sidecar", "level:described"));
        out.add(new Entry("Typed (from the game)", "level:typed"));
        out.add(new Entry("Raw (name and help only)", "level:raw"));
        Set<String> categories = new TreeSet<>();
        CommandCatalog cat = session.catalog();
        if (cat != null) {
            for (CommandInfo c : cat.visible()) {
                if (c.category() != null) categories.add(c.category());
            }
        }
        for (String c : categories) out.add(new Entry("Category: " + c, "cat:" + c));
        return out;
    }

    void renderList(ViewOps ops) {
        ops.clear(Part.LIST);
        if (tab == Tab.COMMANDS) {
            List<CommandInfo> all = commands();
            int shown = Math.min(all.size(), MAX_LIST);
            renderEntries(ops, commandEntries(all.subList(0, shown)));
            CommandCatalog cat = session.catalog();
            int total = cat == null ? 0 : cat.visible().size();
            ops.text(Ref.of(Part.COUNT), all.size() == total ? total + " commands" : all.size() + " of " + total + " commands"
                + (shown < all.size() ? " (first " + shown + " shown)" : ""));
        } else if (tab == Tab.MACROS) {
            List<String[]> entries = macroEntries();
            renderEntries(ops, entries);
            long macros = entries.stream().filter(e -> e[0] != null && !e[0].equals("new")).count();
            ops.text(Ref.of(Part.COUNT), macros + " macros and presets");
        } else {
            ops.text(Ref.of(Part.COUNT), RunText.count(runs().size(), "run") + " going");
        }
    }

    /** Each entry is {key, text}; a null key is a block's heading. */
    private void renderEntries(ViewOps ops, List<String[]> entries) {
        for (int i = 0; i < entries.size(); i++) {
            String[] e = entries.get(i);
            if (e[0] == null) {
                ops.append(Part.LIST, RowKind.LIST_HEADING);
                ops.text(new Ref(Part.LIST, i, Child.NONE), e[1]);
                continue;
            }
            boolean selected = tab == Tab.COMMANDS ? e[0].equals(commandName) : e[0].equals(macroKey);
            ops.append(Part.LIST, RowKind.LIST_ROW);
            ops.text(new Ref(Part.LIST, i, Child.BUTTON_LABEL), e[1]);
            ops.disabled(new Ref(Part.LIST, i, Child.BUTTON), selected);
            ops.bind(new Ref(Part.LIST, i, Child.BUTTON), Trigger.ACTIVATE, "pick", Map.of("Key", e[0]), Map.of());
        }
    }

    /**
     * The commands in blocks: favorites, then the ones without the game's permission, then by level. Each
     * block is in name order. A command is in the first block it belongs to.
     */
    private List<String[]> commandEntries(List<CommandInfo> commands) {
        Set<String> favs = session.favorites();
        Map<String, List<CommandInfo>> blocks = new LinkedHashMap<>();
        for (String h : new String[] {"Favorites", "No permission", "Described by a sidecar", "Typed (from the game)",
            "Raw (name and help only)"}) {
            blocks.put(h, new ArrayList<>());
        }
        for (CommandInfo c : commands) {
            String block = favs.contains(c.name()) ? "Favorites"
                : Boolean.FALSE.equals(session.mayRun(c.name())) ? "No permission"
                : switch (c.level()) {
                    case DESCRIBED -> "Described by a sidecar";
                    case TYPED -> "Typed (from the game)";
                    case RAW -> "Raw (name and help only)";
                };
            blocks.get(block).add(c);
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, List<CommandInfo>> b : blocks.entrySet()) {
            if (b.getValue().isEmpty()) continue;
            out.add(new String[] {null, b.getKey()});
            b.getValue().sort(Comparator.comparing((CommandInfo c) -> c.name().toLowerCase(Locale.ROOT)));
            for (CommandInfo c : b.getValue()) {
                out.add(new String[] {c.name(), "/" + c.name() + (c.label() != null ? "  " + c.label() : "")});
            }
        }
        return out;
    }

    /** The commands that match the search and the filter, in catalog order. */
    List<CommandInfo> commands() {
        CommandCatalog cat = session.catalog();
        List<CommandInfo> out = new ArrayList<>();
        if (cat == null) return out;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("/")) q = q.substring(1);
        Set<String> favs = session.favorites();
        for (CommandInfo c : cat.visible()) {
            if (filter.equals("favorites") && !favs.contains(c.name())) continue;
            if (filter.equals("allowed") && Boolean.FALSE.equals(session.mayRun(c.name()))) continue;
            if (filter.startsWith("level:") && !c.level().id().equals(filter.substring(6))) continue;
            if (filter.startsWith("cat:") && !filter.substring(4).equals(c.category())) continue;
            if (!q.isEmpty() && !matches(c, q)) continue;
            out.add(c);
        }
        return out;
    }

    private static boolean matches(CommandInfo c, String q) {
        if (c.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (String a : c.aliases()) {
            if (a.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        for (String t : new String[] {c.label(), c.description(), c.help(), c.category()}) {
            if (t != null && t.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    /**
     * Key and text of each entry of the macros list: "new" first, then a block per category (a null key is a
     * heading), headings in alphabetical order and "Other" last for those without one. Each block is in name
     * order, with the id added to a name that two entries share. Search and the filter apply within the blocks,
     * and an empty block is left out. The presser's own macros that need a permission they lack are shown,
     * marked with it; other macros and presets they may not run are left out.
     */
    private List<String[]> macroEntries() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"new", "+ New macro"});
        String q = query.trim().toLowerCase(Locale.ROOT);
        Map<String, List<String[]>> blocks = new TreeMap<>(WindowModel::headingOrder);
        if (filter.equals("all") || filter.equals("mine")) {
            for (Macro m : session.macros(Scope.PLAYER)) {
                String needs = holds(m.permission()) ? null : m.permission();
                String block = m.category() != null ? m.category() : OTHER;
                if (q.isEmpty() || (m.id() + " " + m.name() + " " + block).toLowerCase(Locale.ROOT).contains(q)) {
                    blocks.computeIfAbsent(block, k -> new ArrayList<>()).add(new String[] {"mine:" + m.id(), m.name(), m.id(), needs});
                }
            }
        }
        if (filter.equals("all") || filter.equals("server")) {
            for (Macro m : session.macros(Scope.SERVER)) {
                if (!holds(m.permission())) continue;
                String block = m.category() != null ? m.category() : OTHER;
                if (q.isEmpty() || (m.id() + " " + m.name() + " " + block).toLowerCase(Locale.ROOT).contains(q)) {
                    blocks.computeIfAbsent(block, k -> new ArrayList<>()).add(new String[] {"server:" + m.id(), m.name(), m.id(), null});
                }
            }
        }
        CommandCatalog cat = session.catalog();
        if (cat != null && (filter.equals("all") || filter.equals("presets"))) {
            for (Map.Entry<String, Preset> e : cat.presets().entrySet()) {
                Preset p = e.getValue();
                if (!holds(p.permission())) continue;
                String mod = e.getKey().substring(0, e.getKey().lastIndexOf('/'));
                String block = p.category() != null ? p.category() : OTHER;
                String searched = p.id() + " " + p.name() + " " + mod + " " + cat.modName(mod) + " " + block;
                if (q.isEmpty() || searched.toLowerCase(Locale.ROOT).contains(q)) {
                    blocks.computeIfAbsent(block, k -> new ArrayList<>()).add(new String[] {"preset:" + e.getKey(), p.name(), p.id(), null});
                }
            }
        }
        for (Map.Entry<String, List<String[]>> b : blocks.entrySet()) {
            out.add(new String[] {null, b.getKey()});
            List<String[]> items = b.getValue();
            for (String[] x : items) {
                if (x[1] == null || x[1].isBlank()) x[1] = x[2]; // a macro without a name shows its id
            }
            items.sort(Comparator.comparing((String[] x) -> x[1].toLowerCase(Locale.ROOT)).thenComparing(x -> x[2]));
            for (String[] x : items) {
                boolean shared = items.stream().filter(y -> y[1].equalsIgnoreCase(x[1])).count() > 1;
                String text = shared ? x[1] + "  (" + x[2] + ")" : x[1];
                out.add(new String[] {x[0], x[3] == null ? text : text + "  (needs " + x[3] + ")"});
            }
        }
        return out;
    }

    /** Headings in alphabetical order, ignoring case, with "Other" last. */
    static int headingOrder(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.equals(OTHER)) return 1;
        if (b.equals(OTHER)) return -1;
        int c = a.compareToIgnoreCase(b);
        return c != 0 ? c : a.compareTo(b);
    }

    /** Does the presser hold a macro's or preset's permission? True when it has none. */
    private boolean holds(String permission) {
        return session.holds(permission);
    }

    private void pick(ViewOps ops, String key) {
        pending = null;
        hideConfirm(ops);
        if (tab == Tab.COMMANDS) {
            CommandCatalog cat = session.catalog();
            if (cat == null || cat.find(key) == null) return;
            commandName = key;
            variant = -1;
            values.clear();
            rawText = "";
            status = "";
        } else if (tab == Tab.MACROS) {
            // "+ New macro" always starts a fresh draft, so it asks even while a new one is open.
            if (dirty && draft != null && (!key.equals(macroKey) || key.equals("new"))) {
                pending = Pending.leave(key);
                showConfirm(ops, "The macro " + label(draft) + " has unsaved changes. Leave them and open the other one?", "");
                setStatus(ops, "Press Yes to leave the changes, or No to keep editing.");
                return;
            }
            // Picking the macro that's already open keeps its unsaved changes; reading it again would drop them.
            if (!(dirty && draft != null && key.equals(macroKey)) && !selectMacro(key)) return;
        } else {
            return;
        }
        renderList(ops);
        renderRight(ops);
    }

    private static String label(MacroDraft d) {
        return d.name == null || d.name.isBlank() ? d.id : d.name;
    }

    // ---- the right side ---------------------------------------------------------------------------------

    void renderRight(ViewOps ops) {
        gen++;
        ops.clear(Part.ROWS);
        ops.clear(Part.FOOTER);
        rows.clear();
        if (tab == Tab.COMMANDS) {
            renderForm(ops);
        } else if (tab == Tab.MACROS) {
            renderEditor(ops);
        } else {
            renderRuns(ops);
        }
        ops.text(Ref.of(Part.STATUS), status);
    }

    private CommandInfo command() {
        CommandCatalog cat = session.catalog();
        return cat == null || commandName == null ? null : cat.find(commandName);
    }

    /** The arguments of the form shown: the main form or the chosen variant. */
    private List<Arg> formArgs(CommandInfo c) {
        if (variant >= 0 && variant < c.variants().size()) return c.variants().get(variant).args();
        return c.args();
    }

    private void renderForm(ViewOps ops) {
        CommandInfo c = command();
        if (c == null) {
            commandName = null;
            ops.text(Ref.of(Part.HEADING), "CommandDeck");
            ops.text(Ref.of(Part.META), "Pick a command on the left to fill in its form and run it.");
            note(ops, "Commands run as you, with your own permissions. A line that can't be sent exactly as shown is never sent.");
            ops.text(Ref.of(Part.PREVIEW), "");
            ops.text(Ref.of(Part.PREVIEW_NOTE), "");
            return;
        }
        ops.text(Ref.of(Part.HEADING), "/" + c.name() + (c.label() != null ? "   " + c.label() : ""));
        StringBuilder meta = new StringBuilder(levelText(c.level()));
        if (!c.aliases().isEmpty()) meta.append("   Aliases: ").append(String.join(", ", c.aliases()));
        if (c.category() != null) meta.append("   Category: ").append(c.category());
        if (c.permission() != null) meta.append("   Permission: ").append(c.permission());
        if (c.danger() != Danger.NONE) {
            meta.append(c.danger() == Danger.SAVE_RISK ? "   Asks first: can damage the save" : "   Asks first");
        }
        ops.text(Ref.of(Part.META), meta.toString());
        if (Boolean.FALSE.equals(session.mayRun(c.name()))) {
            note(ops, NO_PERMISSION);
        }
        if (c.help() != null && !c.help().isBlank()) note(ops, c.help());
        if (c.description() != null && !c.description().isBlank() && !c.description().equals(c.help())) note(ops, c.description());
        for (Example e : c.examples()) note(ops, "Example: " + e.line() + (e.explanation() == null ? "" : "   " + e.explanation()));

        if (!c.variants().isEmpty()) {
            int r = rows.size();
            rows.add(new Row(Kind.VARIANT, null));
            ops.append(Part.ROWS, RowKind.CHOICE_ROW);
            List<Entry> forms = new ArrayList<>();
            forms.add(new Entry("Main: " + summary(c.args()), "-1"));
            for (int i = 0; i < c.variants().size(); i++) {
                Variant v = c.variants().get(i);
                forms.add(new Entry((v.label() != null ? v.label() + ": " : "") + summary(v.args()), Integer.toString(i)));
            }
            ops.text(Ref.row(r, Child.LABEL), "Form");
            ops.entries(Ref.row(r, Child.INPUT), forms);
            ops.value(Ref.row(r, Child.INPUT), Integer.toString(variant));
            hint(ops, r, variantHint(c));
            ops.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "variant", Map.of("Gen", g()),
                with(formCaptures(), "Value", Ref.row(r, Child.INPUT)));
        }

        if (c.level() == Level.RAW) {
            int r = rows.size();
            rows.add(new Row(Kind.RAW, null));
            ops.append(Part.ROWS, RowKind.TEXT_ROW);
            ops.text(Ref.row(r, Child.LABEL), "Arguments");
            ops.value(Ref.row(r, Child.INPUT), rawText);
            hint(ops, r, "Typed as you would in chat, after the command name. Sent as written.");
            fieldBind(ops, r);
            List<String> history = session.history(c.name());
            if (!history.isEmpty()) {
                int h = rows.size();
                rows.add(new Row(Kind.RECENT, null));
                ops.append(Part.ROWS, RowKind.CHOICE_ROW);
                List<Entry> entries = new ArrayList<>();
                entries.add(new Entry("", ""));
                for (String x : history) entries.add(new Entry(x.isEmpty() ? "(nothing)" : x, x));
                ops.text(Ref.row(h, Child.LABEL), "Recent");
                ops.entries(Ref.row(h, Child.INPUT), entries);
                ops.value(Ref.row(h, Child.INPUT), "");
                hint(ops, h, "Pick one to put it back in the field.");
                ops.bind(Ref.row(h, Child.INPUT), Trigger.CHANGE, "recent", Map.of("Gen", g()), Map.of("Value", Ref.row(h, Child.INPUT)));
            }
        } else {
            for (Arg a : formArgs(c)) renderField(ops, a);
            if (formArgs(c).isEmpty()) note(ops, "This form has no arguments.");
        }

        ops.append(Part.FOOTER, RowKind.FORM_FOOTER);
        ops.bind(Ref.footer(Child.RUN), Trigger.ACTIVATE, "run", Map.of("Gen", g()), formCaptures());
        ops.bind(Ref.footer(Child.ADD_TO_MACRO), Trigger.ACTIVATE, "addToMacro", Map.of("Gen", g()), formCaptures());
        ops.bind(Ref.footer(Child.FAVORITE), Trigger.ACTIVATE, "favorite", Map.of(), Map.of());
        boolean fav = session.favorites().contains(c.name());
        ops.text(Ref.footer(Child.FAVORITE), fav ? "Unfavorite" : "Favorite");
        renderPreview(ops);
    }

    private static String levelText(Level l) {
        switch (l) {
            case TYPED:
                return "Typed: the game describes its arguments";
            case DESCRIBED:
                return "Described by a sidecar";
            default:
                return "Raw: name and help only";
        }
    }

    private static String variantHint(CommandInfo c) {
        return c.variants().size() + 1 + " forms";
    }

    private static String summary(List<Arg> args) {
        if (args.isEmpty()) return "no arguments";
        StringBuilder b = new StringBuilder();
        for (Arg a : args) {
            if (b.length() > 0) b.append(' ');
            String n = a.style() == ArgStyle.POSITIONAL ? a.name() : "--" + a.name();
            b.append(a.required() ? "<" + n + ">" : "[" + n + "]");
        }
        return b.toString();
    }

    /** A row's hint, under its field; hidden when empty so it takes no space. */
    private static void hint(ViewOps ops, int row, String text) {
        boolean shown = text != null && !text.isBlank();
        ops.text(Ref.row(row, Child.HINT), shown ? text : "");
        ops.visible(Ref.row(row, Child.HINT), shown);
    }

    private void note(ViewOps ops, String text) {
        int r = rows.size();
        rows.add(new Row(Kind.NOTE, null));
        ops.append(Part.ROWS, RowKind.NOTE_ROW);
        ops.text(Ref.row(r, Child.NONE), text);
    }

    private void renderField(ViewOps ops, Arg a) {
        int r = rows.size();
        rows.add(new Row(Kind.FIELD, a));
        String v = values.getOrDefault(a.name(), a.defaultValue() == null ? "" : String.valueOf(a.defaultValue()));
        values.putIfAbsent(a.name(), v);
        String label = (a.style() == ArgStyle.POSITIONAL ? a.name() : "--" + a.name()) + (a.required() ? "" : " (optional)");
        if (a.style() == ArgStyle.FLAG) {
            ops.append(Part.ROWS, RowKind.CHECK_ROW);
            ops.value(Ref.row(r, Child.INPUT), "true".equalsIgnoreCase(v));
        } else if (choices(a) != null) {
            ops.append(Part.ROWS, RowKind.CHOICE_ROW);
            List<Entry> entries = new ArrayList<>();
            for (String o : choices(a)) entries.add(new Entry(o.isEmpty() ? "(none)" : o, o));
            ops.entries(Ref.row(r, Child.INPUT), entries);
            ops.value(Ref.row(r, Child.INPUT), v);
        } else {
            ops.append(Part.ROWS, RowKind.TEXT_ROW);
            ops.value(Ref.row(r, Child.INPUT), v);
        }
        ops.text(Ref.row(r, Child.LABEL), label);
        hint(ops, r, hint(a));
        fieldBind(ops, r);
        if (a.style() != ArgStyle.FLAG && (a.type() == ArgType.ITEM || a.type() == ArgType.BLOCK) && !session.assetIds(a.type()).isEmpty()) {
            int m = rows.size();
            rows.add(new Row(Kind.MATCHES, a));
            ops.append(Part.ROWS, RowKind.CHOICE_ROW);
            ops.text(Ref.row(m, Child.LABEL), "   " + (a.type() == ArgType.ITEM ? "Items" : "Blocks"));
            ops.bind(Ref.row(m, Child.INPUT), Trigger.CHANGE, "match", Map.of("Row", Integer.toString(r), "Gen", g()),
                Map.of("Value", Ref.row(m, Child.INPUT)));
            matchesInto(ops, m);
        }
    }

    /** The ids that contain what the field holds (those that start with it first), at most {@link #MAX_MATCHES}. */
    List<String> matches(ArgType type, String typed) {
        List<String> all = session.assetIds(type);
        String q = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        List<String> first = new ArrayList<>();
        List<String> then = new ArrayList<>();
        int found = 0;
        for (String id : all) {
            String l = id.toLowerCase(Locale.ROOT);
            if (q.isEmpty() || l.startsWith(q)) {
                if (first.size() < MAX_MATCHES) first.add(id);
                found++;
            } else if (l.contains(q)) {
                if (then.size() < MAX_MATCHES) then.add(id);
                found++;
            }
        }
        first.addAll(then);
        List<String> out = new ArrayList<>(first.subList(0, Math.min(first.size(), MAX_MATCHES)));
        lastMatchCount = found;
        return out;
    }

    private int lastMatchCount;

    private void matchesInto(ViewOps ops, int m) {
        Arg a = rows.get(m).arg();
        List<String> ids = matches(a.type(), values.getOrDefault(a.name(), ""));
        List<Entry> entries = new ArrayList<>();
        int n = lastMatchCount;
        entries.add(new Entry(n == 0 ? "(no matches)" : n > ids.size() ? "(" + n + " matches; first " + ids.size() + " shown)"
            : "(" + n + (n == 1 ? " match)" : " matches)"), ""));
        for (String id : ids) entries.add(new Entry(id, id));
        ops.entries(Ref.row(m, Child.INPUT), entries);
        ops.value(Ref.row(m, Child.INPUT), "");
        hint(ops, m, "type in the field above to narrow the list, then pick one");
    }

    private void renderMatches(ViewOps ops) {
        if (tab != Tab.COMMANDS) return;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind() == Kind.MATCHES) matchesInto(ops, i);
        }
    }

    /** A picked id goes into the field it belongs to. */
    private void match(ViewOps ops, int fieldRow, String id) {
        if (id.isEmpty() || fieldRow < 0 || fieldRow >= rows.size() || rows.get(fieldRow).kind() != Kind.FIELD) return;
        values.put(rows.get(fieldRow).arg().name(), id);
        ops.value(Ref.row(fieldRow, Child.INPUT), id);
        renderMatches(ops);
        renderPreview(ops);
    }

    /** The values a picker offers, or null for a text field. */
    private List<String> choices(Arg a) {
        List<String> out = new ArrayList<>();
        if (!a.required()) out.add("");
        if (a.type() == ArgType.ENUM && a.options() != null && !a.options().isEmpty()) {
            out.addAll(a.options());
            return out;
        }
        if (a.type() == ArgType.BOOL) {
            out.add("true");
            out.add("false");
            return out;
        }
        if (a.type() == ArgType.PLAYER) {
            if (a.placeholders()) out.add("{self}");
            for (String p : session.onlinePlayers()) {
                if (!out.contains(p)) out.add(p);
            }
            return out;
        }
        return null;
    }

    private String hint(Arg a) {
        StringBuilder b = new StringBuilder();
        switch (a.type()) {
            case GREEDY_STRING:
                b.append("text, the rest of the line");
                break;
            case POSITION:
                List<String> names = coordinates();
                b.append(String.join(" ", names)).append(" (or {").append(String.join("} {", names)).append("})");
                break;
            case INT:
                b.append("whole number");
                break;
            case FLOAT:
                b.append("number");
                break;
            default:
                b.append(a.type().id().replace('_', ' '));
        }
        if (a.min() != null || a.max() != null) {
            b.append(", ").append(a.min() == null ? "up" : Json.formatDouble(a.min())).append(" to ")
                .append(a.max() == null ? "any" : Json.formatDouble(a.max()));
        }
        if (a.description() != null) b.append(". ").append(a.description());
        return b.toString();
    }

    private void fieldBind(ViewOps ops, int r) {
        ops.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "field", Map.of("Row", Integer.toString(r), "Gen", g()),
            Map.of("Value", Ref.row(r, Child.INPUT)));
    }

    /** Every form field's current value, captured with a click as {@code V<row>}. */
    private Map<String, Ref> formCaptures() {
        Map<String, Ref> m = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Kind k = rows.get(i).kind();
            if (k == Kind.FIELD || k == Kind.RAW) m.put("V" + i, Ref.row(i, Child.INPUT));
        }
        return m;
    }

    private static Map<String, Ref> with(Map<String, Ref> captures, String name, Ref ref) {
        Map<String, Ref> m = new LinkedHashMap<>(captures);
        m.put(name, ref);
        return m;
    }

    private void captureForm(Map<String, Object> data) {
        for (int i = 0; i < rows.size(); i++) {
            if (data.containsKey("V" + i)) storeField(i, data.get("V" + i));
        }
    }

    private void storeField(int r, Object value) {
        if (r < 0 || r >= rows.size()) return;
        Row row = rows.get(r);
        String v = value instanceof Boolean ? value.toString() : str(value);
        if (row.kind() == Kind.RAW) {
            rawText = v;
        } else if (row.kind() == Kind.FIELD) {
            values.put(row.arg().name(), v);
        }
    }

    private void recent(ViewOps ops, String text) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind() == Kind.RAW) {
                rawText = text;
                ops.value(Ref.row(i, Child.INPUT), text);
            }
        }
        renderPreview(ops);
    }

    /** The game's coordinates, {@code x y} or {@code x y z}. */
    private List<String> coordinates() {
        int n = session.open().coordinates();
        return Placeholders.COORDINATES.subList(0, n == 2 ? 2 : 3);
    }

    /** How many words a value holds, split at runs of spaces and tabs. */
    private static int words(String v) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < v.length(); i++) {
            boolean space = v.charAt(i) == ' ' || v.charAt(i) == '\t';
            if (!space && !in) n++;
            in = !space;
        }
        return n;
    }

    /** The line the form would send now, or why it can't be sent. */
    public LineRules.Built buildLine() {
        CommandInfo c = command();
        if (c == null) return new LineRules.Built(null, new LineRules.Unsendable("empty", List.of(), null));
        if (c.level() == Level.RAW) {
            String prefix = "/" + c.name() + " ";
            String t = rawText.strip();
            String line = t.isEmpty() ? "/" + c.name() : prefix + t;
            Problem p = lines.checkLine(line);
            if (p == null) return new LineRules.Built(line, null);
            List<Long> at = new ArrayList<>();
            for (Object o : (List<?>) p.detail().get("positions")) at.add(((Long) o) - prefix.length());
            return new LineRules.Built(null, new LineRules.Unsendable((String) p.detail().get("reason"), at, "Arguments"));
        }
        List<LineRules.Field> fields = new ArrayList<>();
        int form = variant >= 0 && variant < c.variants().size() ? variant : -1;
        for (Arg a : formArgs(c)) {
            String v = values.getOrDefault(a.name(), "");
            // The game says how many words it reads (a position or a rotation takes three) and which are lists.
            int tokens = session.tokens(c.name(), form, a.name());
            if (tokens <= 0) tokens = a.type() == ArgType.POSITION ? coordinates().size() : 1;
            boolean list = session.isList(c.name(), form, a.name());
            if (tokens > 1 && !list && !v.isBlank() && words(v.strip()) != tokens) {
                String reason = a.type() == ArgType.POSITION && tokens == coordinates().size() ? "position" : "count";
                return new LineRules.Built(null, new LineRules.Unsendable(reason, List.of(), a.name()));
            }
            fields.add(new LineRules.Field(a.name(), a.type(), a.style(), a.required(), v, list ? 1 : tokens, list));
        }
        return lines.build(c.name(), fields);
    }

    void renderPreview(ViewOps ops) {
        if (tab != Tab.COMMANDS || command() == null) return;
        LineRules.Built b = buildLine();
        if (b.sendable()) {
            ops.text(Ref.of(Part.PREVIEW), b.line());
            ops.text(Ref.of(Part.PREVIEW_NOTE), "Runs as you. This is exactly the line that is sent.");
            return;
        }
        LineRules.Unsendable u = b.unsendable();
        ops.text(Ref.of(Part.PREVIEW), "");
        ops.text(Ref.of(Part.PREVIEW_NOTE), problemText(u));
    }

    private String problemText(LineRules.Unsendable u) {
        if ("position".equals(u.reason())) {
            List<String> names = coordinates();
            return "Can't send yet: " + u.field() + " needs " + (names.size() == 2 ? "two" : "three") + " values, " + String.join(" ", names);
        }
        String msg = lines.message(u.reason());
        if (u.field() == null) return "Can't send yet: " + msg;
        String value = "Arguments".equals(u.field()) ? rawText.strip() : values.getOrDefault(u.field(), "");
        String marked = u.positions().isEmpty() ? "" : ": " + mark(value, u.positions());
        return "Can't send yet: " + msg + " (" + u.field() + marked + ")";
    }

    /** The value with each offending character between guillemets: {@code a«,»b}. */
    public static String mark(String value, List<Long> positions) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            boolean bad = positions.contains((long) i);
            if (bad) b.append('«');
            b.append(value.charAt(i));
            if (bad) b.append('»');
        }
        return b.toString();
    }

    private void runForm(ViewOps ops) {
        CommandInfo c = command();
        if (c == null) return;
        LineRules.Built b = buildLine();
        if (!b.sendable()) {
            notSent(ops);
            return;
        }
        if (c.level() == Level.RAW) session.remember(c.name(), rawText.strip());
        run(ops, new RunRequest(List.of(RunItem.line(b.line())), SOURCE));
        if (c.level() == Level.RAW) renderList(ops);
    }

    /** Checks a request; shows the deck's prompt if it asks first, otherwise starts it. */
    private void run(ViewOps ops, RunRequest request) {
        CheckResult check = session.check(request);
        if (!check.ok()) {
            setStatus(ops, "Can't run that: " + check.refusal().message());
            return;
        }
        if (check.needsConfirmation()) {
            pending = Pending.run(request, check.confirmToken());
            showConfirm(ops, check.confirmPrompt(), check.saveRisk() ? "Warning: this can damage the save. Back it up first." : "");
            setStatus(ops, "This asks first. Read what it runs, then press Yes or No.");
            return;
        }
        start(ops, request, null);
    }

    private void start(ViewOps ops, RunRequest request, String token) {
        StartResult s = session.start(request, token);
        if (!s.started()) {
            setStatus(ops, "Can't run that: " + s.refusal().message());
        } else {
            setStatus(ops, "Started run " + s.runId() + ". The result appears in chat; see the Runs tab while it's going.");
        }
    }

    private void confirm(ViewOps ops) {
        Pending p = pending;
        pending = null;
        hideConfirm(ops);
        if (p == null) return;
        if (p.request() != null) {
            start(ops, p.request(), p.token());
            return;
        }
        if (p.switchTo() != null) {
            String left = draft == null ? "" : label(draft);
            dirty = false;
            if (!selectMacro(p.switchTo())) {
                setStatus(ops, "That macro is gone.");
                return;
            }
            status = "Left the unsaved changes to " + left + ".";
            renderList(ops);
            renderRight(ops);
            return;
        }
        Problem problem = session.deleteMacro(p.deleteScope(), p.deleteId());
        if (problem != null) {
            setStatus(ops, "Not deleted: " + problem.message());
            return;
        }
        status = "Deleted " + p.deleteId() + ".";
        macroKey = null;
        draft = null;
        dirty = false;
        renderList(ops);
        renderRight(ops);
    }

    private void showConfirm(ViewOps ops, String text, String risk) {
        ops.text(Ref.of(Part.CONFIRM_TEXT), text);
        ops.text(Ref.of(Part.CONFIRM_RISK), risk);
        ops.visible(Ref.of(Part.CONFIRM_RISK), !risk.isEmpty());
        ops.visible(Ref.of(Part.CONFIRM_BOX), true);
    }

    private void hideConfirm(ViewOps ops) {
        ops.visible(Ref.of(Part.CONFIRM_BOX), false);
    }

    private void favorite(ViewOps ops) {
        if (commandName == null) return;
        boolean now = session.toggleFavorite(commandName);
        // A click sent before a redraw can arrive on another tab, whose footer has no Favorite button.
        if (tab == Tab.COMMANDS) ops.text(Ref.footer(Child.FAVORITE), now ? "Unfavorite" : "Favorite");
        setStatus(ops, now ? "/" + commandName + " is a favorite now." : "/" + commandName + " is no longer a favorite.");
        renderList(ops);
    }

    private void addToMacro(ViewOps ops) {
        LineRules.Built b = buildLine();
        if (!b.sendable()) {
            notSent(ops);
            return;
        }
        if (draft != null && draftReadOnly && dirty) {
            setStatus(ops, "The macro open in the editor has unsaved changes. Save it as yours, or open another macro, first.");
            return;
        }
        if (draft == null || draftReadOnly) newDraft();
        draft.addCommand(b.line());
        dirty = true;
        setStatus(ops, "Added " + b.line() + " to the macro " + draft.name + ". Open the Macros tab to edit and save it.");
    }

    // ---- macros ---------------------------------------------------------------------------------------

    private void newDraft() {
        String id = "macro-1";
        for (int n = 1; taken(id); n++) id = "macro-" + n;
        draft = new MacroDraft(id, "New macro");
        draftOriginalId = null;
        draftScope = Scope.PLAYER;
        draftReadOnly = false;
        draftNeeds = null;
        dirty = false;
        macroKey = "new";
    }

    private boolean taken(String id) {
        for (Macro m : session.macros(Scope.PLAYER)) {
            if (m.id().equals(id)) return true;
        }
        return false;
    }

    private boolean selectMacro(String key) {
        if (key.equals("new")) {
            newDraft();
        } else if (key.startsWith("mine:") || key.startsWith("server:")) {
            Scope scope = key.startsWith("mine:") ? Scope.PLAYER : Scope.SERVER;
            String id = key.substring(key.indexOf(':') + 1);
            Macro m = null;
            for (Macro x : session.macros(scope)) {
                if (x.id().equals(id)) m = x;
            }
            if (m == null) return false;
            draft = MacroDraft.of(m);
            draft.scopeCalls(ref -> scoped(ref, scope == Scope.SERVER ? Calls.Place.SERVER : Calls.Place.PLAYER).ref);
            draftOriginalId = m.id();
            draftScope = scope;
            draftNeeds = holds(m.permission()) ? null : m.permission();
            draftReadOnly = draftNeeds != null || (scope == Scope.SERVER ? !session.open().operator() : !session.open().canEditOwn());
            dirty = false;
            macroKey = key;
        } else if (key.startsWith("preset:")) {
            CommandCatalog cat = session.catalog();
            Preset p = cat == null ? null : cat.presets().get(key.substring(7));
            if (p == null) return false;
            draft = MacroDraft.copyOf(p.id(), p.name(), p.macro(), p.permission());
            // A copy lives in the presser's macros, where a plain id would look somewhere else than the preset's.
            String mod = key.substring(7, key.lastIndexOf('/'));
            draft.scopeCalls(ref -> scoped(ref, Calls.Place.preset(mod)).ref);
            draft.description = p.description();
            draftOriginalId = null;
            draftScope = Scope.PLAYER;
            draftReadOnly = true;
            draftNeeds = null;
            dirty = false;
            macroKey = key;
        } else {
            return false;
        }
        showNotes = draft.hasNotes();
        status = "";
        return true;
    }

    private void renderEditor(ViewOps ops) {
        layout.clear();
        if (draft == null) {
            ops.text(Ref.of(Part.HEADING), "Macros");
            ops.text(Ref.of(Part.META), "Pick a macro on the left, or + New macro.");
            note(ops, "A macro is a list of steps: commands, waits, loops, groups and calls. It runs one step per tick, as you.");
            ops.text(Ref.of(Part.PREVIEW), "");
            ops.text(Ref.of(Part.PREVIEW_NOTE), "");
            return;
        }
        boolean preset = macroKey != null && macroKey.startsWith("preset:");
        // Every binding below captures the editor's rows, so they're laid out first.
        layOut();
        ops.text(Ref.of(Part.HEADING), draft.name == null || draft.name.isBlank() ? draft.id : draft.name);
        ops.text(Ref.of(Part.META), preset ? "A preset from a mod: run it, or save a copy as your own to change it."
            : draftNeeds != null ? "Your own macro, but it needs the permission " + draftNeeds
                + ", which you don't have. You can't run or change it, but you can delete it."
            : draftReadOnly ? (draftScope == Scope.SERVER ? "A server macro: only operators can change it." : "You may run this macro, but not change it.")
            : (draftScope == Scope.SERVER ? "A server macro." : "Your own macro.") + (draftOriginalId == null ? " Not saved yet." : ""));

        headerRow(ops, 0, RowKind.TEXT_ROW, "Id", draft.id, "lowercase letters, digits, _ . and -; changing it renames the macro", "id");
        headerRow(ops, 1, RowKind.TEXT_ROW, "Name", draft.name, "shown in lists", "name");
        headerRow(ops, 2, RowKind.CHECK_ROW, "Ask before running", null, "shows a confirmation every time it runs", "danger");
        ops.value(Ref.row(2, Child.INPUT), draft.dangerous);
        headerRow(ops, 3, RowKind.CHOICE_ROW, "Saved in", null, "", "where");
        List<Entry> where = new ArrayList<>();
        where.add(new Entry("My macros", "PLAYER"));
        if (session.open().operator()) where.add(new Entry("Server macros", "SERVER"));
        ops.entries(Ref.row(3, Child.INPUT), where);
        ops.value(Ref.row(3, Child.INPUT), draftScope.name());
        hint(ops, 3, session.open().operator() ? "server macros can be run by everyone" : "");
        headerRow(ops, 4, RowKind.TEXT_ROW, "Description", draft.description == null ? "" : draft.description, "optional, shown with the macro",
            "description");
        headerRow(ops, 5, RowKind.TEXT_ROW, "Category", draft.category == null ? "" : draft.category,
            "optional, up to 40 characters; the Macros list groups by it", "category");
        rows.add(new Row(Kind.FIELD, null));
        ops.append(Part.ROWS, RowKind.CHECK_ROW);
        ops.text(Ref.row(6, Child.LABEL), "Show step notes");
        ops.value(Ref.row(6, Child.INPUT), showNotes);
        hint(ops, 6, "a note under each step, saved with the macro");
        ops.bind(Ref.row(6, Child.INPUT), Trigger.CHANGE, "notes", Map.of("Gen", g()), with(editorCaptures(), "Value", Ref.row(6, Child.INPUT)));

        List<MacroDraft.Step> steps = draft.steps();
        for (int x = 0; x < layout.size(); x++) {
            EditorRow e = layout.get(x);
            int r = HEADER_ROWS + x;
            MacroDraft.Step s = e.step() < steps.size() ? steps.get(e.step()) : null;
            switch (e.kind()) {
                case LOOP_ROW -> loopRow(ops, r, e.step(), s);
                case CONDITION_ROW -> conditionRow(ops, r, e.step(), s);
                case FIELD_ROW -> fieldRow(ops, r, e.step(), e.field(), s);
                case ADD_CHECK_ROW -> addCheckRow(ops, r, e.step(), s);
                case TEXT_ROW -> noteRow(ops, r, e.step(), s);
                default -> stepRow(ops, r, e.step(), s);
            }
        }
        if (steps.isEmpty()) note(ops, "No steps yet. Add one with the buttons below.");

        ops.append(Part.FOOTER, RowKind.EDITOR_FOOTER);
        for (MacroDraft.Kind k : FOOTER_KINDS) {
            Ref add = Ref.footer(addButton(k));
            ops.bind(add, Trigger.ACTIVATE, "stepAdd", Map.of("Kind", k.name(), "Gen", g()), editorCaptures());
            ops.disabled(add, draftReadOnly);
        }
        ops.bind(Ref.footer(Child.SAVE), Trigger.ACTIVATE, "save", Map.of("Gen", g()), editorCaptures());
        ops.bind(Ref.footer(Child.RUN_MACRO), Trigger.ACTIVATE, "runMacro", Map.of("Gen", g()), editorCaptures());
        ops.bind(Ref.footer(Child.DELETE), Trigger.ACTIVATE, "deleteMacro", Map.of(), Map.of());
        boolean canCopy = session.open().canEditOwn();
        ops.text(Ref.footer(Child.SAVE), draftReadOnly && draftNeeds == null ? "Save as mine" : "Save");
        ops.disabled(Ref.footer(Child.SAVE), draftNeeds != null || draftReadOnly && !canCopy);
        ops.disabled(Ref.footer(Child.RUN_MACRO), draftNeeds != null);
        ops.disabled(Ref.footer(Child.DELETE), !mayDelete());
        renderValidation(ops);
    }

    /** The footer's add buttons: "+ Loop" adds a Repeat, which its row's selector makes a While or Until. */
    private static final List<MacroDraft.Kind> FOOTER_KINDS = List.of(MacroDraft.Kind.COMMAND, MacroDraft.Kind.WAIT, MacroDraft.Kind.REPEAT,
        MacroDraft.Kind.SEQUENCE, MacroDraft.Kind.CALL);

    private static Child addButton(MacroDraft.Kind k) {
        return switch (k) {
            case COMMAND -> Child.ADD_COMMAND;
            case WAIT -> Child.ADD_WAIT;
            case SEQUENCE -> Child.ADD_SEQUENCE;
            case CALL -> Child.ADD_CALL;
            default -> Child.ADD_LOOP;
        };
    }

    private void headerRow(ViewOps ops, int r, RowKind kind, String label, String value, String hint, String field) {
        rows.add(new Row(Kind.FIELD, null));
        ops.append(Part.ROWS, kind);
        ops.text(Ref.row(r, Child.LABEL), label);
        if (value != null) ops.value(Ref.row(r, Child.INPUT), value);
        hint(ops, r, hint);
        ops.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "header", Map.of("Field", field, "Gen", g()), Map.of("Value", Ref.row(r, Child.INPUT)));
    }

    /**
     * One editor row after the header: its kind, the step it shows, and for a field row the field's index in its
     * check's form ({@link #NOTE} for a step's note row). An add-check row's step is its loop.
     */
    private record EditorRow(RowKind kind, int step, int field) {}

    private static final int NOTE = -2;
    private static final List<Entry> LOOP_KINDS = List.of(new Entry("Repeat", "REPEAT"), new Entry("While", "WHILE"), new Entry("Until", "UNTIL"));
    private static final List<Entry> LOGIC = List.of(new Entry("All of", "all"), new Entry("Any of", "any"), new Entry("Not", "not"));

    /**
     * Lays out the editor's rows: each step's row; a check's fields under it; a step's note under it when notes are
     * shown; and "+ Check" at the end of each While's or Until's block, innermost first.
     */
    private void layOut() {
        layout.clear();
        List<ConditionPort.Form> offered = session.conditions() == null ? List.of() : session.conditions().forms();
        forms.clear();
        for (ConditionPort.Form f : offered) forms.put(f.type(), f);
        checkFields = new CheckFields(session.conditions() == null ? null : session.conditions().game(), session.open().coordinates());
        List<MacroDraft.Step> steps = draft.steps();
        Map<Integer, List<Integer>> endsAt = new TreeMap<>();
        for (int j = 0; j < steps.size(); j++) {
            if (steps.get(j).kind.checked()) endsAt.computeIfAbsent(draft.blockEnd(j) - 1, k -> new ArrayList<>()).add(0, j);
        }
        for (int j = 0; j < steps.size(); j++) {
            MacroDraft.Step s = steps.get(j);
            if (s.kind == MacroDraft.Kind.CHECK) {
                layout.add(new EditorRow(RowKind.CONDITION_ROW, j, -1));
                ConditionPort.Form f = editable(s) ? forms.get(s.text) : null;
                if (f != null) {
                    for (int k = 0; k < f.fields().size(); k++) layout.add(new EditorRow(RowKind.FIELD_ROW, j, k));
                }
            } else {
                layout.add(new EditorRow(s.kind.loop() ? RowKind.LOOP_ROW : RowKind.STEP_ROW, j, -1));
                if (showNotes) layout.add(new EditorRow(RowKind.TEXT_ROW, j, NOTE));
            }
            for (int loop : endsAt.getOrDefault(j, List.of())) layout.add(new EditorRow(RowKind.ADD_CHECK_ROW, loop, -1));
        }
    }

    /** A check the editor can change: a logic check, or one of a type this game offers. Others are shown read-only. */
    private boolean editable(MacroDraft.Step s) {
        return !s.raw() && (MacroDraft.logic(s.text) || forms.containsKey(s.text));
    }

    /** The row showing step {@code j}, or -1. */
    private int rowOf(int j) {
        for (int x = 0; x < layout.size(); x++) {
            EditorRow e = layout.get(x);
            if (e.step() == j && e.field() == -1 && e.kind() != RowKind.ADD_CHECK_ROW) return HEADER_ROWS + x;
        }
        return -1;
    }

    /** The row of a check's field, by the field's JSON name (either name of an Item-or-Tag field), or -1. */
    private int fieldRowOf(int j, String name) {
        ConditionPort.Form f = forms.get(draft.steps().get(j).text);
        if (f == null) return -1;
        for (int x = 0; x < layout.size(); x++) {
            EditorRow e = layout.get(x);
            if (e.kind() != RowKind.FIELD_ROW || e.step() != j) continue;
            ConditionPort.Field field = f.fields().get(e.field());
            if (field.name().equals(name) || field.kind() == ConditionPort.FieldKind.EITHER && field.options().contains(name)) return HEADER_ROWS + x;
        }
        return -1;
    }

    private ConditionPort.Field fieldOf(int j, int k) {
        ConditionPort.Form f = forms.get(draft.steps().get(j).text);
        return f == null || k < 0 || k >= f.fields().size() ? null : f.fields().get(k);
    }

    private void noteRow(ViewOps ops, int r, int j, MacroDraft.Step s) {
        rows.add(new Row(Kind.FIELD, null));
        ops.append(Part.ROWS, RowKind.TEXT_ROW);
        ops.text(Ref.row(r, Child.LABEL), "   Note for step " + (j + 1));
        ops.value(Ref.row(r, Child.INPUT), s.note == null ? "" : s.note);
        hint(ops, r, s.kind == MacroDraft.Kind.WAIT ? "optional" : "optional, up to 200 characters");
        ops.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "stepEdit", Map.of("Row", Integer.toString(j), "Field", "note", "Gen", g()),
            Map.of("Value", Ref.row(r, Child.INPUT)));
    }

    private void stepRow(ViewOps ops, int r, int j, MacroDraft.Step s) {
        rows.add(new Row(Kind.FIELD, null));
        ops.append(Part.ROWS, RowKind.STEP_ROW);
        ops.text(Ref.row(r, Child.DEPTH), ">".repeat(s.depth()));
        String kind;
        switch (s.kind) {
            case COMMAND:
                kind = "Command";
                break;
            case WAIT:
                kind = "Wait";
                break;
            case CALL:
                kind = "Call macro";
                break;
            default:
                kind = "Group";
        }
        ops.text(Ref.row(r, Child.KIND), kind);
        ops.visible(Ref.row(r, Child.INPUT), s.kind != MacroDraft.Kind.SEQUENCE);
        ops.value(Ref.row(r, Child.INPUT), s.text == null ? "" : s.text);
        boolean flag = s.kind == MacroDraft.Kind.COMMAND || s.kind == MacroDraft.Kind.WAIT;
        ops.visible(Ref.row(r, Child.FLAG), flag);
        ops.visible(Ref.row(r, Child.FLAG_LABEL), flag);
        ops.text(Ref.row(r, Child.FLAG_LABEL), s.kind == MacroDraft.Kind.WAIT ? "seconds" : "ask first");
        ops.value(Ref.row(r, Child.FLAG), s.flag);
        ops.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "stepEdit", Map.of("Row", Integer.toString(j), "Field", "text", "Gen", g()),
            Map.of("Value", Ref.row(r, Child.INPUT)));
        ops.bind(Ref.row(r, Child.FLAG), Trigger.CHANGE, "stepEdit", Map.of("Row", Integer.toString(j), "Field", "flag", "Gen", g()),
            Map.of("Value", Ref.row(r, Child.FLAG)));
        moveButtons(ops, r, j);
    }

    /** Up, Down, out, in and delete, for a step, a loop or a check. */
    private void moveButtons(ViewOps ops, int r, int j) {
        Child[] buttons = {Child.UP, Child.DOWN, Child.OUT, Child.IN, Child.DEL};
        String[] opNames = {"up", "down", "out", "in", "del"};
        boolean[] enabled = {draft.canMoveUp(j), draft.canMoveDown(j), draft.canOutdent(j), draft.canIndent(j), true};
        for (int b = 0; b < buttons.length; b++) {
            ops.bind(Ref.row(r, buttons[b]), Trigger.ACTIVATE, "stepOp", Map.of("Row", Integer.toString(j), "Op", opNames[b], "Gen", g()),
                editorCaptures());
            ops.disabled(Ref.row(r, buttons[b]), draftReadOnly || !enabled[b]);
        }
    }

    /** A Repeat, While or Until: the kind selector, then times (most times for a While or Until, blank for the limit). */
    private void loopRow(ViewOps ops, int r, int j, MacroDraft.Step s) {
        rows.add(new Row(Kind.FIELD, null));
        ops.append(Part.ROWS, RowKind.LOOP_ROW);
        ops.text(Ref.row(r, Child.DEPTH), ">".repeat(s.depth()));
        ops.entries(Ref.row(r, Child.KIND), LOOP_KINDS);
        ops.value(Ref.row(r, Child.KIND), s.kind.name());
        boolean repeat = s.kind == MacroDraft.Kind.REPEAT;
        ops.text(Ref.row(r, Child.LABEL), repeat ? "times" : "most times");
        ops.value(Ref.row(r, Child.INPUT), s.text == null ? "" : s.text);
        ops.text(Ref.row(r, Child.LIMIT), repeat ? "" : "blank: " + session.limits().maxIterations());
        ops.bind(Ref.row(r, Child.KIND), Trigger.CHANGE, "loopKind", Map.of("Row", Integer.toString(j), "Gen", g()),
            with(editorCaptures(), "Value", Ref.row(r, Child.KIND)));
        ops.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "stepEdit", Map.of("Row", Integer.toString(j), "Field", "text", "Gen", g()),
            Map.of("Value", Ref.row(r, Child.INPUT)));
        moveButtons(ops, r, j);
        problem(ops, r, "");
    }

    /** A check: its type selector, or for one this game can't answer, its type, read-only. */
    private void conditionRow(ViewOps ops, int r, int j, MacroDraft.Step s) {
        rows.add(new Row(Kind.FIELD, null));
        ops.append(Part.ROWS, RowKind.CONDITION_ROW);
        ops.text(Ref.row(r, Child.DEPTH), ">".repeat(s.depth()));
        ops.text(Ref.row(r, Child.KIND), "Check");
        boolean editable = editable(s);
        ops.visible(Ref.row(r, Child.INPUT), editable);
        if (editable) {
            List<Entry> types = new ArrayList<>();
            for (ConditionPort.Form f : forms.values()) types.add(new Entry(f.owner() == null ? f.label() : f.owner() + ": " + f.label(), f.type()));
            types.addAll(LOGIC);
            ops.entries(Ref.row(r, Child.INPUT), types);
            ops.value(Ref.row(r, Child.INPUT), s.text);
            ops.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "checkType", Map.of("Row", Integer.toString(j), "Gen", g()),
                with(editorCaptures(), "Value", Ref.row(r, Child.INPUT)));
        }
        ops.text(Ref.row(r, Child.TEXT), editable ? "" : s.raw() ? "a check this editor can't show" : s.text);
        moveButtons(ops, r, j);
        problem(ops, r, "");
    }

    /** One field of a check, under it: the controls its kind needs. */
    private void fieldRow(ViewOps ops, int r, int j, int k, MacroDraft.Step s) {
        rows.add(new Row(Kind.FIELD, null));
        ops.append(Part.ROWS, RowKind.FIELD_ROW);
        ConditionPort.Field f = fieldOf(j, k);
        ops.text(Ref.row(r, Child.LABEL), f.title());
        ConditionPort.FieldKind kind = f.kind();
        String choice = checkFields.choice(f, s);
        List<Entry> choices = choices(f, choice);
        boolean position = kind == ConditionPort.FieldKind.POSITION || kind == ConditionPort.FieldKind.PLACE && choice.equals("position");
        boolean text = !position && kind != ConditionPort.FieldKind.OP && kind != ConditionPort.FieldKind.UNIT
            && kind != ConditionPort.FieldKind.ENUM && kind != ConditionPort.FieldKind.BOOLEAN;
        ops.visible(Ref.row(r, Child.CHOICE), !choices.isEmpty());
        if (!choices.isEmpty()) {
            ops.entries(Ref.row(r, Child.CHOICE), choices);
            ops.value(Ref.row(r, Child.CHOICE), choice);
            ops.bind(Ref.row(r, Child.CHOICE), Trigger.CHANGE, "checkChoice", Map.of("Row", Integer.toString(j), "Field", Integer.toString(k), "Gen", g()),
                with(editorCaptures(), "Value", Ref.row(r, Child.CHOICE)));
        }
        ops.visible(Ref.row(r, Child.INPUT), text);
        if (text) {
            ops.value(Ref.row(r, Child.INPUT), checkFields.text(f, s));
            ops.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "checkEdit", Map.of("Row", Integer.toString(j), "Field", Integer.toString(k), "Part", "V", "Gen", g()),
                Map.of("Value", Ref.row(r, Child.INPUT)));
        }
        Child[] coords = {Child.X, Child.Y, Child.Z};
        for (int c = 0; c < coords.length; c++) {
            boolean shown = position && c < checkFields.coordinates();
            ops.visible(Ref.row(r, coords[c]), shown);
            if (!shown) continue;
            ops.value(Ref.row(r, coords[c]), checkFields.coord(f, s, c));
            ops.bind(Ref.row(r, coords[c]), Trigger.CHANGE, "checkEdit", Map.of("Row", Integer.toString(j), "Field", Integer.toString(k),
                "Part", coords[c].name(), "Gen", g()), Map.of("Value", Ref.row(r, coords[c])));
        }
        ops.visible(Ref.row(r, Child.HERE), position);
        if (position) {
            ops.bind(Ref.row(r, Child.HERE), Trigger.ACTIVATE, "checkHere", Map.of("Row", Integer.toString(j), "Field", Integer.toString(k), "Gen", g()),
                editorCaptures());
            ops.disabled(Ref.row(r, Child.HERE), draftReadOnly || session.position() == null);
        }
        boolean flag = kind == ConditionPort.FieldKind.BOOLEAN;
        ops.visible(Ref.row(r, Child.FLAG), flag);
        if (flag) {
            ops.value(Ref.row(r, Child.FLAG), checkFields.flag(f, s));
            ops.bind(Ref.row(r, Child.FLAG), Trigger.CHANGE, "checkEdit", Map.of("Row", Integer.toString(j), "Field", Integer.toString(k), "Part", "B", "Gen", g()),
                Map.of("Value", Ref.row(r, Child.FLAG)));
        }
        hint(ops, r, f.description());
        problem(ops, r, "");
    }

    /** A field's selector entries: empty for a field without one. */
    private static List<Entry> choices(ConditionPort.Field f, String current) {
        List<Entry> out = new ArrayList<>();
        switch (f.kind()) {
            case EITHER:
                for (String o : f.options()) out.add(new Entry(o.isEmpty() ? o : Character.toUpperCase(o.charAt(0)) + o.substring(1), o));
                break;
            case PLACE:
                out.add(new Entry("Position", "position"));
                out.add(new Entry("Placeholder", "placeholder"));
                out.add(new Entry("Marker", "marker"));
                break;
            case OP:
                if (!CheckFields.OPS.contains(current)) out.add(new Entry("", current));
                for (int i = 0; i < CheckFields.OPS.size(); i++) out.add(new Entry(CheckFields.OP_LABELS.get(i), CheckFields.OPS.get(i)));
                break;
            case UNIT:
                for (String u : CheckFields.UNITS) out.add(new Entry(u, u));
                break;
            case ENUM:
                if (!f.required() || !f.options().contains(current)) out.add(new Entry("", ""));
                for (String o : f.options()) out.add(new Entry(o, o));
                break;
            default:
                break;
        }
        return out;
    }

    /** "+ Check" at the end of a While's or Until's block. */
    private void addCheckRow(ViewOps ops, int r, int loop, MacroDraft.Step s) {
        rows.add(new Row(Kind.FIELD, null));
        ops.append(Part.ROWS, RowKind.ADD_CHECK_ROW);
        ops.text(Ref.row(r, Child.DEPTH), ">".repeat(s.depth() + 1));
        ops.bind(Ref.row(r, Child.ADD_CHECK), Trigger.ACTIVATE, "checkAdd", Map.of("Row", Integer.toString(loop), "Gen", g()), editorCaptures());
        ops.disabled(Ref.row(r, Child.ADD_CHECK), draftReadOnly);
    }

    /** A row's problem, under it; hidden when there's none. */
    private static void problem(ViewOps ops, int row, String text) {
        boolean shown = text != null && !text.isBlank();
        ops.text(Ref.row(row, Child.PROBLEM), shown ? text : "");
        ops.visible(Ref.row(row, Child.PROBLEM), shown);
    }

    /**
     * Every editor field's current value, captured with a click: {@code H<field>}; each step's {@code T}, {@code F}
     * and {@code N}; and each check field's parts, {@code <part><step>f<field>} (V text, K selector, X Y Z
     * coordinates, B box). The kind and type selectors aren't captured: they change the rows, so they act alone.
     */
    private Map<String, Ref> editorCaptures() {
        Map<String, Ref> m = new LinkedHashMap<>();
        for (int h = 0; h < HEADER_FIELDS.length; h++) m.put("H" + h, Ref.row(h, Child.INPUT));
        if (draft == null) return m;
        for (int x = 0; x < layout.size(); x++) {
            EditorRow e = layout.get(x);
            int r = HEADER_ROWS + x;
            int j = e.step();
            switch (e.kind()) {
                case STEP_ROW -> {
                    m.put("T" + j, Ref.row(r, Child.INPUT));
                    m.put("F" + j, Ref.row(r, Child.FLAG));
                }
                case LOOP_ROW -> m.put("T" + j, Ref.row(r, Child.INPUT));
                case TEXT_ROW -> m.put("N" + j, Ref.row(r, Child.INPUT));
                case FIELD_ROW -> {
                    String at = j + "f" + e.field();
                    for (Map.Entry<String, Child> p : parts(fieldOf(j, e.field())).entrySet()) m.put(p.getKey() + at, Ref.row(r, p.getValue()));
                }
                default -> {
                }
            }
        }
        return m;
    }

    /** The parts of a field's row that hold its value, by capture letter, selector first. */
    private static Map<String, Child> parts(ConditionPort.Field f) {
        Map<String, Child> out = new LinkedHashMap<>();
        switch (f.kind()) {
            case EITHER, PLACE -> out.put("K", Child.CHOICE);
            case OP, UNIT, ENUM -> {
                out.put("K", Child.CHOICE);
                return out;
            }
            case BOOLEAN -> {
                out.put("B", Child.FLAG);
                return out;
            }
            default -> {
            }
        }
        if (f.kind() != ConditionPort.FieldKind.POSITION) out.put("V", Child.INPUT);
        if (f.kind() == ConditionPort.FieldKind.POSITION || f.kind() == ConditionPort.FieldKind.PLACE) {
            out.put("X", Child.X);
            out.put("Y", Child.Y);
            out.put("Z", Child.Z);
        }
        return out;
    }

    private void captureEditor(Map<String, Object> data) {
        if (draft == null) return;
        for (int h = 0; h < HEADER_FIELDS.length; h++) {
            if (data.containsKey("H" + h)) header(HEADER_FIELDS[h], data.get("H" + h));
        }
        for (int x = 0; x < layout.size(); x++) {
            EditorRow e = layout.get(x);
            int j = e.step();
            switch (e.kind()) {
                case STEP_ROW, LOOP_ROW -> {
                    if (data.containsKey("T" + j)) stepEdit(j, "text", data.get("T" + j));
                    if (data.containsKey("F" + j)) stepEdit(j, "flag", data.get("F" + j));
                }
                case TEXT_ROW -> {
                    if (data.containsKey("N" + j)) stepEdit(j, "note", data.get("N" + j));
                }
                case FIELD_ROW -> {
                    String at = j + "f" + e.field();
                    for (String p : parts(fieldOf(j, e.field())).keySet()) {
                        if (!data.containsKey(p + at)) continue;
                        if (p.equals("K")) checkChoice(j, e.field(), data.get(p + at));
                        else checkEdit(j, e.field(), p, data.get(p + at));
                    }
                }
                default -> {
                }
            }
        }
    }

    private void header(String field, Object value) {
        if (draft == null) return;
        String v = str(value);
        switch (field) {
            case "id":
                if (!v.equals(draft.id)) dirty = true;
                draft.id = v;
                break;
            case "name":
                if (!v.equals(draft.name)) dirty = true;
                draft.name = v;
                break;
            case "description": {
                String old = draft.description == null ? "" : draft.description;
                if (!v.equals(old)) dirty = true;
                draft.description = v;
                break;
            }
            case "category": {
                String old = draft.category == null ? "" : draft.category;
                if (!v.equals(old)) dirty = true;
                draft.category = v;
                break;
            }
            case "danger":
                if (draftReadOnly) return;
                boolean d = "true".equalsIgnoreCase(v);
                if (d != draft.dangerous) dirty = true;
                draft.dangerous = d;
                break;
            case "where":
                if (draftReadOnly) return;
                Scope s = "SERVER".equals(v) && session.open().operator() ? Scope.SERVER : Scope.PLAYER;
                if (s != draftScope) dirty = true;
                draftScope = s;
                break;
            default:
                break;
        }
    }

    private void stepEdit(int j, String field, Object value) {
        if (draft == null || draftReadOnly || j < 0 || j >= draft.steps().size()) return;
        MacroDraft.Step s = draft.steps().get(j);
        if ("flag".equals(field)) {
            boolean f = "true".equalsIgnoreCase(str(value));
            if (f != s.flag) dirty = true;
            s.flag = f;
        } else if ("note".equals(field)) {
            String n = str(value);
            if (!n.equals(s.note == null ? "" : s.note)) dirty = true;
            s.note = n;
        } else if (s.kind != MacroDraft.Kind.CHECK) {
            // A check's text is its type, which only its selector changes.
            String t = str(value);
            if (!t.equals(s.text)) dirty = true;
            s.text = t;
        }
    }

    private void stepOp(ViewOps ops, int j, String op) {
        if (draft == null || draftReadOnly) return;
        boolean done;
        String what = "";
        switch (op) {
            case "up":
                done = draft.moveUp(j);
                break;
            case "down":
                done = draft.moveDown(j);
                break;
            case "in":
                done = draft.indent(j);
                break;
            case "out":
                done = draft.outdent(j);
                break;
            case "del": {
                int n = draft.delete(j);
                done = n > 0;
                what = n > 1 ? "Deleted a step and the " + (n - 1) + " inside it." : "Deleted a step.";
                break;
            }
            default:
                done = false;
        }
        if (!done) return;
        dirty = true;
        status = what;
        renderRight(ops);
    }

    private void stepAdd(ViewOps ops, String kind) {
        if (draft == null || draftReadOnly) return;
        try {
            draft.add(MacroDraft.Kind.valueOf(kind));
        } catch (IllegalArgumentException e) {
            return;
        }
        dirty = true;
        status = "";
        renderRight(ops);
    }

    /** The editor's loop and check buttons and selectors; each one redraws the rows. */
    private void checkOp(ViewOps ops, String action, int j, int k, Object value) {
        if (draft == null || draftReadOnly || j < 0 || j >= draft.steps().size()) return;
        MacroDraft.Step s = draft.steps().get(j);
        boolean done;
        switch (action) {
            case "loopKind": {
                MacroDraft.Kind kind;
                try {
                    kind = MacroDraft.Kind.valueOf(str(value));
                } catch (IllegalArgumentException e) {
                    return;
                }
                done = draft.setLoopKind(j, kind);
                break;
            }
            case "checkType": {
                String type = str(value);
                if (!MacroDraft.logic(type) && !forms.containsKey(type)) return;
                Object subject = s.fields.get("subject");
                done = draft.setCheckType(j, type);
                if (done) startFields(s, subject);
                break;
            }
            case "checkAdd":
                done = draft.addCheck(j) >= 0;
                break;
            case "checkChoice":
                // The selector may change which boxes the row shows, so it redraws even when the capture applied it.
                done = checkChoice(j, k, value) || s.kind == MacroDraft.Kind.CHECK;
                break;
            default: {
                ConditionPort.Field f = s.kind == MacroDraft.Kind.CHECK ? fieldOf(j, k) : null;
                if (f == null) return;
                double[] here = session.position();
                if (here == null) {
                    setStatus(ops, "Your position isn't known here. Type the coordinates instead.");
                    return;
                }
                done = checkFields.here(f, s, here);
            }
        }
        if (!done) return;
        dirty = true;
        status = "";
        renderRight(ops);
    }

    /** A check's fields when its type is picked: the person it was about, and a comparison to start from. */
    private void startFields(MacroDraft.Step s, Object subject) {
        ConditionPort.Form f = forms.get(s.text);
        if (f == null) return;
        for (ConditionPort.Field field : f.fields()) {
            if (field.kind() == ConditionPort.FieldKind.SUBJECT && field.name().equals("subject") && subject != null) s.fields.put("subject", subject);
            if (field.kind() == ConditionPort.FieldKind.OP) s.fields.put(field.name(), "ge");
        }
    }

    private boolean checkChoice(int j, int k, Object value) {
        if (draft == null || draftReadOnly || j < 0 || j >= draft.steps().size()) return false;
        MacroDraft.Step s = draft.steps().get(j);
        ConditionPort.Field f = s.kind == MacroDraft.Kind.CHECK && editable(s) ? fieldOf(j, k) : null;
        if (f == null || !checkFields.setChoice(f, s, str(value))) return false;
        dirty = true;
        return true;
    }

    /** Typing in a check's field: {@code part} V for its text, X, Y or Z for a coordinate, B for its box. */
    private void checkEdit(int j, int k, String part, Object value) {
        if (draft == null || draftReadOnly || j < 0 || j >= draft.steps().size()) return;
        MacroDraft.Step s = draft.steps().get(j);
        ConditionPort.Field f = s.kind == MacroDraft.Kind.CHECK && editable(s) ? fieldOf(j, k) : null;
        if (f == null) return;
        boolean changed;
        switch (part) {
            case "V":
                changed = checkFields.setText(f, s, str(value));
                break;
            case "X":
            case "Y":
            case "Z":
                changed = checkFields.setCoord(f, s, part.charAt(0) - 'X', str(value));
                break;
            case "B":
                changed = checkFields.setFlag(f, s, "true".equalsIgnoreCase(str(value)));
                break;
            default:
                changed = false;
        }
        if (changed) dirty = true;
    }

    /** The draft as it would be saved, or the first reason it can't be. */
    private Problem check() {
        MacroDraft.Built b = draft.build();
        if (!b.ok()) return Problem.of("invalid", b.problem());
        Macro m = draft.toMacro();
        MacroReader.Result<Macro> back = MacroReader.readDocument(Json.parse(Json.write(MacroWriter.document(m))));
        if (!back.ok()) return back.problem();
        return MacroValidator.check(back.value().body(), session.limits(), session.conditions());
    }

    void renderValidation(ViewOps ops) {
        if (tab != Tab.MACROS || draft == null) return;
        Problem p = check();
        int n = draft.steps().size();
        ops.text(Ref.of(Part.PREVIEW), "Macro " + draft.id + ": " + n + (n == 1 ? " step" : " steps") + (dirty ? " (unsaved changes)" : ""));
        ops.text(Ref.of(Part.PREVIEW_NOTE), p == null ? "Ready to save." : "Can't save yet: " + readable(p));
        Map<Integer, List<String>> under = rowProblems();
        for (int x = 0; x < layout.size(); x++) {
            RowKind k = layout.get(x).kind();
            if (k == RowKind.LOOP_ROW || k == RowKind.CONDITION_ROW || k == RowKind.FIELD_ROW) {
                List<String> found = under.get(HEADER_ROWS + x);
                problem(ops, HEADER_ROWS + x, found == null ? "" : String.join(" ", found));
            }
        }
    }

    /**
     * Each loop's and check's problems, by the row they're shown under: what stops the draft being built, and what
     * the game's condition library says about each loop's condition, under the check (or the check's field) its
     * pointer names. A check the game can't answer is told so under its own row.
     */
    private Map<Integer, List<String>> rowProblems() {
        Map<Integer, List<String>> out = new TreeMap<>();
        MacroDraft.Built b = draft.build();
        if (!b.ok() && b.step() >= 0) add(out, rowOf(b.step()), b.problem());
        ConditionPort port = session.conditions();
        List<MacroDraft.Step> steps = draft.steps();
        for (int j = 0; j < steps.size(); j++) {
            if (!steps.get(j).kind.checked()) continue;
            Map<String, Object> condition = draft.condition(j);
            if (condition == null) continue;
            Map<String, Integer> where = draft.conditionSteps(j);
            for (ConditionPort.Issue issue : ConditionPort.issues(port, condition)) {
                String ptr = issue.pointer() == null ? "" : issue.pointer();
                String best = "";
                for (String at : where.keySet()) {
                    if ((ptr.equals(at) || ptr.startsWith(at + "/")) && at.length() >= best.length()) best = at;
                }
                int step = where.getOrDefault(best, j);
                String rest = ptr.substring(best.length());
                int row = -1;
                if (rest.length() > 1 && steps.get(step).kind == MacroDraft.Kind.CHECK) {
                    int slash = rest.indexOf('/', 1);
                    String name = rest.substring(1, slash < 0 ? rest.length() : slash);
                    row = fieldRowOf(step, name.replace("~1", "/").replace("~0", "~"));
                }
                add(out, row >= 0 ? row : rowOf(step), issue.message());
            }
        }
        return out;
    }

    private static void add(Map<Integer, List<String>> out, int row, String message) {
        if (row < 0) return;
        List<String> list = out.computeIfAbsent(row, k -> new ArrayList<>());
        if (!list.contains(message)) list.add(message);
    }

    private static String readable(Problem p) {
        String m = p.message();
        Object ptr = p.detail().get("pointer");
        if ("/id".equals(ptr)) return "the id uses lowercase letters, digits, _ . and - only, and starts with a letter or digit";
        if ("/name".equals(ptr)) return "the name must not be empty, and at most 60 characters";
        return m;
    }

    private void save(ViewOps ops) {
        if (draft == null) return;
        if (draftNeeds != null) {
            setStatus(ops, "Not saved: this macro needs the permission " + draftNeeds + ", which you don't have.");
            return;
        }
        Scope scope = draftReadOnly ? Scope.PLAYER : draftScope;
        if (scope == Scope.SERVER ? !session.open().operator() : !session.open().canEditOwn()) {
            setStatus(ops, scope == Scope.SERVER ? "Only operators can save server macros." : "You may not save macros on this server.");
            return;
        }
        if (draft.name == null || draft.name.isBlank()) draft.name = draft.id;
        // The editor writes scoped references, and says which called macro
        // needs a permission, since lists only check the top macro's.
        Calls.Place from = scope == Scope.SERVER ? Calls.Place.SERVER : Calls.Place.PLAYER;
        List<String> needs = new ArrayList<>();
        draft.scopeCalls(ref -> {
            Scoped s = scoped(ref, from);
            if (s.permission != null && !needs.contains(s.ref + " needs " + s.permission)) needs.add(s.ref + " needs " + s.permission);
            return s.ref;
        });
        Problem p = check();
        if (p != null) {
            setStatus(ops, "Not saved: " + readable(p));
            return;
        }
        Macro m = draft.toMacro();
        // A copy of a preset or of someone else's macro is a new macro; so is a macro moved to the other place.
        String replacing = draftReadOnly || macroKey == null || macroKey.equals("new") || scopeOf(macroKey) != scope ? null : draftOriginalId;
        p = session.saveMacro(scope, m, replacing);
        if (p != null) {
            setStatus(ops, "Not saved: " + readable(p));
            return;
        }
        draftOriginalId = m.id();
        draftScope = scope;
        draftReadOnly = false;
        draftNeeds = null;
        dirty = false;
        macroKey = (scope == Scope.SERVER ? "server:" : "mine:") + m.id();
        status = "Saved " + m.name() + " (" + m.id() + ")." + (needs.isEmpty() ? ""
            : " Only players with these permissions can run it: " + String.join("; ", needs) + ".");
        renderList(ops);
        renderRight(ops);
    }

    private record Scoped(String ref, String permission) {}

    /**
     * A call's reference, scoped as it resolves from {@code from} among the macros and presets this window
     * knows, with the called macro's permission; a reference that resolves to nothing is kept as written, and the
     * server refuses it at save.
     */
    private Scoped scoped(String ref, Calls.Place from) {
        MacroLibrary known = new MacroLibrary() {
            @Override
            public Macro playerMacro(String presserId, String id) {
                return find(session.macros(Scope.PLAYER), id);
            }

            @Override
            public Macro serverMacro(String id) {
                return find(session.macros(Scope.SERVER), id);
            }
        };
        Calls.Target t = MacroLookup.find(known, session.catalog(), "me", ref, from);
        return t == null ? new Scoped(ref, null) : new Scoped(t.ref(), t.permission());
    }

    private static Macro find(List<Macro> macros, String id) {
        for (Macro m : macros) {
            if (m.id().equals(id)) return m;
        }
        return null;
    }

    private static Scope scopeOf(String key) {
        return key.startsWith("server:") ? Scope.SERVER : Scope.PLAYER;
    }

    private void runMacro(ViewOps ops) {
        if (draft == null) return;
        if (draftNeeds != null) {
            setStatus(ops, "Not run: this macro needs the permission " + draftNeeds + ", which you don't have.");
            return;
        }
        boolean preset = macroKey != null && macroKey.startsWith("preset:");
        if (dirty || (!preset && draftOriginalId == null)) {
            setStatus(ops, "Save the macro first, so what runs is what is saved.");
            return;
        }
        for (MacroDraft.Step s : draft.steps()) {
            if (s.kind == MacroDraft.Kind.CHECK && !editable(s)) {
                setStatus(ops, "Not run: remove the checks this game can't answer first.");
                return;
            }
        }
        // A scoped reference runs exactly the macro that is open, whatever else shares its id.
        String ref = preset ? macroKey : (draftScope == Scope.SERVER ? "server:" : "player:") + draftOriginalId;
        run(ops, new RunRequest(List.of(RunItem.ref(ref)), SOURCE));
    }

    /**
     * Your own macros can be deleted with the edit permission, even one that needs a permission you lack;
     * server macros by operators.
     */
    private boolean mayDelete() {
        if (draft == null || draftOriginalId == null) return false;
        if (draftNeeds != null) return draftScope == Scope.PLAYER && session.open().canEditOwn();
        return !draftReadOnly;
    }

    private void askDelete(ViewOps ops) {
        if (!mayDelete()) return;
        pending = Pending.delete(draftScope, draftOriginalId);
        showConfirm(ops, "Delete the macro " + draft.name + " (" + draftOriginalId + ")? This can't be undone.", "");
        setStatus(ops, "Press Yes to delete, or No to keep it.");
    }

    // ---- runs -----------------------------------------------------------------------------------------

    private List<RunStatus> runs() {
        return session.running();
    }

    private void renderRuns(ViewOps ops) {
        ops.text(Ref.of(Part.HEADING), "Runs");
        ops.text(Ref.of(Part.META), session.open().operator() ? "Every run going on the server. Stop takes effect before the next step." : "Your runs. Stop takes effect before the next step.");
        List<RunStatus> list = runs();
        for (int i = 0; i < list.size(); i++) {
            RunStatus s = list.get(i);
            int r = rows.size();
            rows.add(new Row(Kind.NOTE, null));
            ops.append(Part.ROWS, RowKind.RUN_ROW);
            ops.text(Ref.row(r, Child.TEXT), RunText.line(s));
            ops.bind(Ref.row(r, Child.STOP), Trigger.ACTIVATE, "stopRun", Map.of("Run", s.runId()), Map.of());
        }
        if (list.isEmpty()) note(ops, "No runs going.");
        ops.append(Part.FOOTER, RowKind.RUNS_FOOTER);
        ops.bind(Ref.footer(Child.REFRESH), Trigger.ACTIVATE, "refresh", Map.of(), Map.of());
        ops.bind(Ref.footer(Child.STOP_ALL), Trigger.ACTIVATE, "stopAll", Map.of(), Map.of());
        ops.visible(Ref.footer(Child.STOP_ALL), session.open().operator());
        ops.text(Ref.of(Part.PREVIEW), "");
        ops.text(Ref.of(Part.PREVIEW_NOTE), "");
    }

    private void stopRun(ViewOps ops, String runId) {
        boolean ok = session.stop(runId);
        status = ok ? "Asked run " + runId + " to stop." : "Run " + runId + " isn't going, or isn't yours to stop.";
        renderList(ops);
        renderRight(ops);
    }

    private void stopAll(ViewOps ops) {
        int n = session.stopAll();
        status = n < 0 ? "Only operators can stop everyone's runs." : "Asked " + n + (n == 1 ? " run" : " runs") + " to stop.";
        renderList(ops);
        renderRight(ops);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private String g() {
        return String.valueOf(gen);
    }

    private static int parseInt(String s, int otherwise) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    static String str(Object o) {
        if (o == null) return "";
        if (o instanceof Double && (Double) o == Math.rint((Double) o) && !((Double) o).isInfinite()) return Long.toString(((Double) o).longValue());
        return o.toString();
    }
}
