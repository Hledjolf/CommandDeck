package commanddeck.jvm;

import commanddeck.json.Json;
import commanddeck.macro.ConditionPort;
import hledjolf.conditions.Jv;
import hledjolf.conditions.Library;
import hledjolf.conditions.api.Context;
import hledjolf.conditions.api.Evaluation;
import hledjolf.conditions.api.Problem;
import hledjolf.conditions.api.RegistryEntry;
import hledjolf.conditions.api.TypeInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The shared condition library behind the core's {@link ConditionPort}, and the server owner's registry files. A
 * condition goes to the library as JSON text, so the two never share JSON classes.
 */
public final class LibraryConditions implements ConditionPort {
    private final Library library;

    public LibraryConditions(Library library) {
        this.library = library;
    }

    public Library library() {
        return library;
    }

    @Override
    public List<Issue> check(Map<String, Object> condition) {
        List<Issue> out = new ArrayList<>();
        for (Problem p : library.validate(Json.write(condition))) out.add(new Issue(p.pointer(), p.code().wire(), p.message()));
        return out;
    }

    @Override
    public Result evaluate(Map<String, Object> condition, String actor, String target, long tick) {
        Evaluation e = library.evaluate(Json.write(condition), new Context(actor, target, null, null, tick));
        switch (e.answer()) {
            case TRUE: return new Result(Answer.TRUE, null);
            case FALSE: return new Result(Answer.FALSE, null);
            default: return new Result(Answer.UNKNOWN, e.reason());
        }
    }

    @Override
    public String game() {
        return library.facts() == null ? null : library.facts().game();
    }

    /** Plain words for the shared types. */
    private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("item_count", "Item count"), Map.entry("stat", "Stat"),
        Map.entry("health", "Health"), Map.entry("time_of_day", "Time of day"), Map.entry("distance", "Distance"),
        Map.entry("in_area", "In an area"), Map.entry("player_online", "Player online"), Map.entry("is_night", "Night"),
        Map.entry("weather", "Weather"), Map.entry("zone", "Zone"), Map.entry("in_fluid", "In a fluid"), Map.entry("near_fluid", "Near a fluid"),
        Map.entry("near_entity", "Creatures nearby"), Map.entry("wielding", "Holding"), Map.entry("wearing", "Wearing"));

    /** A shared field's title and hint, by name; a field not listed is titled by its name. */
    private static final Map<String, String[]> FIELDS = Map.ofEntries(
        Map.entry("subject", new String[] {"Who", "a name or a placeholder; blank is {self}, you"}),
        Map.entry("item", new String[] {"Item", "an item id such as Rock_Stone, or pick Tag for an item tag"}),
        Map.entry("tag", new String[] {"Creature", "an entity tag such as core:hostile, or pick Id for a creature id"}),
        Map.entry("op", new String[] {"Compare", ""}),
        Map.entry("value", new String[] {"Value", ""}),
        Map.entry("stat", new String[] {"Stat", "a stat id such as Mana"}),
        Map.entry("unit", new String[] {"Unit", ""}),
        Map.entry("from", new String[] {"From hour", "0 to 24"}),
        Map.entry("to", new String[] {"To", ""}),
        Map.entry("min", new String[] {"From corner", "the box includes both corners"}),
        Map.entry("max", new String[] {"To corner", ""}),
        Map.entry("world", new String[] {"World", "optional"}),
        Map.entry("category", new String[] {"Weather", "categories, comma separated, such as core:rain, core:storm"}),
        Map.entry("in", new String[] {"Zones", "zone ids, comma separated"}),
        Map.entry("fluid", new String[] {"Fluids", "comma separated, such as core:water"}),
        Map.entry("radius", new String[] {"Radius", ""}),
        Map.entry("owner", new String[] {"Owned by", "optional: only what this player owns, such as {self}"}));

    /**
     * The shared types this game answers, in the schema's order, then the mods' types by owner, each with its
     * form. A shared type's form comes from the shared schema with the words above; a mod's type's form comes
     * from the schema it registered.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Form> forms() {
        List<Form> out = new ArrayList<>();
        Map<String, Object> defs = (Map<String, Object>) Library.sharedTypesSchema().get("$defs");
        for (String type : library.answeredSharedTypes()) {
            if (type.equals("counter") || Library.isLogic(type) || !(defs.get(type) instanceof Map)) continue;
            out.add(new Form(type, LABELS.getOrDefault(type, type), null, fields(type, (Map<String, Object>) defs.get(type), true)));
        }
        List<Form> mods = new ArrayList<>();
        for (TypeInfo t : library.types()) {
            Map<String, Object> schema = library.schemaOf(t.type());
            if (schema == null) continue;
            String label = t.description() == null || t.description().isBlank() ? t.type() : t.description();
            mods.add(new Form(t.type(), label, t.owner(), fields(t.type(), schema, false)));
        }
        mods.sort((a, b) -> a.owner().equals(b.owner()) ? a.label().compareToIgnoreCase(b.label()) : a.owner().compareToIgnoreCase(b.owner()));
        out.addAll(mods);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Field> fields(String type, Map<String, Object> schema, boolean shared) {
        Map<String, Object> props = schema.get("properties") instanceof Map ? (Map<String, Object>) schema.get("properties") : Map.of();
        List<Object> required = schema.get("required") instanceof List ? (List<Object>) schema.get("required") : List.of();
        // Two fields of which exactly one is required ("item" or "tag") are one field with a selector.
        List<String> either = new ArrayList<>();
        if (schema.get("oneOf") instanceof List) {
            for (Object alt : (List<Object>) schema.get("oneOf")) {
                if (alt instanceof Map && ((Map<String, Object>) alt).get("required") instanceof List) {
                    List<Object> r = (List<Object>) ((Map<String, Object>) alt).get("required");
                    if (r.size() == 1 && props.containsKey(String.valueOf(r.get(0)))) either.add(String.valueOf(r.get(0)));
                }
            }
            if (either.size() != 2) either.clear();
        }
        List<Field> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String name = e.getKey();
            if (name.equals("type") || either.size() == 2 && name.equals(either.get(1))) continue;
            Map<String, Object> p = e.getValue() instanceof Map ? (Map<String, Object>) e.getValue() : Map.of();
            boolean req = required.contains(name) || either.contains(name);
            String title;
            String hint;
            FieldKind kind;
            if (either.contains(name)) {
                String[] words = FIELDS.getOrDefault(name, new String[] {name, ""});
                title = shared ? words[0] : String.valueOf(p.getOrDefault("title", name));
                hint = shared ? words[1] : String.valueOf(p.getOrDefault("description", ""));
                kind = FieldKind.EITHER;
            } else if (shared) {
                String[] words = FIELDS.getOrDefault(name, new String[] {name, ""});
                title = words[0];
                hint = words[1];
                if (name.equals("value")) {
                    title = type.equals("item_count") ? "Amount" : type.equals("near_entity") ? "How many" : type.equals("distance") ? "Distance" : "Value";
                } else if (name.equals("to") && type.equals("time_of_day")) {
                    title = "To hour";
                    hint = "0 to 24; a range like 20 to 6 wraps past midnight";
                } else if (name.equals("to")) {
                    hint = "a position, a placeholder such as {target}, or a marker's name";
                }
                kind = sharedKind(type, name, p);
            } else {
                title = p.get("title") instanceof String ? (String) p.get("title") : name;
                hint = p.get("description") instanceof String ? (String) p.get("description") : "";
                kind = registeredKind(name, p);
            }
            out.add(new Field(name, title, hint, kind, req, kind == FieldKind.EITHER ? either : options(p)));
        }
        return out;
    }

    private static FieldKind sharedKind(String type, String name, Map<String, Object> p) {
        switch (name) {
            case "subject": return FieldKind.SUBJECT;
            case "op": return FieldKind.OP;
            case "unit": return FieldKind.UNIT;
            case "stat": return FieldKind.GAME_ID;
            case "min":
            case "max": return FieldKind.POSITION;
            case "in":
            case "fluid": return FieldKind.GAME_ID_LIST;
            case "category": return FieldKind.LIST;
            case "to": return type.equals("distance") ? FieldKind.PLACE : registeredKind(name, p);
            default: return registeredKind(name, p);
        }
    }

    /** How a field of a mod's type is shown, from its schema: anything without a plain control is JSON text. */
    @SuppressWarnings("unchecked")
    private static FieldKind registeredKind(String name, Map<String, Object> p) {
        if (p.get("enum") instanceof List) return options(p).size() == ((List<Object>) p.get("enum")).size() ? FieldKind.ENUM : FieldKind.JSON;
        Object t = p.get("type");
        if ("boolean".equals(t)) return FieldKind.BOOLEAN;
        if ("string".equals(t)) return name.equals("subject") ? FieldKind.SUBJECT : FieldKind.TEXT;
        if ("number".equals(t)) return FieldKind.NUMBER;
        if ("integer".equals(t)) return FieldKind.WHOLE;
        if ("array".equals(t) && p.get("items") instanceof Map && "string".equals(((Map<String, Object>) p.get("items")).get("type"))) {
            return FieldKind.LIST;
        }
        return FieldKind.JSON;
    }

    /** A field's enum values that are strings. */
    @SuppressWarnings("unchecked")
    private static List<String> options(Map<String, Object> p) {
        List<String> out = new ArrayList<>();
        if (p.get("enum") instanceof List) {
            for (Object o : (List<Object>) p.get("enum")) if (o instanceof String) out.add((String) o);
        }
        return out;
    }

    /**
     * Reads every {@code .json} file in the server owner's {@code registries} folder and puts their entries in place
     * of the ones read before (at start, and again on {@code /deck reload}). A file that breaks the rules is skipped
     * as a whole and logged with where; it never stops the others. Returns how many entries were read.
     */
    public int readRegistryFiles(Path dir, Consumer<String> log) {
        Map<String, List<RegistryEntry>> byRegistry = new TreeMap<>();
        int entries = 0;
        if (Files.isDirectory(dir)) {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.json")) {
                s.forEach(files::add);
            } catch (IOException e) {
                log.accept("Couldn't list " + dir + ": " + e.getMessage());
            }
            files.sort(null);
            for (Path f : files) {
                String text;
                try {
                    text = Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.accept("Skipped registry file " + f.getFileName() + ": " + e.getMessage());
                    continue;
                }
                RegistryFile r = RegistryFile.read(text);
                if (r.problem() != null) {
                    log.accept("Skipped registry file " + f.getFileName() + ": " + r.problem());
                    continue;
                }
                byRegistry.computeIfAbsent(r.registry(), k -> new ArrayList<>()).addAll(r.entries());
                entries += r.entries().size();
            }
        }
        library.replaceServerFileEntries(byRegistry);
        return entries;
    }

    /** One registry file, read: its registry and entries, or what's wrong with it (naming the JSON Pointer). */
    public record RegistryFile(String registry, List<RegistryEntry> entries, String problem) {
        static final String FORMAT = "hledjolf.conditions.registry";

        static RegistryFile bad(String pointer, String why) {
            return new RegistryFile(null, List.of(), why + " (at " + (pointer.isEmpty() ? "the top" : pointer) + ")");
        }

        @SuppressWarnings("unchecked")
        public static RegistryFile read(String text) {
            Object doc;
            try {
                doc = Jv.parse(text);
            } catch (Jv.ParseException e) {
                return bad("", "not JSON: " + e.getMessage());
            }
            if (!(doc instanceof Map)) return bad("", "a registry file is a JSON object");
            Map<String, Object> m = (Map<String, Object>) doc;
            for (String k : m.keySet()) {
                if (!List.of("format", "format_version", "registry", "entries").contains(k)) return bad("/" + k, "unknown field \"" + k + "\"");
            }
            if (!FORMAT.equals(m.get("format"))) return bad("/format", "format must be \"" + FORMAT + "\"");
            if (!"1.0".equals(m.get("format_version"))) return bad("/format_version", "format_version must be \"1.0\"");
            if (!(m.get("registry") instanceof String registry) || !registry.matches("[a-z][a-z0-9_]*")) {
                return bad("/registry", "registry must be a lower-case name, e.g. weather");
            }
            if (!(m.get("entries") instanceof List)) return bad("/entries", "entries must be a list");
            List<Object> raw = (List<Object>) m.get("entries");
            List<RegistryEntry> out = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                String at = "/entries/" + i;
                if (!(raw.get(i) instanceof Map)) return bad(at, "an entry is an object");
                Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) raw.get(i));
                if (e.containsKey("same_as")) return bad(at + "/same_as", "same_as is reserved for a later version");
                for (String k : e.keySet()) if (!k.equals("id") && !k.equals("in")) return bad(at + "/" + k, "unknown field \"" + k + "\"");
                if (!(e.get("id") instanceof String id) || !id.matches("[a-z][a-z0-9_]*:.+")) return bad(at + "/id", "id must be namespaced, e.g. hytale:Zone1_Rain");
                if (!(e.get("in") instanceof List)) return bad(at + "/in", "in must be a list of categories");
                List<String> in = new ArrayList<>();
                List<Object> cats = (List<Object>) e.get("in");
                for (int j = 0; j < cats.size(); j++) {
                    if (!(cats.get(j) instanceof String c) || !c.matches("[a-z][a-z0-9_]*:.+")) return bad(at + "/in/" + j, "a category must be namespaced, e.g. core:rain");
                    in.add(c);
                }
                out.add(new RegistryEntry(id, in));
            }
            return new RegistryFile(registry, out, null);
        }
    }
}
