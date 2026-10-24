package commanddeck.hytale;

import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import commanddeck.command.ArgType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The item and block ids the server has loaded, for the form's pickers. Each list is sorted once and kept
 * until the number of assets changes (an asset reload). Any trouble reading a map gives an empty list, and
 * the form falls back to a plain text field.
 */
final class HytaleAssets {
    private final HytaleLogger log;
    private final Cache items = new Cache(Item::getAssetMap);
    private final Cache blocks = new Cache(BlockType::getAssetMap);

    HytaleAssets(HytaleLogger log) {
        this.log = log;
    }

    List<String> ids(ArgType type) {
        if (type == ArgType.ITEM) return items.get();
        if (type == ArgType.BLOCK) return blocks.get();
        return List.of();
    }

    private final class Cache {
        private final Supplier<? extends AssetMap<String, ?>> map;
        private List<String> ids = List.of();
        private int count = -1;
        private boolean warned;

        Cache(Supplier<? extends AssetMap<String, ?>> map) {
            this.map = map;
        }

        synchronized List<String> get() {
            try {
                AssetMap<String, ?> m = map.get();
                if (m == null) return List.of();
                int n = m.getAssetCount();
                if (n != count) {
                    List<String> out = new ArrayList<>(m.getAssetMap().keySet());
                    out.sort(String.CASE_INSENSITIVE_ORDER);
                    ids = List.copyOf(out);
                    count = n;
                }
                return ids;
            } catch (RuntimeException e) {
                if (!warned) log.atWarning().withCause(e).log("[CommandDeck] can't list assets for a picker; using a text field");
                warned = true;
                return List.of();
            }
        }
    }
}
