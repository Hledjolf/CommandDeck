package commanddeck.hytale.window;

import commanddeck.api.CheckResult;
import commanddeck.api.Presser;
import commanddeck.api.RunItem;
import commanddeck.api.RunRequest;
import commanddeck.api.RunStatus;
import commanddeck.api.StartResult;
import commanddeck.command.Arg;
import commanddeck.command.ArgStyle;
import commanddeck.command.ArgType;
import commanddeck.command.CommandCatalog;
import commanddeck.command.CommandInfo;
import commanddeck.command.Example;
import commanddeck.command.Level;
import commanddeck.command.Preset;
import commanddeck.command.Variant;
import commanddeck.hytale.HytaleLines;
import commanddeck.hytale.RunText;
import commanddeck.hytale.window.MacroShelf.Scope;
import commanddeck.json.Json;
import commanddeck.macro.Macro;
import commanddeck.macro.MacroReader;
import commanddeck.macro.MacroValidator;
import commanddeck.macro.MacroWriter;
import commanddeck.macro.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.TreeSet;

/**
 * One player's CommandDeck window, as plain state plus rendering into {@link UiOps}. Three tabs:
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
 * the client drops events while an update is unacknowledged. Each redraw of the right side bumps a
 * generation number, and events from an older one are ignored.
 */
public final class DeckWindow {
    public static final String DIR = "CommandDeck/";
    public static final String DOC = DIR + "Window.ui";
    public static final String LIST_ROW = DIR + "ListRow.ui";
    public static final String LIST_HEADING = DIR + "ListHeading.ui";

    /** Shown on a command the game says this player may not run. Running stays possible: the game decides. */
    static final String NO_PERMISSION = "You don't have the game's permission for this command. You can still press Run, but the game "
        + "will probably refuse it.";
    public static final String NOTE_ROW = DIR + "NoteRow.ui";
    public static final String TEXT_ROW = DIR + "TextRow.ui";
    public static final String CHECK_ROW = DIR + "CheckRow.ui";
    public static final String CHOICE_ROW = DIR + "ChoiceRow.ui";
    public static final String STEP_ROW = DIR + "StepRow.ui";
    public static final String RUN_ROW = DIR + "RunRow.ui";
    public static final String FORM_FOOTER = DIR + "FormFooter.ui";
    public static final String EDITOR_FOOTER = DIR + "EditorFooter.ui";
    public static final String RUNS_FOOTER = DIR + "RunsFooter.ui";

    /** Every document the window uses (the plugin checks they are registered). */
    public static final List<String> DOCUMENTS = List.of(DOC, LIST_ROW, LIST_HEADING, NOTE_ROW, TEXT_ROW, CHECK_ROW, CHOICE_ROW, STEP_ROW, RUN_ROW,
        FORM_FOOTER, EDITOR_FOOTER, RUNS_FOOTER);

    /** The most commands the list shows at once; the search narrows it. */
    static final int MAX_LIST = 150;
    /** The most ids an item or block picker offers at once; typing in the field narrows them. */
    static final int MAX_MATCHES = 40;
    /** The editor's rows before the first step: id, name, ask first, where it is saved, description, category, show notes. */
    static final int HEADER_ROWS = 7;
    /** The header fields captured with every editor click, in row order (the notes switch is view state). */
    private static final String[] HEADER_FIELDS = {"id", "name", "danger", "where", "description", "category"};
    /** The Macros tab's heading for macros and presets without a category; it comes last. */
    static final String OTHER = "Other";
    static final String SOURCE = "window";

    public enum Tab {
        COMMANDS, MACROS, RUNS
    }

    /** What a form row holds. */
    private enum RowKind {
        NOTE, VARIANT, FIELD, RAW, RECENT, MATCHES
    }

    private record Row(RowKind kind, Arg arg) {}

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

    private final Host host;
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

    public DeckWindow(Host host) {
        this.host = host;
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
    public synchronized boolean unsaved() {
        return dirty && draft != null;
    }

    public String selectedCommand() {
        return commandName;
    }

    /** Everything after the main document is appended: the static bindings plus both sides. */
    public synchronized UiOps open() {
        UiOps ops = new UiOps();
        for (Tab t : Tab.values()) ops.bind("#Tab" + cap(t), "Activating", Map.of("Action", "tab", "Tab", t.name()));
        ops.set("#Search.Value", query);
        ops.bind("#Search", "ValueChanged", Map.of("Action", "search", "@Query", "#Search.Value"));
        ops.bind("#Filter", "ValueChanged", Map.of("Action", "filter", "@Value", "#Filter.Value"));
        ops.bind("#ConfirmYes", "Activating", Map.of("Action", "confirm"));
        ops.bind("#ConfirmNo", "Activating", Map.of("Action", "cancel"));
        // The window outlives its page: a question left open when it was closed is dropped, and unsaved edits wait.
        pending = null;
        if (dirty && draft != null) status = "Your unsaved changes to " + label(draft) + " are still here, in the Macros tab.";
        renderAll(ops);
        return ops;
    }

    /** Whether the page should redraw this event's result only once typing pauses (see {@link #deferredOps}). */
    public static boolean deferred(String action) {
        return "search".equals(action) || "field".equals(action) || "stepEdit".equals(action) || "header".equals(action);
    }

    /** The redraw for a deferred event, built from the latest state. */
    public synchronized UiOps deferredOps(String action) {
        UiOps ops = new UiOps();
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

    /** A client event: its {@code Action} and the rest of its data. */
    public record Event(String action, Map<String, Object> data) {}

    /**
     * Decodes the data string of a client's page event (the JSON object built from a binding, with each
     * {@code @} key replaced by the element's value). Throws if it isn't a JSON object.
     */
    public static Event decode(String rawData) {
        Map<String, Object> data = new LinkedHashMap<>(Json.parseObject(rawData));
        Object a = data.remove("Action");
        return new Event(a == null ? null : a.toString(), data);
    }

    /** Handles one event from the page. The returned ops are sent as a partial update (possibly none). */
    public synchronized UiOps onEvent(String action, Map<String, Object> data) {
        UiOps ops = new UiOps();
        if (action == null) return ops;
        switch (action) {
            case "tab":
                switchTab(ops, str(data.get("Tab")));
                break;
            case "search":
                query = str(data.get("@Query"));
                break;
            case "filter":
                filter = str(data.get("@Value"));
                if (filter.isEmpty()) filter = "all";
                renderList(ops);
                break;
            case "pick":
                pick(ops, str(data.get("Key")));
                break;
            case "variant":
                if (!sameGen(data)) break;
                captureForm(data);
                variant = parseInt(str(data.get("@Value")), -1);
                renderRight(ops);
                break;
            case "field":
                if (!sameGen(data)) break;
                storeField(parseInt(str(data.get("Row")), -1), data.get("@Value"));
                break;
            case "recent":
                if (!sameGen(data)) break;
                recent(ops, str(data.get("@Value")));
                break;
            case "match":
                if (!sameGen(data)) break;
                match(ops, parseInt(str(data.get("Row")), -1), str(data.get("@Value")));
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
                header(str(data.get("Field")), data.get("@Value"));
                break;
            case "stepEdit":
                if (!sameGen(data)) break;
                stepEdit(parseInt(str(data.get("Row")), -1), str(data.get("Field")), data.get("@Value"));
                break;
            case "notes":
                if (!sameGen(data)) {
                    stale(ops);
                    break;
                }
                captureEditor(data);
                showNotes = "true".equalsIgnoreCase(str(data.get("@Value")));
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
    private void notSent(UiOps ops) {
        renderPreview(ops);
        setStatus(ops, NOT_SENT);
    }

    static final String NOT_SENT = "Not sent. The message above says what to change.";

    private void stale(UiOps ops) {
        setStatus(ops, "The window was redrawn before that click arrived. Please click again.");
    }

    private void setStatus(UiOps ops, String s) {
        status = s;
        ops.text("#Status", s);
    }

    // ---- tabs and the list ----------------------------------------------------------------------------

    private void switchTab(UiOps ops, String name) {
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

    private void renderAll(UiOps ops) {
        for (Tab t : Tab.values()) ops.set("#Tab" + cap(t) + ".Disabled", t == tab);
        ops.visible("#Search", tab != Tab.RUNS);
        ops.visible("#FilterRow", tab != Tab.RUNS);
        ops.entries("#Filter", filterEntries());
        ops.set("#Filter.Value", filter);
        hideConfirm(ops);
        renderList(ops);
        renderRight(ops);
    }

    private List<UiOps.Entry> filterEntries() {
        List<UiOps.Entry> out = new ArrayList<>();
        if (tab == Tab.MACROS) {
            out.add(new UiOps.Entry("All macros", "all"));
            out.add(new UiOps.Entry("Mine", "mine"));
            out.add(new UiOps.Entry("Server", "server"));
            out.add(new UiOps.Entry("Presets", "presets"));
            return out;
        }
        out.add(new UiOps.Entry("All commands", "all"));
        out.add(new UiOps.Entry("Favorites", "favorites"));
        out.add(new UiOps.Entry("Ones I have permission for", "allowed"));
        out.add(new UiOps.Entry("Described by a sidecar", "level:described"));
        out.add(new UiOps.Entry("Typed (from the game)", "level:typed"));
        out.add(new UiOps.Entry("Raw (name and help only)", "level:raw"));
        Set<String> categories = new TreeSet<>();
        CommandCatalog cat = host.catalog();
        if (cat != null) {
            for (CommandInfo c : cat.visible()) {
                if (c.category() != null) categories.add(c.category());
            }
        }
        for (String c : categories) out.add(new UiOps.Entry("Category: " + c, "cat:" + c));
        return out;
    }

    void renderList(UiOps ops) {
        ops.clear("#List");
        if (tab == Tab.COMMANDS) {
            List<CommandInfo> all = commands();
            int shown = Math.min(all.size(), MAX_LIST);
            renderEntries(ops, commandEntries(all.subList(0, shown)));
            CommandCatalog cat = host.catalog();
            int total = cat == null ? 0 : cat.visible().size();
            ops.text("#Count", all.size() == total ? total + " commands" : all.size() + " of " + total + " commands"
                + (shown < all.size() ? " (first " + shown + " shown)" : ""));
        } else if (tab == Tab.MACROS) {
            List<String[]> entries = macroEntries();
            renderEntries(ops, entries);
            long macros = entries.stream().filter(e -> e[0] != null && !e[0].equals("new")).count();
            ops.text("#Count", macros + " macros and presets");
        } else {
            ops.text("#Count", RunText.count(runs().size(), "run") + " going");
        }
    }

    /** Each entry is {key, text}; a null key is a block's heading. */
    private void renderEntries(UiOps ops, List<String[]> entries) {
        for (int i = 0; i < entries.size(); i++) {
            String[] e = entries.get(i);
            String sel = "#List[" + i + "]";
            if (e[0] == null) {
                ops.append("#List", LIST_HEADING);
                ops.text(sel, e[1]);
                continue;
            }
            boolean selected = tab == Tab.COMMANDS ? e[0].equals(commandName) : e[0].equals(macroKey);
            ops.append("#List", LIST_ROW);
            ops.text(sel + " #Button #Label", e[1]);
            ops.set(sel + " #Button.Disabled", selected);
            ops.bind(sel + " #Button", "Activating", Map.of("Action", "pick", "Key", e[0]));
        }
    }

    /**
     * The commands in blocks: favorites, then the ones without the game's permission, then by level. Each
     * block is in name order. A command is in the first block it belongs to.
     */
    private List<String[]> commandEntries(List<CommandInfo> commands) {
        Set<String> favs = host.prefs().favorites(host.presser().id());
        Map<String, List<CommandInfo>> blocks = new LinkedHashMap<>();
        for (String h : new String[] {"Favorites", "No permission", "Described by a sidecar", "Typed (from the game)",
            "Raw (name and help only)"}) {
            blocks.put(h, new ArrayList<>());
        }
        for (CommandInfo c : commands) {
            String block = favs.contains(c.name()) ? "Favorites"
                : Boolean.FALSE.equals(host.mayRun(c.name())) ? "No permission"
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
            b.getValue().sort(java.util.Comparator.comparing((CommandInfo c) -> c.name().toLowerCase(Locale.ROOT)));
            for (CommandInfo c : b.getValue()) {
                out.add(new String[] {c.name(), "/" + c.name() + (c.label() != null ? "  " + c.label() : "")});
            }
        }
        return out;
    }

    /** The commands that match the search and the filter, in catalog order. */
    List<CommandInfo> commands() {
        CommandCatalog cat = host.catalog();
        List<CommandInfo> out = new ArrayList<>();
        if (cat == null) return out;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("/")) q = q.substring(1);
        Set<String> favs = host.prefs().favorites(host.presser().id());
        for (CommandInfo c : cat.visible()) {
            if (filter.equals("favorites") && !favs.contains(c.name())) continue;
            if (filter.equals("allowed") && Boolean.FALSE.equals(host.mayRun(c.name()))) continue;
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
        Map<String, List<String[]>> blocks = new TreeMap<>(DeckWindow::headingOrder);
        if (filter.equals("all") || filter.equals("mine")) {
            for (Macro m : host.macros().list(Scope.PLAYER, host.presser().id())) {
                String needs = holds(m.permission()) ? null : m.permission();
                String block = m.category() != null ? m.category() : OTHER;
                if (q.isEmpty() || (m.id() + " " + m.name() + " " + block).toLowerCase(Locale.ROOT).contains(q)) {
                    blocks.computeIfAbsent(block, k -> new ArrayList<>()).add(new String[] {"mine:" + m.id(), m.name(), m.id(), needs});
                }
            }
        }
        if (filter.equals("all") || filter.equals("server")) {
            for (Macro m : host.macros().list(Scope.SERVER, null)) {
                if (!holds(m.permission())) continue;
                String block = m.category() != null ? m.category() : OTHER;
                if (q.isEmpty() || (m.id() + " " + m.name() + " " + block).toLowerCase(Locale.ROOT).contains(q)) {
                    blocks.computeIfAbsent(block, k -> new ArrayList<>()).add(new String[] {"server:" + m.id(), m.name(), m.id(), null});
                }
            }
        }
        CommandCatalog cat = host.catalog();
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
            items.sort(java.util.Comparator.comparing((String[] x) -> x[1].toLowerCase(Locale.ROOT)).thenComparing(x -> x[2]));
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
        return host.deck().holds(host.presser(), permission);
    }

    private void pick(UiOps ops, String key) {
        pending = null;
        hideConfirm(ops);
        if (tab == Tab.COMMANDS) {
            CommandCatalog cat = host.catalog();
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

    void renderRight(UiOps ops) {
        gen++;
        ops.clear("#Rows");
        ops.clear("#Footer");
        rows.clear();
        if (tab == Tab.COMMANDS) {
            renderForm(ops);
        } else if (tab == Tab.MACROS) {
            renderEditor(ops);
        } else {
            renderRuns(ops);
        }
        ops.text("#Status", status);
    }

    private CommandInfo command() {
        CommandCatalog cat = host.catalog();
        return cat == null || commandName == null ? null : cat.find(commandName);
    }

    /** The arguments of the form shown: the main form or the chosen variant. */
    private List<Arg> formArgs(CommandInfo c) {
        if (variant >= 0 && variant < c.variants().size()) return c.variants().get(variant).args();
        return c.args();
    }

    private void renderForm(UiOps ops) {
        CommandInfo c = command();
        if (c == null) {
            commandName = null;
            ops.text("#Heading", "CommandDeck");
            ops.text("#Meta", "Pick a command on the left to fill in its form and run it.");
            note(ops, "Commands run as you, with your own permissions. A line that can't be sent exactly as shown is never sent.");
            ops.text("#Preview", "");
            ops.text("#PreviewNote", "");
            return;
        }
        ops.text("#Heading", "/" + c.name() + (c.label() != null ? "   " + c.label() : ""));
        StringBuilder meta = new StringBuilder(levelText(c.level()));
        if (!c.aliases().isEmpty()) meta.append("   Aliases: ").append(String.join(", ", c.aliases()));
        if (c.category() != null) meta.append("   Category: ").append(c.category());
        if (c.permission() != null) meta.append("   Permission: ").append(c.permission());
        if (c.danger() != commanddeck.command.Danger.NONE) {
            meta.append(c.danger() == commanddeck.command.Danger.SAVE_RISK ? "   Asks first: can damage the save" : "   Asks first");
        }
        ops.text("#Meta", meta.toString());
        if (Boolean.FALSE.equals(host.mayRun(c.name()))) {
            note(ops, NO_PERMISSION);
        }
        if (c.help() != null && !c.help().isBlank()) note(ops, c.help());
        if (c.description() != null && !c.description().isBlank() && !c.description().equals(c.help())) note(ops, c.description());
        for (Example e : c.examples()) note(ops, "Example: " + e.line() + (e.explanation() == null ? "" : "   " + e.explanation()));

        if (!c.variants().isEmpty()) {
            int r = rows.size();
            rows.add(new Row(RowKind.VARIANT, null));
            ops.append("#Rows", CHOICE_ROW);
            List<UiOps.Entry> forms = new ArrayList<>();
            forms.add(new UiOps.Entry("Main: " + summary(c.args()), "-1"));
            for (int i = 0; i < c.variants().size(); i++) {
                Variant v = c.variants().get(i);
                forms.add(new UiOps.Entry((v.label() != null ? v.label() + ": " : "") + summary(v.args()), Integer.toString(i)));
            }
            String sel = "#Rows[" + r + "]";
            ops.text(sel + " #Label", "Form");
            ops.entries(sel + " #Input", forms);
            ops.set(sel + " #Input.Value", Integer.toString(variant));
            hint(ops, sel, variantHint(c));
            ops.bind(sel + " #Input", "ValueChanged", withCaptures(Map.of("Action", "variant", "Gen", g(), "@Value", sel + " #Input.Value")));
        }

        if (c.level() == Level.RAW) {
            int r = rows.size();
            rows.add(new Row(RowKind.RAW, null));
            ops.append("#Rows", TEXT_ROW);
            String sel = "#Rows[" + r + "]";
            ops.text(sel + " #Label", "Arguments");
            ops.set(sel + " #Input.Value", rawText);
            hint(ops, sel, "Typed as you would in chat, after the command name. Sent as written.");
            fieldBind(ops, r);
            List<String> history = host.prefs().history(host.presser().id(), c.name());
            if (!history.isEmpty()) {
                int h = rows.size();
                rows.add(new Row(RowKind.RECENT, null));
                ops.append("#Rows", CHOICE_ROW);
                String hs = "#Rows[" + h + "]";
                List<UiOps.Entry> entries = new ArrayList<>();
                entries.add(new UiOps.Entry("", ""));
                for (String x : history) entries.add(new UiOps.Entry(x.isEmpty() ? "(nothing)" : x, x));
                ops.text(hs + " #Label", "Recent");
                ops.entries(hs + " #Input", entries);
                ops.set(hs + " #Input.Value", "");
                hint(ops, hs, "Pick one to put it back in the field.");
                ops.bind(hs + " #Input", "ValueChanged", Map.of("Action", "recent", "Gen", g(), "@Value", hs + " #Input.Value"));
            }
        } else {
            for (Arg a : formArgs(c)) renderField(ops, a);
            if (formArgs(c).isEmpty()) note(ops, "This form has no arguments.");
        }

        ops.append("#Footer", FORM_FOOTER);
        ops.bind("#Footer[0] #Run", "Activating", withCaptures(Map.of("Action", "run", "Gen", g())));
        ops.bind("#Footer[0] #AddToMacro", "Activating", withCaptures(Map.of("Action", "addToMacro", "Gen", g())));
        ops.bind("#Footer[0] #Favorite", "Activating", Map.of("Action", "favorite"));
        boolean fav = host.prefs().favorites(host.presser().id()).contains(c.name());
        ops.text("#Footer[0] #Favorite", fav ? "Unfavorite" : "Favorite");
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
    private static void hint(UiOps ops, String row, String text) {
        boolean shown = text != null && !text.isBlank();
        ops.text(row + " #Hint", shown ? text : "");
        ops.visible(row + " #Hint", shown);
    }

    private void note(UiOps ops, String text) {
        int r = rows.size();
        rows.add(new Row(RowKind.NOTE, null));
        ops.append("#Rows", NOTE_ROW);
        ops.text("#Rows[" + r + "]", text);
    }

    private void renderField(UiOps ops, Arg a) {
        int r = rows.size();
        rows.add(new Row(RowKind.FIELD, a));
        String sel = "#Rows[" + r + "]";
        String v = values.getOrDefault(a.name(), a.defaultValue() == null ? "" : String.valueOf(a.defaultValue()));
        values.putIfAbsent(a.name(), v);
        String label = (a.style() == ArgStyle.POSITIONAL ? a.name() : "--" + a.name()) + (a.required() ? "" : " (optional)");
        if (a.style() == ArgStyle.FLAG) {
            ops.append("#Rows", CHECK_ROW);
            ops.set(sel + " #Input.Value", "true".equalsIgnoreCase(v));
        } else if (choices(a) != null) {
            ops.append("#Rows", CHOICE_ROW);
            List<UiOps.Entry> entries = new ArrayList<>();
            for (String o : choices(a)) entries.add(new UiOps.Entry(o.isEmpty() ? "(none)" : o, o));
            ops.entries(sel + " #Input", entries);
            ops.set(sel + " #Input.Value", v);
        } else {
            ops.append("#Rows", TEXT_ROW);
            ops.set(sel + " #Input.Value", v);
        }
        ops.text(sel + " #Label", label);
        hint(ops, sel, hint(a));
        fieldBind(ops, r);
        if (a.style() != ArgStyle.FLAG && (a.type() == ArgType.ITEM || a.type() == ArgType.BLOCK) && !host.assetIds(a.type()).isEmpty()) {
            int m = rows.size();
            rows.add(new Row(RowKind.MATCHES, a));
            ops.append("#Rows", CHOICE_ROW);
            String ms = "#Rows[" + m + "]";
            ops.text(ms + " #Label", "   " + (a.type() == ArgType.ITEM ? "Items" : "Blocks"));
            ops.bind(ms + " #Input", "ValueChanged", Map.of("Action", "match", "Row", Integer.toString(r), "Gen", g(), "@Value", ms + " #Input.Value"));
            matchesInto(ops, m);
        }
    }

    /** The ids that contain what the field holds (those that start with it first), at most {@link #MAX_MATCHES}. */
    List<String> matches(ArgType type, String typed) {
        List<String> all = host.assetIds(type);
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

    private void matchesInto(UiOps ops, int m) {
        Arg a = rows.get(m).arg();
        List<String> ids = matches(a.type(), values.getOrDefault(a.name(), ""));
        List<UiOps.Entry> entries = new ArrayList<>();
        int n = lastMatchCount;
        entries.add(new UiOps.Entry(n == 0 ? "(no matches)" : n > ids.size() ? "(" + n + " matches; first " + ids.size() + " shown)"
            : "(" + n + (n == 1 ? " match)" : " matches)"), ""));
        for (String id : ids) entries.add(new UiOps.Entry(id, id));
        String ms = "#Rows[" + m + "]";
        ops.entries(ms + " #Input", entries);
        ops.set(ms + " #Input.Value", "");
        hint(ops, ms, "type in the field above to narrow the list, then pick one");
    }

    private void renderMatches(UiOps ops) {
        if (tab != Tab.COMMANDS) return;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind() == RowKind.MATCHES) matchesInto(ops, i);
        }
    }

    /** A picked id goes into the field it belongs to. */
    private void match(UiOps ops, int fieldRow, String id) {
        if (id.isEmpty() || fieldRow < 0 || fieldRow >= rows.size() || rows.get(fieldRow).kind() != RowKind.FIELD) return;
        values.put(rows.get(fieldRow).arg().name(), id);
        ops.set("#Rows[" + fieldRow + "] #Input.Value", id);
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
            for (String p : host.onlinePlayers()) {
                if (!out.contains(p)) out.add(p);
            }
            return out;
        }
        return null;
    }

    private static String hint(Arg a) {
        StringBuilder b = new StringBuilder();
        switch (a.type()) {
            case GREEDY_STRING:
                b.append("text, the rest of the line");
                break;
            case POSITION:
                b.append("x y z (or {x} {y} {z})");
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

    private void fieldBind(UiOps ops, int r) {
        String sel = "#Rows[" + r + "]";
        ops.bind(sel + " #Input", "ValueChanged", Map.of("Action", "field", "Row", Integer.toString(r), "Gen", g(), "@Value", sel + " #Input.Value"));
    }

    /** Adds the current value of every field to a click's data, as {@code @V<row>}. */
    private Map<String, String> withCaptures(Map<String, String> base) {
        Map<String, String> m = new LinkedHashMap<>(base);
        for (int i = 0; i < rows.size(); i++) {
            RowKind k = rows.get(i).kind();
            if (k == RowKind.FIELD || k == RowKind.RAW) m.put("@V" + i, "#Rows[" + i + "] #Input.Value");
        }
        return m;
    }

    private void captureForm(Map<String, Object> data) {
        for (int i = 0; i < rows.size(); i++) {
            if (data.containsKey("@V" + i)) storeField(i, data.get("@V" + i));
        }
    }

    private void storeField(int r, Object value) {
        if (r < 0 || r >= rows.size()) return;
        Row row = rows.get(r);
        String v = value instanceof Boolean ? value.toString() : str(value);
        if (row.kind() == RowKind.RAW) {
            rawText = v;
        } else if (row.kind() == RowKind.FIELD) {
            values.put(row.arg().name(), v);
        }
    }

    private void recent(UiOps ops, String text) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind() == RowKind.RAW) {
                rawText = text;
                ops.set("#Rows[" + i + "] #Input.Value", text);
            }
        }
        renderPreview(ops);
    }

    /** The line the form would send now, or why it can't be sent. */
    HytaleLines.Built buildLine() {
        CommandInfo c = command();
        if (c == null) return new HytaleLines.Built(null, new HytaleLines.Unsendable("empty", List.of(), null));
        if (c.level() == Level.RAW) {
            String prefix = "/" + c.name() + " ";
            String t = rawText.strip();
            String line = t.isEmpty() ? "/" + c.name() : prefix + t;
            Problem p = HytaleLines.checkLine(line);
            if (p == null) return new HytaleLines.Built(line, null);
            List<Long> at = new ArrayList<>();
            for (Object o : (List<?>) p.detail().get("positions")) at.add(((Long) o) - prefix.length());
            return new HytaleLines.Built(null, new HytaleLines.Unsendable((String) p.detail().get("reason"), at, "Arguments"));
        }
        List<HytaleLines.Field> fields = new ArrayList<>();
        int form = variant >= 0 && variant < c.variants().size() ? variant : -1;
        for (Arg a : formArgs(c)) {
            String v = values.getOrDefault(a.name(), "");
            // The game says how many words it reads (a position or a rotation takes three) and which are lists.
            int tokens = host.tokens(c.name(), form, a.name());
            if (tokens <= 0) tokens = a.type() == ArgType.POSITION ? 3 : 1;
            boolean list = host.isList(c.name(), form, a.name());
            if (tokens > 1 && !list && !v.isBlank() && v.strip().split("[ \t]+").length != tokens) {
                return new HytaleLines.Built(null, new HytaleLines.Unsendable(tokens == 3 ? "position" : "count", List.of(), a.name()));
            }
            fields.add(new HytaleLines.Field(a.name(), a.type(), a.style(), a.required(), v, list ? 1 : tokens, list));
        }
        return HytaleLines.build(c.name(), fields);
    }

    void renderPreview(UiOps ops) {
        if (tab != Tab.COMMANDS || command() == null) return;
        HytaleLines.Built b = buildLine();
        if (b.sendable()) {
            ops.text("#Preview", b.line());
            ops.text("#PreviewNote", "Runs as you. This is exactly the line that is sent.");
            return;
        }
        HytaleLines.Unsendable u = b.unsendable();
        ops.text("#Preview", "");
        ops.text("#PreviewNote", problemText(u));
    }

    private String problemText(HytaleLines.Unsendable u) {
        if ("position".equals(u.reason())) return "Can't send yet: " + u.field() + " needs three values, x y z";
        String msg = HytaleLines.message(u.reason());
        if (u.field() == null) return "Can't send yet: " + msg;
        String value = "Arguments".equals(u.field()) ? rawText.strip() : values.getOrDefault(u.field(), "");
        String marked = u.positions().isEmpty() ? "" : ": " + mark(value, u.positions());
        return "Can't send yet: " + msg + " (" + u.field() + marked + ")";
    }

    /** The value with each offending character between guillemets: {@code a«,»b}. */
    static String mark(String value, List<Long> positions) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            boolean bad = positions.contains((long) i);
            if (bad) b.append('«');
            b.append(value.charAt(i));
            if (bad) b.append('»');
        }
        return b.toString();
    }

    private void runForm(UiOps ops) {
        CommandInfo c = command();
        if (c == null) return;
        HytaleLines.Built b = buildLine();
        if (!b.sendable()) {
            notSent(ops);
            return;
        }
        if (c.level() == Level.RAW) host.prefs().remember(host.presser().id(), c.name(), rawText.strip());
        run(ops, new RunRequest(List.of(RunItem.line(b.line())), SOURCE), "Ran " + b.line());
        if (c.level() == Level.RAW) renderList(ops);
    }

    /** Checks a request; shows the deck's prompt if it asks first, otherwise starts it. */
    private void run(UiOps ops, RunRequest request, String what) {
        Presser p = host.presser();
        CheckResult check = host.deck().check(p, request);
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

    private void start(UiOps ops, RunRequest request, String token) {
        StartResult s = host.deck().start(host.presser(), request, host.reporter(), token);
        if (!s.started()) {
            setStatus(ops, "Can't run that: " + s.refusal().message());
        } else {
            setStatus(ops, "Started run " + s.runId() + ". The result appears in chat; see the Runs tab while it's going.");
        }
    }

    private void confirm(UiOps ops) {
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
        Problem problem = host.macros().delete(p.deleteScope(), host.presser().id(), p.deleteId());
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

    private void showConfirm(UiOps ops, String text, String risk) {
        ops.text("#ConfirmText", text);
        ops.text("#ConfirmRisk", risk);
        ops.visible("#ConfirmRisk", !risk.isEmpty());
        ops.visible("#ConfirmBox", true);
    }

    private void hideConfirm(UiOps ops) {
        ops.visible("#ConfirmBox", false);
    }

    private void favorite(UiOps ops) {
        if (commandName == null) return;
        boolean now = host.prefs().toggleFavorite(host.presser().id(), commandName);
        // A click sent before a redraw can arrive on another tab, whose footer has no Favorite button.
        if (tab == Tab.COMMANDS) ops.text("#Footer[0] #Favorite", now ? "Unfavorite" : "Favorite");
        setStatus(ops, now ? "/" + commandName + " is a favorite now." : "/" + commandName + " is no longer a favorite.");
        renderList(ops);
    }

    private void addToMacro(UiOps ops) {
        HytaleLines.Built b = buildLine();
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
        for (Macro m : host.macros().list(Scope.PLAYER, host.presser().id())) {
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
            for (Macro x : host.macros().list(scope, host.presser().id())) {
                if (x.id().equals(id)) m = x;
            }
            if (m == null) return false;
            draft = MacroDraft.of(m);
            draftOriginalId = m.id();
            draftScope = scope;
            draftNeeds = holds(m.permission()) ? null : m.permission();
            draftReadOnly = draftNeeds != null || (scope == Scope.SERVER ? !host.isOperator() : !host.canEditOwn());
            dirty = false;
            macroKey = key;
        } else if (key.startsWith("preset:")) {
            CommandCatalog cat = host.catalog();
            Preset p = cat == null ? null : cat.presets().get(key.substring(7));
            if (p == null) return false;
            draft = MacroDraft.copyOf(p.id(), p.name(), p.macro(), p.permission());
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

    private void renderEditor(UiOps ops) {
        if (draft == null) {
            ops.text("#Heading", "Macros");
            ops.text("#Meta", "Pick a macro on the left, or + New macro.");
            note(ops, "A macro is a list of steps: commands, waits, repeats and groups. It runs one step per tick, as you.");
            ops.text("#Preview", "");
            ops.text("#PreviewNote", "");
            return;
        }
        boolean preset = macroKey != null && macroKey.startsWith("preset:");
        ops.text("#Heading", draft.name == null || draft.name.isBlank() ? draft.id : draft.name);
        ops.text("#Meta", preset ? "A preset from a mod: run it, or save a copy as your own to change it."
            : draftNeeds != null ? "Your own macro, but it needs the permission " + draftNeeds
                + ", which you don't have. You can't run or change it, but you can delete it."
            : draftReadOnly ? (draftScope == Scope.SERVER ? "A server macro: only operators can change it." : "You may run this macro, but not change it.")
            : (draftScope == Scope.SERVER ? "A server macro." : "Your own macro.") + (draftOriginalId == null ? " Not saved yet." : ""));

        headerRow(ops, 0, TEXT_ROW, "Id", draft.id, "lowercase letters, digits, _ . and -; changing it renames the macro", "id");
        headerRow(ops, 1, TEXT_ROW, "Name", draft.name, "shown in lists", "name");
        headerRow(ops, 2, CHECK_ROW, "Ask before running", null, "shows a confirmation every time it runs", "danger");
        ops.set("#Rows[2] #Input.Value", draft.dangerous);
        headerRow(ops, 3, CHOICE_ROW, "Saved in", null, "", "where");
        List<UiOps.Entry> where = new ArrayList<>();
        where.add(new UiOps.Entry("My macros", "PLAYER"));
        if (host.isOperator()) where.add(new UiOps.Entry("Server macros", "SERVER"));
        ops.entries("#Rows[3] #Input", where);
        ops.set("#Rows[3] #Input.Value", draftScope.name());
        hint(ops, "#Rows[3]", host.isOperator() ? "server macros can be run by everyone" : "");
        headerRow(ops, 4, TEXT_ROW, "Description", draft.description == null ? "" : draft.description, "optional, shown with the macro",
            "description");
        headerRow(ops, 5, TEXT_ROW, "Category", draft.category == null ? "" : draft.category,
            "optional, up to 40 characters; the Macros list groups by it", "category");
        rows.add(new Row(RowKind.FIELD, null));
        ops.append("#Rows", CHECK_ROW);
        ops.text("#Rows[6] #Label", "Show step notes");
        ops.set("#Rows[6] #Input.Value", showNotes);
        hint(ops, "#Rows[6]", "a note under each step, saved with the macro");
        ops.bind("#Rows[6] #Input", "ValueChanged", editorCaptures(Map.of("Action", "notes", "Gen", g(), "@Value", "#Rows[6] #Input.Value")));

        List<MacroDraft.Step> steps = draft.steps();
        for (int j = 0; j < steps.size(); j++) {
            stepRow(ops, stepRowIndex(j), j, steps.get(j));
            if (showNotes) noteRow(ops, stepRowIndex(j) + 1, j, steps.get(j));
        }
        if (steps.isEmpty()) note(ops, "No steps yet. Add one with the buttons below.");

        ops.append("#Footer", EDITOR_FOOTER);
        String f = "#Footer[0] ";
        for (MacroDraft.Kind k : MacroDraft.Kind.values()) {
            ops.bind(f + "#Add" + cap(k), "Activating", editorCaptures(Map.of("Action", "stepAdd", "Kind", k.name(), "Gen", g())));
            ops.set(f + "#Add" + cap(k) + ".Disabled", draftReadOnly);
        }
        ops.bind(f + "#Save", "Activating", editorCaptures(Map.of("Action", "save", "Gen", g())));
        ops.bind(f + "#RunMacro", "Activating", editorCaptures(Map.of("Action", "runMacro", "Gen", g())));
        ops.bind(f + "#Delete", "Activating", Map.of("Action", "deleteMacro"));
        boolean canCopy = host.canEditOwn();
        ops.text(f + "#Save", draftReadOnly && draftNeeds == null ? "Save as mine" : "Save");
        ops.set(f + "#Save.Disabled", draftNeeds != null || draftReadOnly && !canCopy);
        ops.set(f + "#RunMacro.Disabled", draftNeeds != null);
        ops.set(f + "#Delete.Disabled", !mayDelete());
        renderValidation(ops);
    }

    private void headerRow(UiOps ops, int r, String doc, String label, String value, String hint, String field) {
        rows.add(new Row(RowKind.FIELD, null));
        ops.append("#Rows", doc);
        String sel = "#Rows[" + r + "]";
        ops.text(sel + " #Label", label);
        if (value != null) ops.set(sel + " #Input.Value", value);
        hint(ops, sel, hint);
        ops.bind(sel + " #Input", "ValueChanged", Map.of("Action", "header", "Field", field, "Gen", g(), "@Value", sel + " #Input.Value"));
    }

    /** The row of step {@code j}; with notes shown, each step's note row follows it. */
    private int stepRowIndex(int j) {
        return HEADER_ROWS + (showNotes ? 2 * j : j);
    }

    private void noteRow(UiOps ops, int r, int j, MacroDraft.Step s) {
        rows.add(new Row(RowKind.FIELD, null));
        ops.append("#Rows", TEXT_ROW);
        String sel = "#Rows[" + r + "]";
        ops.text(sel + " #Label", "   Note for step " + (j + 1));
        ops.set(sel + " #Input.Value", s.note == null ? "" : s.note);
        hint(ops, sel, s.kind == MacroDraft.Kind.WAIT ? "optional" : "optional, up to 200 characters");
        ops.bind(sel + " #Input", "ValueChanged", Map.of("Action", "stepEdit", "Row", Integer.toString(j), "Field", "note", "Gen", g(),
            "@Value", sel + " #Input.Value"));
    }

    private void stepRow(UiOps ops, int r, int j, MacroDraft.Step s) {
        rows.add(new Row(RowKind.FIELD, null));
        ops.append("#Rows", STEP_ROW);
        String sel = "#Rows[" + r + "]";
        ops.text(sel + " #Depth", ">".repeat(s.depth()));
        String kind;
        switch (s.kind) {
            case COMMAND:
                kind = "Command";
                break;
            case WAIT:
                kind = "Wait";
                break;
            case REPEAT:
                kind = "Repeat, times";
                break;
            default:
                kind = "Group";
        }
        ops.text(sel + " #Kind", kind);
        ops.visible(sel + " #Input", s.kind != MacroDraft.Kind.SEQUENCE);
        ops.set(sel + " #Input.Value", s.text == null ? "" : s.text);
        boolean flag = s.kind == MacroDraft.Kind.COMMAND || s.kind == MacroDraft.Kind.WAIT;
        ops.visible(sel + " #Flag", flag);
        ops.visible(sel + " #FlagLabel", flag);
        ops.text(sel + " #FlagLabel", s.kind == MacroDraft.Kind.WAIT ? "seconds" : "ask first");
        ops.set(sel + " #Flag.Value", s.flag);
        ops.bind(sel + " #Input", "ValueChanged", Map.of("Action", "stepEdit", "Row", Integer.toString(j), "Field", "text", "Gen", g(),
            "@Value", sel + " #Input.Value"));
        ops.bind(sel + " #Flag", "ValueChanged", Map.of("Action", "stepEdit", "Row", Integer.toString(j), "Field", "flag", "Gen", g(),
            "@Value", sel + " #Flag.Value"));
        String[][] buttons = {{"#Up", "up"}, {"#Down", "down"}, {"#Out", "out"}, {"#In", "in"}, {"#Del", "del"}};
        boolean[] enabled = {draft.canMoveUp(j), draft.canMoveDown(j), draft.canOutdent(j), draft.canIndent(j), true};
        for (int b = 0; b < buttons.length; b++) {
            ops.bind(sel + " " + buttons[b][0], "Activating", editorCaptures(Map.of("Action", "stepOp", "Row", Integer.toString(j), "Op",
                buttons[b][1], "Gen", g())));
            ops.set(sel + " " + buttons[b][0] + ".Disabled", draftReadOnly || !enabled[b]);
        }
    }

    /** Adds every editor field's current value to a click's data. */
    private Map<String, String> editorCaptures(Map<String, String> base) {
        Map<String, String> m = new LinkedHashMap<>(base);
        for (int h = 0; h < HEADER_FIELDS.length; h++) m.put("@H" + h, "#Rows[" + h + "] #Input.Value");
        if (draft != null) {
            for (int j = 0; j < draft.steps().size(); j++) {
                String sel = "#Rows[" + stepRowIndex(j) + "]";
                m.put("@T" + j, sel + " #Input.Value");
                m.put("@F" + j, sel + " #Flag.Value");
                if (showNotes) m.put("@N" + j, "#Rows[" + (stepRowIndex(j) + 1) + "] #Input.Value");
            }
        }
        return m;
    }

    private void captureEditor(Map<String, Object> data) {
        if (draft == null) return;
        for (int h = 0; h < HEADER_FIELDS.length; h++) {
            if (data.containsKey("@H" + h)) header(HEADER_FIELDS[h], data.get("@H" + h));
        }
        for (int j = 0; j < draft.steps().size(); j++) {
            if (data.containsKey("@T" + j)) stepEdit(j, "text", data.get("@T" + j));
            if (data.containsKey("@F" + j)) stepEdit(j, "flag", data.get("@F" + j));
            if (data.containsKey("@N" + j)) stepEdit(j, "note", data.get("@N" + j));
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
                Scope s = "SERVER".equals(v) && host.isOperator() ? Scope.SERVER : Scope.PLAYER;
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
        } else {
            String t = str(value);
            if (!t.equals(s.text)) dirty = true;
            s.text = t;
        }
    }

    private void stepOp(UiOps ops, int j, String op) {
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

    private void stepAdd(UiOps ops, String kind) {
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

    /** The draft as it would be saved, or the first reason it can't be. */
    private Problem check() {
        MacroDraft.Built b = draft.build();
        if (!b.ok()) return Problem.of("invalid", b.problem());
        Macro m = draft.toMacro();
        MacroReader.Result<Macro> back = MacroReader.readDocument(Json.parse(Json.write(MacroWriter.document(m))));
        if (!back.ok()) return back.problem();
        return MacroValidator.check(back.value().body(), host.limits());
    }

    void renderValidation(UiOps ops) {
        if (tab != Tab.MACROS || draft == null) return;
        Problem p = check();
        int n = draft.steps().size();
        ops.text("#Preview", "Macro " + draft.id + ": " + n + (n == 1 ? " step" : " steps") + (dirty ? " (unsaved changes)" : ""));
        ops.text("#PreviewNote", p == null ? "Ready to save." : "Can't save yet: " + readable(p));
    }

    private static String readable(Problem p) {
        String m = p.message();
        Object ptr = p.detail().get("pointer");
        if ("/id".equals(ptr)) return "the id uses lowercase letters, digits, _ . and - only, and starts with a letter or digit";
        if ("/name".equals(ptr)) return "the name must not be empty, and at most 60 characters";
        return m;
    }

    private void save(UiOps ops) {
        if (draft == null) return;
        if (draftNeeds != null) {
            setStatus(ops, "Not saved: this macro needs the permission " + draftNeeds + ", which you don't have.");
            return;
        }
        Scope scope = draftReadOnly ? Scope.PLAYER : draftScope;
        if (scope == Scope.SERVER ? !host.isOperator() : !host.canEditOwn()) {
            setStatus(ops, scope == Scope.SERVER ? "Only operators can save server macros." : "You may not save macros on this server.");
            return;
        }
        if (draft.name == null || draft.name.isBlank()) draft.name = draft.id;
        Problem p = check();
        if (p != null) {
            setStatus(ops, "Not saved: " + readable(p));
            return;
        }
        Macro m = draft.toMacro();
        // A copy of a preset or of someone else's macro is a new macro; so is a macro moved to the other place.
        String replacing = draftReadOnly || macroKey == null || macroKey.equals("new") || scopeOf(macroKey) != scope ? null : draftOriginalId;
        p = host.macros().save(scope, host.presser().id(), m, replacing);
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
        status = "Saved " + m.name() + " (" + m.id() + ").";
        renderList(ops);
        renderRight(ops);
    }

    private static Scope scopeOf(String key) {
        return key.startsWith("server:") ? Scope.SERVER : Scope.PLAYER;
    }

    private void runMacro(UiOps ops) {
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
        // A scoped reference runs exactly the macro that is open, whatever else shares its id.
        String ref = preset ? macroKey : (draftScope == Scope.SERVER ? "server:" : "player:") + draftOriginalId;
        run(ops, new RunRequest(List.of(RunItem.ref(ref)), SOURCE), "Ran " + (preset ? draft.id : draftOriginalId));
    }

    /**
     * Your own macros can be deleted with the edit permission, even one that needs a permission you lack;
     * server macros by operators.
     */
    private boolean mayDelete() {
        if (draft == null || draftOriginalId == null) return false;
        if (draftNeeds != null) return draftScope == Scope.PLAYER && host.canEditOwn();
        return !draftReadOnly;
    }

    private void askDelete(UiOps ops) {
        if (!mayDelete()) return;
        pending = Pending.delete(draftScope, draftOriginalId);
        showConfirm(ops, "Delete the macro " + draft.name + " (" + draftOriginalId + ")? This can't be undone.", "");
        setStatus(ops, "Press Yes to delete, or No to keep it.");
    }

    // ---- runs -----------------------------------------------------------------------------------------

    private List<RunStatus> runs() {
        return host.deck().running(host.isOperator() ? null : host.presser());
    }

    private void renderRuns(UiOps ops) {
        ops.text("#Heading", "Runs");
        ops.text("#Meta", host.isOperator() ? "Every run going on the server. Stop takes effect before the next step." : "Your runs. Stop takes effect before the next step.");
        List<RunStatus> list = runs();
        for (int i = 0; i < list.size(); i++) {
            RunStatus s = list.get(i);
            int r = rows.size();
            rows.add(new Row(RowKind.NOTE, null));
            ops.append("#Rows", RUN_ROW);
            String sel = "#Rows[" + r + "]";
            ops.text(sel + " #Text", RunText.line(s));
            ops.bind(sel + " #Stop", "Activating", Map.of("Action", "stopRun", "Run", s.runId()));
        }
        if (list.isEmpty()) note(ops, "No runs going.");
        ops.append("#Footer", RUNS_FOOTER);
        ops.bind("#Footer[0] #Refresh", "Activating", Map.of("Action", "refresh"));
        ops.bind("#Footer[0] #StopAll", "Activating", Map.of("Action", "stopAll"));
        ops.visible("#Footer[0] #StopAll", host.isOperator());
        ops.text("#Preview", "");
        ops.text("#PreviewNote", "");
    }

    private void stopRun(UiOps ops, String runId) {
        boolean ok = host.deck().stop(runId, host.presser());
        status = ok ? "Asked run " + runId + " to stop." : "Run " + runId + " isn't going, or isn't yours to stop.";
        renderList(ops);
        renderRight(ops);
    }

    private void stopAll(UiOps ops) {
        int n = host.deck().stopAll(host.presser());
        status = n < 0 ? "Only operators can stop everyone's runs." : "Asked " + n + (n == 1 ? " run" : " runs") + " to stop.";
        renderList(ops);
        renderRight(ops);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private String g() {
        return String.valueOf(gen);
    }

    private static String cap(Enum<?> e) {
        String n = e.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
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
