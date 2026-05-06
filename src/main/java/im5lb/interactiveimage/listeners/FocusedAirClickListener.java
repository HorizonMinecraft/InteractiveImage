package im5lb.interactiveimage.listeners;

import im5lb.interactiveimage.actions.ActionExecutor;
import im5lb.interactiveimage.api.event.InteractiveFrameClickEvent;
import im5lb.interactiveimage.config.InteractiveImageConfig;
import im5lb.interactiveimage.editor.EditorManager;
import im5lb.interactiveimage.focus.FocusScanner;
import im5lb.interactiveimage.focus.FocusState;
import im5lb.interactiveimage.model.ResolvedTarget;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Enables "click actions" beyond vanilla entity interaction reach by using the currently focused target and
 * listening for air clicks.
 *
 * Note: We only handle AIR clicks to reduce conflicts with normal block interactions.
 */
public final class FocusedAirClickListener implements Listener {

    private final Supplier<InteractiveImageConfig> configSupplier;
    private final FocusScanner focusScanner;
    private final EditorManager editorManager;

    private final ActionExecutor actionExecutor = new ActionExecutor();

    public FocusedAirClickListener(
            Supplier<InteractiveImageConfig> configSupplier,
            FocusScanner focusScanner,
            EditorManager editorManager
    ) {
        this.configSupplier = configSupplier;
        this.focusScanner = focusScanner;
        this.editorManager = editorManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAirClick(PlayerInteractEvent event) {
        // Only process the main hand to avoid firing twice (Bukkit fires once per hand).
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        // Handle both AIR and BLOCK clicks. When the player is far from the frame but
        // there is a block within normal reach in their line of sight, Minecraft fires
        // RIGHT_CLICK_BLOCK / LEFT_CLICK_BLOCK rather than the *_AIR variants, so we
        // must handle both to support clicking from any distance.
        // Note: ignoreCancelled is intentionally NOT set — block-click events are often
        // pre-cancelled by the server (e.g. no permission to place), and we still need
        // to process them to detect far-distance frame clicks.
        boolean isRight = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean isLeft  = action == Action.LEFT_CLICK_AIR  || action == Action.LEFT_CLICK_BLOCK;
        if (!isRight && !isLeft) {
            return;
        }

        Player player = event.getPlayer();
        if (editorManager != null && editorManager.isEnabled(player)) {
            return;
        }

        InteractiveImageConfig cfg = configSupplier.get();

        // Try the cached focus state first. If the player clicks before the periodic
        // scanner tick has run (e.g. they just walked up and immediately clicked),
        // fall back to a live ray-trace so the click is never silently dropped.
        FocusState focus = focusScanner == null ? null : focusScanner.getFocusState(player.getUniqueId());

        ItemFrame frame;
        ResolvedTarget resolved;

        if (focus != null && focus.target() != null && focus.target().rule() != null) {
            // Use cached focus state
            World world = Bukkit.getWorld(focus.worldUuid());
            if (world == null) {
                return;
            }
            var entity = world.getEntity(focus.frameUuid());
            if (!(entity instanceof ItemFrame f) || f.isDead() || !f.isValid()) {
                return;
            }
            frame = f;
            resolved = focus.target();
        } else {
            // No cached focus — do a live ray-trace fallback
            if (focusScanner == null) {
                return;
            }
            var liveResolved = focusScanner.resolveClickTarget(player);
            if (liveResolved.isEmpty() || liveResolved.get().rule() == null) {
                return;
            }
            resolved = liveResolved.get();
            World world = player.getWorld();
            var entity = world.getEntity(resolved.frameUuid());
            if (!(entity instanceof ItemFrame f) || f.isDead() || !f.isValid()) {
                return;
            }
            frame = f;
        }

        if (!withinClickDistance(player, frame, cfg, resolved)) {
            return;
        }

        // If the player is close enough for vanilla entity interaction (~3 blocks),
        // FrameInteractListener already handles it via PlayerInteractEntityEvent.
        // Only fire here for the extended-range case to avoid double-firing actions.
        double distSq = player.getEyeLocation().distanceSquared(frame.getLocation());
        if (distSq <= 3.0 * 3.0) {
            return;
        }

        InteractiveFrameClickEvent.ClickType clickType = isRight
                ? InteractiveFrameClickEvent.ClickType.RIGHT_CLICK
                : InteractiveFrameClickEvent.ClickType.LEFT_CLICK;

        var apiEvent = new InteractiveFrameClickEvent(player, frame, clickType, resolved.providerId(), resolved.mapName(), resolved.title());
        Bukkit.getPluginManager().callEvent(apiEvent);
        if (apiEvent.isCancelled()) {
            return;
        }

        // No tick-based cooldown: actions run immediately when clicked.
        InteractiveImageConfig.MapRule rule = resolved.rule();

        Map<String, String> placeholders = Map.of(
                "{player}", player.getName(),
                "{uuid}", player.getUniqueId().toString(),
                "{provider}", resolved.providerId(),
                "{map}", resolved.mapName() == null ? "" : resolved.mapName(),
                "{title}", resolved.title() == null ? "" : resolved.title()
        );

        if (clickType == InteractiveFrameClickEvent.ClickType.RIGHT_CLICK) {
            actionExecutor.run(player, rule.onRightClick(), placeholders);
        } else {
            actionExecutor.run(player, rule.onLeftClick(), placeholders);
        }

        // Prevent accidental item use when interacting at a distance.
        event.setCancelled(true);
    }

    private static boolean withinClickDistance(Player player, ItemFrame frame, InteractiveImageConfig cfg, ResolvedTarget target) {
        double max = cfg.activation().click().maxDistance();
        var rule = target.rule();
        if (rule != null && rule.activation() != null && rule.activation().clickMaxDistance() != null) {
            max = rule.activation().clickMaxDistance();
        }
        if (max <= 0.0) {
            return false;
        }
        return player.getEyeLocation().distanceSquared(frame.getLocation()) <= (max * max);
    }
}

