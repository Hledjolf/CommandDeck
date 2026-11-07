package commanddeck.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.hypixel.hytale.builtin.weather.components.WeatherTracker;
import hledjolf.conditions.GameFacts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * What Hytale tells the condition library. Subjects are players, by UUID or name. Every fact is read on the
 * subject's world thread; asked from any other thread, it gives null (UNKNOWN). {@link HytaleConditions} moves a
 * whole check onto that thread first.
 */
final class HytaleFacts implements GameFacts {
    /** The day's light part: sunrise at 0.2 of the day (04:48), sunset at 0.8 (19:12), as {@code WorldTimeResource} has it. */
    static final double SUNRISE = 0.2;
    static final double SUNSET = 0.8;

    private static final Set<String> ANSWERS = Set.of("item_count", "stat", "health", "time_of_day", "distance", "in_area",
        "player_online", "is_night", "weather", "zone", "in_fluid", "near_fluid", "near_entity", "wielding", "wearing");

    @Override public String game() { return "hytale"; }
    @Override public int dimensions() { return 3; }
    @Override public Set<String> answers() { return ANSWERS; }
    @Override public String healthStat() { return "Health"; }

    /** An online player, by UUID or by name. */
    static PlayerRef player(String nameOrId) {
        Universe u = Universe.get();
        if (u == null || nameOrId == null) return null;
        try {
            return u.getPlayer(UUID.fromString(nameOrId));
        } catch (IllegalArgumentException notUuid) {
            return u.getPlayerByUsername(nameOrId, NameMatching.EXACT_IGNORE_CASE);
        }
    }

    static World worldOf(PlayerRef p) {
        Universe u = Universe.get();
        UUID w = p == null ? null : p.getWorldUuid();
        return u == null || w == null ? null : u.getWorld(w);
    }

    @Override
    public String subject(String nameOrId) {
        PlayerRef p = player(nameOrId);
        return p == null ? null : p.getUuid().toString();
    }

    @Override
    public Boolean online(String nameOrId) {
        return Universe.get() == null ? null : player(nameOrId) != null;
    }

    /** Reads from a subject's entity on its world thread; null when it can't. */
    private static <T> T read(String subject, Function<Here, T> f) {
        PlayerRef p = player(subject);
        World w = worldOf(p);
        if (w == null || !w.isInThread()) return null;
        Ref<EntityStore> ref = p.getReference();
        if (ref == null || !ref.isValid()) return null;
        try {
            return f.apply(new Here(p, w, ref, ref.getStore()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record Here(PlayerRef player, World world, Ref<EntityStore> ref, Store<EntityStore> store) {
        org.joml.Vector3d position() {
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            return t == null ? null : t.getPosition();
        }
    }

    @Override
    public Map<String, Long> inventory(String subject) {
        return read(subject, h -> {
            // Everything the player carries: hotbar, storage, backpack, armor, utility, tools and the rest.
            ItemContainer c = InventoryComponent.getCombined(h.store(), h.ref(), InventoryComponent.EVERYTHING);
            if (c == null) return null;
            Map<String, Long> out = new HashMap<>();
            for (short i = 0; i < c.getCapacity(); i++) {
                ItemStack s = c.getItemStack(i);
                if (!ItemStack.isEmpty(s)) out.merge(s.getItemId(), (long) s.getQuantity(), Long::sum);
            }
            return out;
        });
    }

    @Override
    public Double stat(String subject, String stat, boolean percent) {
        return read(subject, h -> {
            int index = EntityStatType.getAssetMap().getIndex(stat);
            EntityStatMap m = h.store().getComponent(h.ref(), EntityStatMap.getComponentType());
            // An unknown id gives a negative index, which the map doesn't check.
            EntityStatValue v = m == null || index < 0 ? null : m.get(index);
            if (v == null) return null;
            return percent ? (double) v.asPercentage() * 100.0 : (double) v.get();
        });
    }

    private static Double dayProgress(String subject) {
        return read(subject, h -> {
            WorldTimeResource t = h.store().getResource(WorldTimeResource.getResourceType());
            return t == null ? null : (double) t.getDayProgress();
        });
    }

    @Override
    public Double hour(String subject) {
        Double d = dayProgress(subject);
        return d == null ? null : Math.round(d * 24_000.0) / 1000.0;
    }

    @Override
    public Boolean night(String subject) {
        Double d = dayProgress(subject);
        return d == null ? null : d < SUNRISE || d >= SUNSET;
    }

    @Override
    public double[] position(String subject) {
        return read(subject, h -> {
            org.joml.Vector3d p = h.position();
            return p == null ? null : new double[] {p.x, p.y, p.z};
        });
    }

    @Override
    public String world(String subject) {
        return read(subject, h -> h.world().getName());
    }

    @Override
    public String weather(String subject) {
        return read(subject, h -> {
            WeatherTracker wt = h.store().getComponent(h.ref(), WeatherTracker.getComponentType());
            Weather w = wt == null ? null : Weather.getAssetMap().getAsset(wt.getWeatherIndex());
            return w == null ? null : w.getId();
        });
    }

    @Override
    public String zone(String subject) {
        return read(subject, h -> {
            WeatherTracker wt = h.store().getComponent(h.ref(), WeatherTracker.getComponentType());
            Environment e = wt == null ? null : Environment.getAssetMap().getAsset(wt.getEnvironmentId());
            return e == null ? null : e.getId();
        });
    }

    /** The fluid at a block, or the empty string for none. */
    private static String fluidAt(World w, int x, int y, int z) {
        int id = WorldUtil.getFluidIdAtPosition(w.getChunkStore(), x, y, z);
        if (id == 0) return "";
        Fluid f = Fluid.getAssetMap().getAsset(id);
        return f == null ? null : f.getId();
    }

    /** The fluid at the subject's feet, or else at its head. */
    @Override
    public String fluidAt(String subject) {
        return read(subject, h -> {
            org.joml.Vector3d p = h.position();
            if (p == null) return null;
            int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
            String feet = fluidAt(h.world(), x, y, z);
            return feet == null || !feet.isEmpty() ? feet : fluidAt(h.world(), x, y + 1, z);
        });
    }

    @Override
    public Set<String> fluidsNear(String subject, double radius) {
        return read(subject, h -> {
            org.joml.Vector3d p = h.position();
            if (p == null) return null;
            int r = (int) Math.ceil(radius);
            int cx = (int) Math.floor(p.x), cy = (int) Math.floor(p.y), cz = (int) Math.floor(p.z);
            Set<String> out = new LinkedHashSet<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                        String f = fluidAt(h.world(), cx + dx, cy + dy, cz + dz);
                        if (f != null && !f.isEmpty()) out.add(f);
                    }
                }
            }
            return out;
        });
    }

    @Override
    public List<Entity> entitiesNear(String subject, double radius) {
        return read(subject, h -> {
            org.joml.Vector3d p = h.position();
            if (p == null) return null;
            SpatialResource<Ref<EntityStore>, EntityStore> sr = h.store().getResource(EntityModule.get().getEntitySpatialResourceType());
            List<Ref<EntityStore>> found = new ArrayList<>();
            sr.getSpatialStructure().collect(p, radius, found);
            List<Entity> out = new ArrayList<>();
            for (Ref<EntityStore> r : found) {
                if (r == null || !r.isValid() || r.equals(h.ref())) continue;
                UUIDComponent u = h.store().getComponent(r, UUIDComponent.getComponentType());
                String id = u == null ? null : u.getUuid().toString();
                if (h.store().getComponent(r, Player.getComponentType()) != null) {
                    out.add(new Entity(id, "Player", Set.of("core:player")));
                    continue;
                }
                NPCEntity npc = h.store().getComponent(r, NPCEntity.getComponentType());
                if (npc == null) continue;
                WorldSupport ws = h.store().getComponent(r, WorldSupport.getComponentType());
                Attitude a = ws == null ? null : ws.getDefaultPlayerAttitude();
                Set<String> tags = new LinkedHashSet<>();
                if (a == Attitude.HOSTILE) tags.add("core:hostile");
                else if (a == Attitude.NEUTRAL || a == Attitude.FRIENDLY || a == Attitude.REVERED) tags.add("core:passive");
                // So the game's own answer is final for the core: tags even when the attitude is IGNORE.
                if (a != null) tags.add("hytale:Attitude=" + a.name());
                out.add(new Entity(id, npc.getRoleName(), tags));
            }
            return out;
        });
    }

    @Override
    public String held(String subject) {
        return read(subject, h -> {
            ItemStack s = InventoryComponent.getItemInHand(h.store(), h.ref());
            return ItemStack.isEmpty(s) ? "" : s.getItemId();
        });
    }

    @Override
    public List<String> worn(String subject) {
        return read(subject, h -> {
            InventoryComponent.Armor armor = h.store().getComponent(h.ref(), InventoryComponent.Armor.getComponentType());
            InventoryComponent.Utility utility = h.store().getComponent(h.ref(), InventoryComponent.Utility.getComponentType());
            if (armor == null && utility == null) return null;
            List<String> out = new ArrayList<>();
            ItemContainer c = armor == null ? null : armor.getInventory();
            if (c != null) {
                for (short i = 0; i < c.getCapacity(); i++) {
                    ItemStack s = c.getItemStack(i);
                    if (!ItemStack.isEmpty(s)) out.add(s.getItemId());
                }
            }
            ItemStack u = utility == null ? null : utility.getActiveItem();
            if (!ItemStack.isEmpty(u)) out.add(u.getItemId());
            return out;
        });
    }
}
