package commanddeck.hytale;

import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import commanddeck.jvm.LibraryConditions;
import commanddeck.macro.ConditionPort;
import hledjolf.conditions.Library;
import hledjolf.conditions.api.RegistryEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The condition library on Hytale: the core's port, answering each check on the presser's world thread (where
 * {@link HytaleFacts} can read the game), and the registries' {@code hytale} entries, from the game's own asset tags.
 */
final class HytaleConditions implements ConditionPort {
    /** How long a check from another thread waits for the presser's world thread, as for {@code {look}}. */
    static final long WAIT_MILLIS = HytaleGame.LOOK_WAIT_MILLIS;
    static final String OWNER = "hytale";

    /** Weather tags, and the seeds each means. */
    private static final Map<String, List<String>> WEATHER_TAGS = new LinkedHashMap<>();
    /** Weathers whose tags miss what their name says (measured on 0.7.5), with the seeds the name gives. */
    private static final Map<String, List<String>> WEATHER_BY_NAME = new LinkedHashMap<>();

    static {
        WEATHER_TAGS.put("Rain", List.of("core:rain", "core:precipitation"));
        WEATHER_TAGS.put("Snow", List.of("core:snow", "core:precipitation"));
        WEATHER_TAGS.put("Storm", List.of("core:storm"));
        WEATHER_TAGS.put("Thunderstorm", List.of("core:storm"));
        WEATHER_TAGS.put("Sandstorm", List.of("core:storm"));
        WEATHER_TAGS.put("Foggy", List.of("core:fog"));
        WEATHER_TAGS.put("Sunny", List.of("core:clear"));
        WEATHER_BY_NAME.put("Zone4_GhostForest_Rain", List.of("core:rain", "core:precipitation"));
        WEATHER_BY_NAME.put("Cave_Fog", List.of("core:fog"));
        WEATHER_BY_NAME.put("Dungeon_Goblin_Void_Finale_Fog", List.of("core:fog"));
        WEATHER_BY_NAME.put("Skylands_Sunny", List.of("core:clear"));
        WEATHER_BY_NAME.put("Skylands_Rapid_Marsh_Stormy", List.of("core:storm"));
        WEATHER_BY_NAME.put("Cave_Goblin_Void_Event_Storm", List.of("core:storm"));
    }

    private final LibraryConditions port;

    HytaleConditions(Library library) {
        this.port = new LibraryConditions(library);
    }

    LibraryConditions port() {
        return port;
    }

    @Override
    public List<Issue> check(Map<String, Object> condition) {
        return port.check(condition);
    }

    @Override
    public String game() {
        return port.game();
    }

    @Override
    public List<Form> forms() {
        return port.forms();
    }

    @Override
    public Result evaluate(Map<String, Object> condition, String actor, String target, long tick) {
        PlayerRef p = HytaleFacts.player(actor);
        World w = HytaleFacts.worldOf(p);
        if (w == null || w.isInThread()) return port.evaluate(condition, actor, target, tick);
        CompletableFuture<Result> answer = new CompletableFuture<>();
        w.execute(() -> {
            try {
                answer.complete(port.evaluate(condition, actor, target, tick));
            } catch (Throwable e) {
                answer.completeExceptionally(e);
            }
        });
        try {
            return answer.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Answer.UNKNOWN, "interrupted");
        } catch (ExecutionException e) {
            return new Result(Answer.UNKNOWN, "the check failed: " + e.getCause());
        } catch (TimeoutException e) {
            return new Result(Answer.UNKNOWN, "the world didn't answer within " + WAIT_MILLIS + " ms");
        }
    }

    /**
     * The {@code hytale} entries, once the assets are loaded: every weather with the {@code core:} seeds its tags
     * (or, where they miss, its name) give; every fluid with {@code core:water} or {@code core:lava} and its
     * {@code Fluid=} tag; every item with its own tags (an item with none still gets an entry, so a tag check on it
     * is FALSE rather than UNKNOWN). Returns a line for the log.
     */
    String seed(Library lib) {
        Map<String, Set<String>> weather = new LinkedHashMap<>();
        for (Object k : Weather.getAssetMap().getAssetMap().keySet()) weather.put(String.valueOf(k), new TreeSet<>());
        WEATHER_TAGS.forEach((tag, seeds) -> {
            for (String id : keys(Weather.getAssetMap(), tag)) weather.computeIfAbsent(id, x -> new TreeSet<>()).addAll(seeds);
        });
        WEATHER_BY_NAME.forEach((id, seeds) -> {
            if (weather.containsKey(id)) weather.get(id).addAll(seeds);
        });
        lib.registerEntries("weather", entries(weather), OWNER);

        Map<String, Set<String>> fluids = new LinkedHashMap<>();
        for (Object k : Fluid.getAssetMap().getAssetMap().keySet()) fluids.put(String.valueOf(k), new TreeSet<>());
        Map<String, String> base = Map.of("Fluid=Water", "core:water", "Fluid=Lava", "core:lava");
        for (String tag : tagNames()) {
            if (!tag.startsWith("Fluid=")) continue;
            for (String id : keys(Fluid.getAssetMap(), tag)) {
                Set<String> in = fluids.computeIfAbsent(id, x -> new TreeSet<>());
                in.add("hytale:" + tag);
                if (base.containsKey(tag)) in.add(base.get(tag));
            }
        }
        lib.registerEntries("fluid", entries(fluids), OWNER);

        Map<String, Set<String>> items = new LinkedHashMap<>();
        for (Object k : Item.getAssetMap().getAssetMap().keySet()) items.put(String.valueOf(k), new TreeSet<>());
        int tagged = 0;
        for (String tag : tagNames()) {
            Set<String> ids = keys(Item.getAssetMap(), tag);
            if (!ids.isEmpty()) tagged++;
            for (String id : ids) items.computeIfAbsent(id, x -> new TreeSet<>()).add("hytale:" + tag);
        }
        lib.registerEntries("item_tag", entries(items), OWNER);
        return weather.size() + " weathers, " + fluids.size() + " fluids, " + items.size() + " items (" + tagged + " item tags)";
    }

    private static List<RegistryEntry> entries(Map<String, Set<String>> byId) {
        List<RegistryEntry> out = new ArrayList<>();
        byId.forEach((id, in) -> out.add(new RegistryEntry("hytale:" + id, new ArrayList<>(in))));
        return out;
    }

    /** The asset ids carrying a tag; empty when the tag doesn't exist. */
    private static Set<String> keys(AssetMap<?, ?> map, String tag) {
        int i = AssetRegistry.getTagIndex(tag);
        Set<String> out = new TreeSet<>();
        if (i == AssetRegistry.TAG_NOT_FOUND) return out;
        for (Object k : map.getKeysForTag(i)) out.add(String.valueOf(k));
        return out;
    }

    private static volatile List<String> tagNames;

    /**
     * Every asset tag's name. The game lists tags only by index, so the names come from its tag table; if that
     * can't be read, item and fluid tags are left out (weather seeds still work, by name).
     */
    @SuppressWarnings("unchecked")
    static List<String> tagNames() {
        List<String> names = tagNames;
        if (names != null) return names;
        names = new ArrayList<>();
        try {
            java.lang.reflect.Field f = AssetRegistry.class.getDeclaredField("TAG_MAP");
            f.setAccessible(true);
            names.addAll(new TreeSet<>(((Map<String, Integer>) f.get(null)).keySet()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            names = List.of();
        }
        tagNames = names;
        return names;
    }
}
