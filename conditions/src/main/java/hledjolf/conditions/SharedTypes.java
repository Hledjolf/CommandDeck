package hledjolf.conditions;

import hledjolf.conditions.api.Answer;
import hledjolf.conditions.api.Context;
import hledjolf.conditions.api.Evaluation;
import hledjolf.conditions.api.Owner;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared set's answers, from the game's {@link GameFacts}: the comparing, the registries and the subjects are
 * done here, once for every game. Each condition reaching here has passed validation.
 */
final class SharedTypes {
    private final Library lib;
    private final GameFacts facts;

    SharedTypes(Library lib, GameFacts facts) {
        this.lib = lib;
        this.facts = facts;
    }

    Evaluation evaluate(String type, Map<String, Object> m, String at, Context ctx) {
        if (type.equals("counter")) {
            long n = ctx.counters().getOrDefault((String) m.get("name"), 0L);
            return compare(m, BigDecimal.valueOf(n));
        }
        if (type.equals("player_online")) {
            String name = name((String) m.get("subject"), ctx);
            Boolean on = name == null ? null : facts.online(name);
            return on == null ? unknown(at + "/subject", "can't tell who " + m.get("subject") + " is") : Evaluation.of(on);
        }
        String s = subject(m, ctx, type.equals("time_of_day") ? "{self}" : null);
        if (s == null) return unknown(at + "/subject", "the subject " + spec(m) + " isn't online or loaded");
        switch (type) {
            case "item_count": return itemCount(m, at, s);
            case "stat": return stat(s, own((String) m.get("stat")), m, at);
            case "health": {
                String h = facts.healthStat();
                return h == null ? unknown(at, facts.game() + " has no health stat") : stat(s, h, m, at);
            }
            case "time_of_day": return timeOfDay(m, at, s);
            case "distance": return distance(m, at, s, ctx);
            case "in_area": return inArea(m, at, s);
            case "is_night": return known(facts.night(s), at, "night");
            case "weather": {
                String w = facts.weather(s);
                if (w == null) return unknown(at, "can't tell the subject's weather");
                return matchAny("weather", game(w), strings(m.get("category")), at);
            }
            case "zone": {
                String z = facts.zone(s);
                if (z == null) return unknown(at, "can't tell the subject's zone");
                return Evaluation.of(strings(m.get("in")).contains(game(z)));
            }
            case "in_fluid": {
                String f = facts.fluidAt(s);
                if (f == null) return unknown(at, "can't tell what the subject is in");
                if (f.isEmpty()) return Evaluation.of(false);
                return matchAny("fluid", game(f), strings(m.get("fluid")), at);
            }
            case "near_fluid": {
                Set<String> near = facts.fluidsNear(s, number(m.get("radius")).doubleValue());
                if (near == null) return unknown(at, "can't tell which fluids are near");
                boolean unknown = false;
                for (String f : near) {
                    Evaluation e = matchAny("fluid", game(f), strings(m.get("fluid")), at);
                    if (e.answer() == Answer.TRUE) return e;
                    unknown |= e.answer() == Answer.UNKNOWN;
                }
                return unknown ? unknown(at, "a fluid nearby has no registry entry") : Evaluation.of(false);
            }
            case "near_entity": return nearEntity(m, at, s, ctx);
            case "wielding": {
                String h = facts.held(s);
                if (h == null) return unknown(at, "can't tell what the subject holds");
                return h.isEmpty() ? Evaluation.of(false) : itemMatches(m, game(h), at);
            }
            case "wearing": {
                List<String> worn = facts.worn(s);
                if (worn == null) return unknown(at, "can't tell what the subject wears");
                boolean unknown = false;
                for (String w : worn) {
                    Evaluation e = itemMatches(m, game(w), at);
                    if (e.answer() == Answer.TRUE) return e;
                    unknown |= e.answer() == Answer.UNKNOWN;
                }
                return unknown ? unknown(at + "/tag", "a worn item has no registry entry") : Evaluation.of(false);
            }
            default: return unknown(at + "/type", "\"" + type + "\" can't be answered here");
        }
    }

    // ---- types ------------------------------------------------------------------------------------------------

    private Evaluation itemCount(Map<String, Object> m, String at, String s) {
        Map<String, Long> inv = facts.inventory(s);
        if (inv == null) return unknown(at, "can't read the subject's inventory");
        long n = 0;
        if (m.get("item") instanceof String item) {
            n = inv.getOrDefault(own(item), 0L);
        } else {
            String tag = (String) m.get("tag");
            if (!lib.registries().listed("item_tag", tag)) return unknown(at + "/tag", "no item_tag entry lists " + tag);
            for (Map.Entry<String, Long> e : inv.entrySet()) {
                if (Boolean.TRUE.equals(lib.registries().matches("item_tag", game(e.getKey()), tag))) n += e.getValue();
            }
        }
        return compare(m, BigDecimal.valueOf(n));
    }

    private Evaluation stat(String s, String stat, Map<String, Object> m, String at) {
        Double v = facts.stat(s, stat, "percent".equals(m.get("unit")));
        if (v == null) return unknown(at, "the subject has no stat " + stat);
        return compare(m, BigDecimal.valueOf(v));
    }

    /** {@code from} up to, not including, {@code to}; {@code from > to} wraps past midnight. */
    private Evaluation timeOfDay(Map<String, Object> m, String at, String s) {
        Double h = facts.hour(s);
        if (h == null) return unknown(at, "can't tell the time in the subject's world");
        double from = number(m.get("from")).doubleValue(), to = number(m.get("to")).doubleValue();
        return Evaluation.of(from <= to ? h >= from && h < to : h >= from || h < to);
    }

    private Evaluation distance(Map<String, Object> m, String at, String s, Context ctx) {
        double[] here = facts.position(s);
        if (here == null) return unknown(at, "can't tell where the subject is");
        Object to = m.get("to");
        double[] there;
        if (to instanceof List<?> l) {
            there = position(l);
        } else {
            String t = (String) to;
            if (t.startsWith("marker:")) there = facts.marker(t.substring(7));
            else {
                String other = facts.subject(nameOf(t, ctx));
                if (other == null) return unknown(at + "/to", t + " isn't online or loaded");
                String w1 = facts.world(s), w2 = facts.world(other);
                if (w1 == null || !w1.equals(w2)) return unknown(at + "/to", t + " is in another world");
                there = facts.position(other);
            }
        }
        if (there == null) return unknown(at + "/to", "can't tell where that is");
        if (there.length != here.length) return unknown(at + "/to", "a position on " + facts.game() + " has " + here.length + " numbers");
        double sum = 0;
        for (int i = 0; i < here.length; i++) sum += (here[i] - there[i]) * (here[i] - there[i]);
        return compare(m, BigDecimal.valueOf(Math.sqrt(sum)));
    }

    private Evaluation inArea(Map<String, Object> m, String at, String s) {
        double[] p = facts.position(s);
        if (p == null) return unknown(at, "can't tell where the subject is");
        double[] min = position((List<?>) m.get("min")), max = position((List<?>) m.get("max"));
        if (min.length != p.length || max.length != p.length) return unknown(at, "a position on " + facts.game() + " has " + p.length + " numbers");
        if (m.get("world") instanceof String w) {
            String here = facts.world(s);
            if (here == null) return unknown(at, "can't tell the subject's world");
            if (!here.equals(w)) return Evaluation.of(false);
        }
        for (int i = 0; i < p.length; i++) {
            if (p[i] < Math.min(min[i], max[i]) || p[i] > Math.max(min[i], max[i])) return Evaluation.of(false);
        }
        return Evaluation.of(true);
    }

    /**
     * Counts the matching entities. An entity whose kind has no entry for the tag, or whose owner no source knows,
     * might or might not count; the answer is UNKNOWN only when that could change it.
     */
    private Evaluation nearEntity(Map<String, Object> m, String at, String s, Context ctx) {
        List<GameFacts.Entity> near = facts.entitiesNear(s, number(m.get("radius")).doubleValue());
        if (near == null) return unknown(at, "can't tell what is near the subject");
        String ownerId = null;
        if (m.get("owner") instanceof String o) {
            ownerId = facts.subject(nameOf(o, ctx));
            if (ownerId == null) return unknown(at + "/owner", o + " isn't online or loaded");
        }
        long sure = 0, maybe = 0;
        for (GameFacts.Entity e : near) {
            Boolean match;
            if (m.get("id") instanceof String id) match = id.equals(game(e.kind()));
            else {
                String tag = (String) m.get("tag");
                // The game decides the core: tags it reports (player, hostile, passive) for every entity it knows.
                match = e.tags().contains(tag) ? Boolean.TRUE : tag.startsWith("core:") && !e.tags().isEmpty() ? Boolean.FALSE
                    : lib.registries().matches("entity_tag", game(e.kind()), tag);
            }
            if (Boolean.FALSE.equals(match)) continue;
            if (ownerId != null) {
                Owner o = lib.ownerOf(e.id());
                if (o.kind() == Owner.Kind.NONE || (o.kind() == Owner.Kind.PLAYER && !ownerId.equals(o.playerId()))) continue;
                if (o.kind() == Owner.Kind.UNKNOWN) match = null;
            }
            if (match == null) maybe++;
            else sure++;
        }
        Answer first = compare(m, BigDecimal.valueOf(sure)).answer();
        for (long n = sure + 1; n <= sure + maybe; n++) {
            if (compare(m, BigDecimal.valueOf(n)).answer() != first) return unknown(at, maybe + " entities nearby might or might not count");
        }
        return Evaluation.of(first);
    }

    private Evaluation itemMatches(Map<String, Object> m, String item, String at) {
        if (m.get("item") instanceof String want) return Evaluation.of(want.equals(item));
        Boolean b = lib.registries().matches("item_tag", item, (String) m.get("tag"));
        return b == null ? unknown(at + "/tag", item + " has no item_tag entry") : Evaluation.of(b);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Evaluation matchAny(String registry, String id, List<String> wanted, String at) {
        boolean unknown = false;
        for (String w : wanted) {
            Boolean b = lib.registries().matches(registry, id, w);
            if (Boolean.TRUE.equals(b)) return Evaluation.of(true);
            unknown |= b == null;
        }
        return unknown ? unknown(at, id + " has no " + registry + " entry") : Evaluation.of(false);
    }

    private static Evaluation compare(Map<String, Object> m, BigDecimal actual) {
        int c = actual.compareTo(number(m.get("value")));
        boolean r;
        switch ((String) m.get("op")) {
            case "eq": r = c == 0; break;
            case "ne": r = c != 0; break;
            case "lt": r = c < 0; break;
            case "le": r = c <= 0; break;
            case "gt": r = c > 0; break;
            default: r = c >= 0;
        }
        return Evaluation.of(r);
    }

    private static Evaluation known(Boolean b, String at, String what) {
        return b == null ? unknown(at, "can't tell whether it's " + what) : Evaluation.of(b);
    }

    private static Evaluation unknown(String at, String why) {
        return Evaluation.unknown(at + ": " + why);
    }

    /** The subject's id, or null. {@code fallback} is the subject when the condition has none ({@code {self}}). */
    private String subject(Map<String, Object> m, Context ctx, String fallback) {
        String spec = m.get("subject") instanceof String x ? x : fallback != null ? fallback : "{self}";
        String name = nameOf(spec, ctx);
        return name == null ? null : facts.subject(name);
    }

    private static String spec(Map<String, Object> m) {
        return m.get("subject") instanceof String x ? x : "{self}";
    }

    private static String name(String spec, Context ctx) {
        return nameOf(spec, ctx);
    }

    /** {@code {self}}, {@code {target}} and the consumer's own placeholders resolved; a plain name as it is. */
    static String nameOf(String spec, Context ctx) {
        if (spec.equals("{self}")) return ctx.actor();
        if (spec.equals("{target}")) return ctx.target();
        if (spec.length() > 2 && spec.startsWith("{") && spec.endsWith("}")) return ctx.placeholders().get(spec.substring(1, spec.length() - 1));
        return spec;
    }

    private String game(String own) {
        return facts.game() + ":" + own;
    }

    /** A game id without its prefix (validation made sure it's this game's). */
    private static String own(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static BigDecimal number(Object v) {
        return Jv.decimal(v);
    }

    private static double[] position(List<?> l) {
        double[] p = new double[l.size()];
        for (int i = 0; i < p.length; i++) p[i] = Jv.decimal(l.get(i)).doubleValue();
        return p;
    }

    private static List<String> strings(Object v) {
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
