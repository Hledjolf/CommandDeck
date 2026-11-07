package hledjolf.conditions;

import hledjolf.conditions.api.Answer;
import hledjolf.conditions.api.Conditions;
import hledjolf.conditions.api.Context;
import hledjolf.conditions.api.Evaluation;
import hledjolf.conditions.api.Evaluator;
import hledjolf.conditions.api.Owner;
import hledjolf.conditions.api.OwnerSource;
import hledjolf.conditions.api.Problem;
import hledjolf.conditions.api.Registered;
import hledjolf.conditions.api.RegistryEntry;
import hledjolf.conditions.api.TypeInfo;
import hledjolf.conditions.api.TypeRegistration;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The shared condition library. One per game process: the hosting mod builds it with its game's {@link GameFacts}
 * and installs it ({@link hledjolf.conditions.api.Installed}). Thread-safe for registering; evaluation runs on the
 * game's thread.
 */
public final class Library implements Conditions {
    public static final String API_VERSION = "1.0";

    /** Game names, whose namespaces are the library's own and whose ids a document for another game can't use. */
    public static final Set<String> GAMES = Set.of("hytale", "necesse", "stardew", "valheim", "corekeeper", "bannerlord", "skyrim",
        "fallout4", "oblivion", "factorio", "palworld", "minecraft");

    static final String NAME = "[a-z][a-z0-9_]*";
    static final String ID = NAME + ":.+";
    private static final String DOTTED = NAME + "\\." + NAME;
    private static final BigDecimal MAX_FRACTION = new BigDecimal("100000000000");
    private static final BigDecimal MAX_WHOLE = new BigDecimal("9007199254740992");

    /** Where an entry came from when the server owner's files supplied it, so a reload replaces only those. */
    public static final String SERVER_FILES = " server files";

    private final GameFacts facts;
    private final Map<String, Object> shared;
    private final Consumer<String> warn;
    private final LongSupplier nanos;
    private final IntSupplier budgetMs;

    private final Map<String, Registration> types = new ConcurrentHashMap<>();
    private final Map<String, String> namespaces = new ConcurrentHashMap<>();
    private final Map<String, OwnerSource> ownerSources = new LinkedHashMap<>();
    private final Map<String, Integer> lateStreak = new ConcurrentHashMap<>();
    private final Registries registries = new Registries();
    private final SharedTypes sharedTypes;

    private record Registration(String type, String owner, Map<String, Object> schema, Evaluator evaluator, String description) {}

    /**
     * @param facts the game, or null for a library that answers no shared type needing one
     * @param warn where problems with mods' evaluators and sources are logged
     * @param nanos the clock evaluators are timed with ({@code System::nanoTime})
     * @param budgetMs the operator's {@code condition_budget_ms}, read on every call so a settings change applies
     */
    public Library(GameFacts facts, Consumer<String> warn, LongSupplier nanos, IntSupplier budgetMs) {
        this.facts = facts;
        this.shared = sharedSchema();
        this.warn = warn;
        this.nanos = nanos;
        this.budgetMs = budgetMs;
        this.sharedTypes = new SharedTypes(this, facts);
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    // ---- registering ------------------------------------------------------------------------------------------

    @Override
    public Registered registerType(TypeRegistration r) {
        return register(r, false);
    }

    /** For the library's game adapter: types in the game's own namespace ({@code hytale.moon_phase}). */
    public Registered registerOwnType(TypeRegistration r) {
        return register(r, true);
    }

    private synchronized Registered register(TypeRegistration r, boolean own) {
        if (r == null || r.type() == null || r.owner() == null || r.owner().isBlank()) return Registered.refused("invalid: type and owner are required");
        if (!r.type().matches(DOTTED)) return Registered.refused("invalid: a type is <namespace>.<name>, lower case, e.g. mymod.has_thing");
        if (r.evaluator() == null) return Registered.refused("invalid: no evaluator");
        String ns = r.type().substring(0, r.type().indexOf('.'));
        if (!own && (ns.equals("core") || GAMES.contains(ns))) return Registered.refused("reserved: the namespace " + ns + " is the library's own");
        String holder = namespaces.get(ns);
        if (holder != null && !holder.equals(r.owner())) return Registered.refused("namespace_taken: " + ns + " belongs to " + holder);
        Object schema;
        try {
            schema = Jv.parse(r.schema() == null ? "" : r.schema());
        } catch (Jv.ParseException e) {
            return Registered.refused("invalid_schema: not JSON (" + e.getMessage() + ")");
        }
        String why = Schema.unusable(schema, r.type());
        if (why != null) return Registered.refused("invalid_schema: " + why);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) schema;
        namespaces.put(ns, r.owner());
        types.put(r.type(), new Registration(r.type(), r.owner(), s, r.evaluator(), r.description()));
        return Registered.accepted();
    }

    @Override
    public Registered registerEntries(String registry, List<RegistryEntry> entries, String owner) {
        if (owner == null || owner.isBlank()) return Registered.refused("invalid: an owner is required");
        String why = entriesProblem(registry, entries);
        if (why != null) return Registered.refused("invalid: " + why);
        registries.add(registry, entries, owner);
        return Registered.accepted();
    }

    /** Replaces every entry that came from the server owner's files (at start and on reload). */
    public void replaceServerFileEntries(Map<String, List<RegistryEntry>> byRegistry) {
        registries.replaceSource(SERVER_FILES, byRegistry);
    }

    static String entriesProblem(String registry, List<RegistryEntry> entries) {
        if (registry == null || !registry.matches(NAME)) return "a registry name is lower case, e.g. weather";
        if (entries == null) return "no entries";
        for (RegistryEntry e : entries) {
            if (e == null || e.id() == null || !e.id().matches(ID)) return "an entry's id must be namespaced, e.g. hytale:Zone1_Rain (got " + (e == null ? "null" : e.id()) + ")";
            for (String c : e.memberOf()) if (c == null || !c.matches(ID)) return "a category must be namespaced, e.g. core:rain (got " + c + " in " + e.id() + ")";
        }
        return null;
    }

    @Override
    public synchronized Registered registerOwnerSource(String owner, OwnerSource source) {
        if (owner == null || owner.isBlank() || source == null) return Registered.refused("invalid: owner and source are required");
        ownerSources.put(owner, source);
        return Registered.accepted();
    }

    @Override
    public List<TypeInfo> types() {
        List<TypeInfo> out = new ArrayList<>();
        new TreeMap<>(types).values().forEach(r -> out.add(new TypeInfo(r.type(), r.owner(), r.description())));
        return out;
    }

    /** A registered type's schema as parsed (don't change it), or null when no mod registered the type. */
    public Map<String, Object> schemaOf(String type) {
        Registration r = type == null ? null : types.get(type);
        return r == null ? null : r.schema();
    }

    /** The shared types' schema, as parsed (don't change it): one entry under {@code $defs} for each type. */
    public static Map<String, Object> sharedTypesSchema() {
        return sharedSchema();
    }

    /** The registries, for the game adapter and the shared types. */
    public Registries registries() {
        return registries;
    }

    public GameFacts facts() {
        return facts;
    }

    /** The shared types this library answers here, in the schema's order. */
    public List<String> answeredSharedTypes() {
        List<String> out = new ArrayList<>();
        for (String t : sharedSchemaTypes()) if (answered(t)) out.add(t);
        return out;
    }

    // ---- validating -------------------------------------------------------------------------------------------

    @Override
    public List<Problem> validate(String conditionJson) {
        List<Problem> out = new ArrayList<>();
        Object v;
        try {
            v = Jv.parse(conditionJson);
        } catch (Jv.ParseException e) {
            out.add(new Problem("", Problem.Code.INVALID, "not JSON: " + e.getMessage()));
            return out;
        }
        node(v, "", out);
        return out;
    }

    /** Problems with a parsed condition. Pointers start with {@code at}. */
    public List<Problem> validateParsed(Object condition, String at) {
        List<Problem> out = new ArrayList<>();
        node(condition, at, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void node(Object v, String at, List<Problem> out) {
        if (!(v instanceof Map<?, ?> raw)) {
            out.add(new Problem(at, Problem.Code.INVALID, "a condition is a JSON object, not " + Schema.kind(v)));
            return;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        if (!(m.get("type") instanceof String type)) {
            out.add(new Problem(at + "/type", Problem.Code.INVALID, "a condition needs a \"type\""));
            return;
        }
        if (isLogic(type)) {
            Schema.Fail f = Schema.check(def(type), m, at, shared, (child, p) -> {
                node(child, p, out);
                return null;
            });
            if (f != null) out.add(invalid(f));
            return;
        }
        Problem p = leafProblem(type, m, at);
        if (p != null) out.add(p);
    }

    public static boolean isLogic(String type) {
        return type.equals("all") || type.equals("any") || type.equals("not");
    }

    /** Every shared type the schema defines, logic included, in its order. */
    @SuppressWarnings("unchecked")
    public static List<String> sharedSchemaTypes() {
        List<String> out = new ArrayList<>();
        for (String t : ((Map<String, Object>) sharedSchema().get("$defs")).keySet()) {
            if (!t.equals("condition") && !t.equals("namespaced")) out.add(t);
        }
        return out;
    }

    /** The first problem with one condition that isn't all/any/not, or null. */
    private Problem leafProblem(String type, Map<String, Object> m, String at) {
        if (type.indexOf('.') < 0) {
            Map<String, Object> d = def(type);
            if (d == null || type.equals("condition") || type.equals("namespaced")) {
                return new Problem(at + "/type", Problem.Code.UNKNOWN_TYPE, "there's no condition type \"" + type + "\"");
            }
            Schema.Fail f = Schema.check(d, m, at, shared, null);
            if (f == null) f = numberRules(m, at);
            if (f != null) return invalid(f);
            Problem g = gameIds(type, m, at);
            if (g != null) return g;
            if (!answered(type)) {
                return new Problem(at + "/type", Problem.Code.UNSUPPORTED_HERE, "\"" + type + "\" can't be answered on " + (facts == null ? "this game" : facts.game()));
            }
            return null;
        }
        Registration r = types.get(type);
        if (r == null) return new Problem(at + "/type", Problem.Code.UNKNOWN_TYPE, "no mod has registered the condition type \"" + type + "\"");
        Schema.Fail f = Schema.check(r.schema(), m, at, r.schema(), null);
        if (f == null) f = numberRules(m, at);
        return f == null ? null : invalid(f);
    }

    private static Problem invalid(Schema.Fail f) {
        return new Problem(f.pointer(), Problem.Code.INVALID, f.message());
    }

    private boolean answered(String type) {
        if (type.equals("counter") || isLogic(type)) return true;
        return facts != null && facts.answers().contains(type);
    }

    /** Fractions have at most 3 decimals within ±10^11; whole numbers lie within ±2^53. */
    private static Schema.Fail numberRules(Object v, String at) {
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Schema.Fail f = numberRules(e.getValue(), at + "/" + Schema.escape(String.valueOf(e.getKey())));
                if (f != null) return f;
            }
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                Schema.Fail f = numberRules(l.get(i), at + "/" + i);
                if (f != null) return f;
            }
        } else if (v instanceof BigDecimal d) {
            if (Schema.decimals(d) > 0) {
                if (Schema.decimals(d) > 3) return new Schema.Fail(at, "has more than 3 decimal places");
                if (d.abs().compareTo(MAX_FRACTION) > 0) return new Schema.Fail(at, "is outside ±10^11");
            } else if (d.abs().compareTo(MAX_WHOLE) > 0) return new Schema.Fail(at, "is outside ±2^53");
        } else if (v instanceof Long n && Math.abs(n) > 9007199254740992L) {
            return new Schema.Fail(at, "is outside ±2^53");
        }
        return null;
    }

    /** Fields holding a game's own id, by type; and fields holding registry entries. */
    private static final Map<String, List<String>> GAME_ID_FIELDS = Map.of("item_count", List.of("item"), "stat", List.of("stat"),
        "zone", List.of("in"), "wielding", List.of("item"), "wearing", List.of("item"), "near_entity", List.of("id"));
    private static final Map<String, List<String>> ENTRY_FIELDS = Map.of("item_count", List.of("tag"), "weather", List.of("category"),
        "in_fluid", List.of("fluid"), "near_fluid", List.of("fluid"), "near_entity", List.of("tag"), "wielding", List.of("tag"),
        "wearing", List.of("tag"));

    private Problem gameIds(String type, Map<String, Object> m, String at) {
        if (facts == null) return null;
        for (String f : GAME_ID_FIELDS.getOrDefault(type, List.of())) {
            for (Map.Entry<String, String> id : ids(m.get(f), at + "/" + f).entrySet()) {
                String game = id.getValue().substring(0, id.getValue().indexOf(':'));
                if (!game.equals(facts.game())) return wrongGame(id.getKey(), id.getValue());
            }
        }
        for (String f : ENTRY_FIELDS.getOrDefault(type, List.of())) {
            for (Map.Entry<String, String> id : ids(m.get(f), at + "/" + f).entrySet()) {
                String ns = id.getValue().substring(0, id.getValue().indexOf(':'));
                if (GAMES.contains(ns) && !ns.equals(facts.game())) return wrongGame(id.getKey(), id.getValue());
            }
        }
        return null;
    }

    private Problem wrongGame(String at, String id) {
        return new Problem(at, Problem.Code.WRONG_GAME, id + " is another game's id; this is " + facts.game());
    }

    private static Map<String, String> ids(Object v, String at) {
        Map<String, String> out = new LinkedHashMap<>();
        if (v instanceof String s && s.indexOf(':') > 0) out.put(at, s);
        else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) if (l.get(i) instanceof String s && s.indexOf(':') > 0) out.put(at + "/" + i, s);
        }
        return out;
    }

    // ---- evaluating -------------------------------------------------------------------------------------------

    @Override
    public Evaluation evaluate(String conditionJson, Context context) {
        Object v;
        try {
            v = Jv.parse(conditionJson);
        } catch (Jv.ParseException e) {
            return Evaluation.unknown(": not JSON: " + e.getMessage());
        }
        return evaluateParsed(v, "", context == null ? new Context(null, null, null, null, 0) : context);
    }

    /** Evaluates a parsed condition; reasons name pointers starting with {@code at}. */
    @SuppressWarnings("unchecked")
    public Evaluation evaluateParsed(Object v, String at, Context ctx) {
        if (!(v instanceof Map<?, ?> raw) || !(((Map<String, Object>) raw).get("type") instanceof String type)) {
            List<Problem> p = validateParsed(v, at);
            return Evaluation.unknown(reason(p.isEmpty() ? new Problem(at, Problem.Code.INVALID, "not a condition") : p.get(0)));
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        if (isLogic(type)) {
            Schema.Fail shape = Schema.check(def(type), m, at, shared, (child, p) -> null);
            if (shape != null) return Evaluation.unknown(reason(invalid(shape)));
            if (type.equals("not")) {
                Evaluation e = evaluateParsed(m.get("of"), at + "/of", ctx);
                return new Evaluation(e.answer().not(), e.reason());
            }
            boolean all = type.equals("all");
            List<Object> of = (List<Object>) m.get("of");
            String unknown = null;
            boolean anyUnknown = false;
            for (int i = 0; i < of.size(); i++) {
                Evaluation e = evaluateParsed(of.get(i), at + "/of/" + i, ctx);
                if (e.answer() == (all ? Answer.FALSE : Answer.TRUE)) return e;
                if (e.answer() == Answer.UNKNOWN && !anyUnknown) {
                    anyUnknown = true;
                    unknown = e.reason();
                }
            }
            return anyUnknown ? Evaluation.unknown(unknown) : Evaluation.of(all);
        }
        Problem p = leafProblem(type, m, at);
        if (p != null) return Evaluation.unknown(reason(p));
        if (type.indexOf('.') >= 0) {
            Registration r = types.get(type);
            Evaluation e = timed(r.owner(), "the evaluator of " + type, () -> r.evaluator().evaluate(new Jv.View(m), ctx));
            if (e == null) return Evaluation.unknown(at + ": " + type + "'s evaluator gave no answer in time");
            return e.answer() == Answer.UNKNOWN && e.reason() == null ? Evaluation.unknown(at + ": " + type + " couldn't tell") : e;
        }
        return sharedTypes.evaluate(type, m, at, ctx);
    }

    static String reason(Problem p) {
        return p.pointer() + ": " + p.message();
    }

    /** Who owns an entity, by the owner sources in registration order. */
    Owner ownerOf(String entityId) {
        List<Map.Entry<String, OwnerSource>> sources;
        synchronized (this) {
            sources = new ArrayList<>(ownerSources.entrySet());
        }
        for (Map.Entry<String, OwnerSource> s : sources) {
            Owner o = timed(s.getKey(), "its owner source", () -> s.getValue().ownerOf(entityId));
            if (o != null && o.kind() != Owner.Kind.UNKNOWN) return o;
        }
        return Owner.UNKNOWN;
    }

    /** Runs a mod's callback on the budget: null when it threw or ran late. */
    private <T> T timed(String owner, String what, Supplier<T> call) {
        long start = nanos.getAsLong();
        T result;
        try {
            result = call.get();
        } catch (RuntimeException | LinkageError e) {
            warn.accept(owner + ": " + what + " threw " + e + "; answering UNKNOWN");
            return null;
        }
        long tookMs = (nanos.getAsLong() - start) / 1_000_000L;
        int budget = Math.max(1, Math.min(50, budgetMs.getAsInt()));
        if (tookMs > budget) {
            int streak = lateStreak.merge(owner, 1, Integer::sum);
            warn.accept(owner + ": " + what + " took " + tookMs + " ms, over the " + budget + " ms budget; answering UNKNOWN");
            if (streak == 3) warn.accept(owner + ": " + what + " has been late three times in a row and is slowing the server");
            return null;
        }
        lateStreak.remove(owner);
        return result;
    }

    // ---- the shared types' schema -----------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Map<String, Object> def(String type) {
        Object d = sharedDefs().get(type);
        return d instanceof Map<?, ?> ? (Map<String, Object>) d : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sharedDefs() {
        return (Map<String, Object>) shared.get("$defs");
    }

    private static volatile Map<String, Object> sharedSchema;

    /** The shared types' schema, from the jar. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> sharedSchema() {
        Map<String, Object> s = sharedSchema;
        if (s != null) return s;
        try (InputStream in = Library.class.getResourceAsStream("shared-types.json")) {
            if (in == null) throw new IllegalStateException("shared-types.json is missing from the jar");
            s = (Map<String, Object>) Jv.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("can't read shared-types.json", e);
        }
        sharedSchema = s;
        return s;
    }
}
