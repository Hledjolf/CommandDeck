package commanddeck.hytale;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import commanddeck.hytale.window.DeckWindow;
import commanddeck.hytale.window.UiOps;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The CommandDeck window as a raw {@code .ui} page. All state and layout live in {@link DeckWindow}; this
 * class only builds the page, decodes the client's events and sends the window's updates.
 */
final class DeckPage extends InteractiveCustomUIPage<DeckPage.NoEvent> {
    /** Events are decoded from the raw JSON, so the typed codec is never used. */
    static final class NoEvent {
        static final BuilderCodec<NoEvent> CODEC = BuilderCodec.builder(NoEvent.class, NoEvent::new).build();
    }

    static final long TYPING_DELAY_MS = 300;

    private final DeckWindow window;
    private final HytaleLogger logger;
    private volatile ScheduledFuture<?> pendingRedraw;
    private volatile boolean closed;

    DeckPage(PlayerRef player, DeckWindow window, HytaleLogger logger) {
        super(player, CustomPageLifetime.CanDismiss, NoEvent.CODEC);
        this.window = window;
        this.logger = logger;
    }

    /** Opens the page for a player, on the player's world thread. False if the player is not in a world. */
    static boolean open(PlayerRef player, DeckWindow window, HytaleLogger logger) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return false;
        World world = ref.getStore().getExternalData().getWorld();
        Runnable r = () -> {
            Ref<EntityStore> now = player.getReference();
            if (now == null || !now.isValid()) return;
            Store<EntityStore> store = now.getStore();
            Player p = store.getComponent(now, Player.getComponentType());
            if (p != null) p.getPageManager().openCustomPage(now, store, new DeckPage(player, window, logger));
        };
        if (world.isInThread()) r.run();
        else world.execute(r);
        return true;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append(DeckWindow.DOC);
        UiTranslate.into(window.open(), cmd, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String rawData) {
        DeckWindow.Event event;
        try {
            event = DeckWindow.decode(rawData);
        } catch (RuntimeException bad) {
            logger.atWarning().log("[CommandDeck] undecodable window event %s", rawData);
            return;
        }
        String action = event.action();
        UiOps ops;
        try {
            ops = window.onEvent(action, event.data());
        } catch (RuntimeException e) {
            logger.atSevere().withCause(e).log("[CommandDeck] window failed on %s", action);
            return;
        }
        if (DeckWindow.deferred(action)) {
            // Each keystroke sends an event, and every page update must be acknowledged before the client
            // sends the next one. Redraw once typing pauses, so keystrokes are not dropped.
            ScheduledFuture<?> old = pendingRedraw;
            if (old != null) old.cancel(false);
            pendingRedraw = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> send(window.deferredOps(action)), TYPING_DELAY_MS, TimeUnit.MILLISECONDS);
            return;
        }
        send(ops);
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        closed = true;
        ScheduledFuture<?> f = pendingRedraw;
        if (f != null) f.cancel(false);
        super.onDismiss(ref, store);
    }

    private void send(UiOps ops) {
        // A late redraw must never land on another page the player opened since.
        if (closed || ops == null || ops.isEmpty()) return;
        try {
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder ev = new UIEventBuilder();
            UiTranslate.into(ops, cmd, ev);
            sendUpdate(cmd, ev, false);
        } catch (RuntimeException e) {
            logger.atSevere().withCause(e).log("[CommandDeck] window update failed");
        }
    }
}
