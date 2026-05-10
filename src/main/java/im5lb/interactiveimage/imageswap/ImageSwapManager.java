package im5lb.interactiveimage.imageswap;

import im5lb.interactiveimage.InteractiveImage;
import im5lb.interactiveimage.config.InteractiveImageConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ImageSwapManager {

    private final InteractiveImage plugin;

    // frame UUID -> original item before swap (saved before any swap occurs)
    private final Map<UUID, ItemStack> originalItems = new HashMap<>();
    // frame UUID -> tile index within the source image (saved at focus time from original item)
    private final Map<UUID, Integer> tileIndices = new HashMap<>();
    // frame UUID -> pending auto-revert task id
    private final Map<UUID, Integer> revertTaskIds = new HashMap<>();
    // frame UUID -> set of all frame UUIDs in the same swap group (for group auto-revert)
    private final Map<UUID, List<UUID>> swapGroups = new HashMap<>();

    // ImageFrame reflection
    private volatile boolean initTried = false;
    private volatile boolean available = false;
    private Field imageMapManagerField;
    private Method getFromNameMethod;
    private Method imageMapGetMapViewsMethod;
    private Method imageMapGetNameMethod;
    private Method managerValuesMethod;

    public ImageSwapManager(InteractiveImage plugin) {
        this.plugin = plugin;
    }

    public void onFocus(ItemFrame frame, InteractiveImageConfig.MapRule rule) {
        if (rule == null) return;
        InteractiveImageConfig.ImageSwap swap = rule.imageSwap();
        if (swap == null || swap.hoverMap() == null || swap.hoverMap().isBlank()) return;
        if (!isAvailable()) return;

        UUID frameId = frame.getUniqueId();

        // Save original item and tile index ONLY if not already saved.
        // We must read the frame item BEFORE any swap has occurred to get the real original.
        // tileIndices is populated here from the original item so findMatchingMapView
        // doesn't need to re-read the (potentially already-swapped) frame item later.
        if (!originalItems.containsKey(frameId)) {
            ItemStack orig = frame.getItem();
            originalItems.put(frameId, orig == null ? null : orig.clone());
            // Compute and cache tile index from the original item now
            tileIndices.put(frameId, computeTileIndex(frame, orig));
        }

        MapView targetView = findMatchingMapViewByIndex(swap.hoverMap(), tileIndices.getOrDefault(frameId, 0));
        if (targetView == null) {
            plugin.getLogger().warning("[interactiveimage] ImageSwap: could not find map view for '"
                    + swap.hoverMap() + "' (tile " + tileIndices.getOrDefault(frameId, 0) + ")");
            return;
        }

        ItemStack newItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) newItem.getItemMeta();
        if (meta == null) return;
        meta.setMapView(targetView);
        newItem.setItemMeta(meta);
        frame.setItem(newItem, false);
    }

    /**
     * Called once per image group after all individual onFocus calls.
     * Schedules the auto-revert task for the whole group if configured.
     */
    public void onFocusGroupDone(List<UUID> groupFrameUuids, InteractiveImageConfig.MapRule rule) {
        if (rule == null) return;
        InteractiveImageConfig.ImageSwap swap = rule.imageSwap();
        if (swap == null) return;

        // Mark the hover map as an active swap target so its frames become non-interactive.
        if (swap.hoverMap() != null && !swap.hoverMap().isBlank()) {
            activeSwapTargets.add(swap.hoverMap().toLowerCase(java.util.Locale.ROOT));
        }

        // Register the group so auto-revert can revert all frames together
        for (UUID id : groupFrameUuids) {
            swapGroups.put(id, groupFrameUuids);
        }

        if (swap.autoSwapTicks() > 0) {
            // Cancel any existing tasks for all frames in the group
            for (UUID id : groupFrameUuids) {
                cancelRevertTask(id);
            }
            // Schedule a single task that reverts the whole group
            int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                if (swap.hoverMap() != null) {
                    activeSwapTargets.remove(swap.hoverMap().toLowerCase(java.util.Locale.ROOT));
                }
                for (UUID id : groupFrameUuids) {
                    revertById(id);
                }
            }, swap.autoSwapTicks());
            // Store the same task id for all frames so any individual cancel cleans it up
            for (UUID id : groupFrameUuids) {
                revertTaskIds.put(id, taskId);
            }
        }
    }

    public void onUnfocus(ItemFrame frame, InteractiveImageConfig.MapRule rule) {
        if (frame == null) return;
        UUID frameId = frame.getUniqueId();

        // Cancel auto-revert task for the whole group
        List<UUID> group = swapGroups.getOrDefault(frameId, List.of(frameId));
        // Cancel the shared task (stored under each frame id, but it's the same task)
        Integer taskId = revertTaskIds.get(frameId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
            for (UUID id : group) {
                revertTaskIds.remove(id);
            }
        }

        // Clear the swap target marker for this group's hover map
        if (rule != null && rule.imageSwap() != null && rule.imageSwap().hoverMap() != null) {
            activeSwapTargets.remove(rule.imageSwap().hoverMap().toLowerCase(java.util.Locale.ROOT));
        }

        if (rule == null) {
            revert(frame);
            return;
        }
        InteractiveImageConfig.ImageSwap swap = rule.imageSwap();
        if (swap == null || !swap.revertOnUnfocus()) return;
        revert(frame);
    }

    public void revert(ItemFrame frame) {
        if (frame == null) return;
        revertById(frame.getUniqueId());
    }

    private void revertById(UUID frameId) {
        cancelRevertTask(frameId);
        swapGroups.remove(frameId);
        tileIndices.remove(frameId);
        ItemStack original = originalItems.remove(frameId);
        if (original == null) return;
        // Find the frame entity across all worlds
        for (var world : Bukkit.getWorlds()) {
            var entity = world.getEntity(frameId);
            if (entity instanceof ItemFrame f && f.isValid() && !f.isDead()) {
                f.setItem(original, false);
                return;
            }
        }
    }

    public void shutdown() {
        // Cancel all pending tasks (deduplicate since group frames share the same task id)
        for (int taskId : new java.util.HashSet<>(revertTaskIds.values())) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        revertTaskIds.clear();
        swapGroups.clear();
        tileIndices.clear();
        activeSwapTargets.clear();
        for (Map.Entry<UUID, ItemStack> entry : new ArrayList<>(originalItems.entrySet())) {
            UUID frameId = entry.getKey();
            ItemStack original = entry.getValue();
            if (original == null) continue;
            for (var world : Bukkit.getWorlds()) {
                var entity = world.getEntity(frameId);
                if (entity instanceof ItemFrame f && f.isValid() && !f.isDead()) {
                    f.setItem(original, false);
                    break;
                }
            }
        }
        originalItems.clear();
    }

    // Set of map names currently being used as hover targets by active swaps.
    // While a map name is in this set, its frames are non-interactive.
    private final java.util.Set<String> activeSwapTargets = new java.util.HashSet<>();

    /**
     * Returns true if the given frame UUID is currently showing a swapped item
     * (its original item is saved and it's displaying the hover map).
     */
    public boolean isSwapped(UUID frameUuid) {
        return originalItems.containsKey(frameUuid);
    }

    /**
     * Returns true if the given map name is currently being used as a hover target
     * by an active swap. Used by ImageFrameResolver to suppress interactivity on
     * frames that are being "borrowed" as display targets.
     */
    public boolean isSwappedTarget(String mapName) {
        if (mapName == null) return false;
        return activeSwapTargets.contains(mapName.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Returns true if the given frame UUID belongs to a frame that is currently
     * acting as a swap target (its map name is in activeSwapTargets).
     * This is checked via the frame's current item — if the frame shows a map
     * whose name is an active swap target, suppress interactivity.
     * Note: this is intentionally NOT used — we check by map name at resolve time.
     */
    public boolean isSwappedTarget(UUID frameUuid) {
        // Not used directly; kept for potential future use.
        return false;
    }

    /**
     * Returns all ImageFrame map names that have the same tile count as the given source map,
     * sorted alphabetically. Used to populate the hover-map picker with only compatible images.
     */
    public List<String> getCompatibleMapNames(String sourceMapName) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            Object manager = imageMapManagerField.get(null);
            if (manager == null) return Collections.emptyList();

            // Get tile count of the source image
            int sourceTiles = -1;
            if (sourceMapName != null && !sourceMapName.isBlank()) {
                Object sourceImage = getImageByName(manager, sourceMapName);
                if (sourceImage != null) {
                    List<?> views = (List<?>) imageMapGetMapViewsMethod.invoke(sourceImage);
                    if (views != null) sourceTiles = views.size();
                }
            }

            List<String> names = new ArrayList<>();
            Iterable<?> maps = getManagerIterable(manager);
            if (maps != null) {
                for (Object map : maps) {
                    try {
                        Object name = imageMapGetNameMethod.invoke(map);
                        if (name == null || name.toString().isBlank()) continue;
                        String mapName = name.toString().trim();
                        // Skip the source map itself
                        if (mapName.equalsIgnoreCase(sourceMapName)) continue;
                        // If we know the source tile count, only include same-size maps
                        if (sourceTiles > 0) {
                            List<?> views = (List<?>) imageMapGetMapViewsMethod.invoke(map);
                            if (views == null || views.size() != sourceTiles) continue;
                        }
                        names.add(mapName);
                    } catch (Throwable ignored) {}
                }
            }
            Collections.sort(names);
            return names;
        } catch (Throwable t) {
            plugin.getLogger().warning("[interactiveimage] getCompatibleMapNames failed: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Returns all ImageFrame map names available on the server, sorted alphabetically.
     * Used to populate the hover-map picker GUI.
     */
    public List<String> getAllMapNames() {
        if (!isAvailable()) return Collections.emptyList();
        try {
            Object manager = imageMapManagerField.get(null);
            if (manager == null) return Collections.emptyList();

            List<String> names = new ArrayList<>();
            Iterable<?> maps = getManagerIterable(manager);
            if (maps != null) {
                for (Object map : maps) {
                    try {
                        Object name = imageMapGetNameMethod.invoke(map);
                        if (name != null && !name.toString().isBlank()) {
                            names.add(name.toString().trim());
                        }
                    } catch (Throwable ignored) {}
                }
            }
            Collections.sort(names);
            return names;
        } catch (Throwable t) {
            plugin.getLogger().warning("[interactiveimage] getAllMapNames failed: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Tries several strategies to get an iterable of all ImageMap objects from the manager.
     */
    private Iterable<?> getManagerIterable(Object manager) {
        // Strategy 1: cached managerValuesMethod
        if (managerValuesMethod != null) {
            try {
                Object result = managerValuesMethod.invoke(manager);
                if (result instanceof Iterable<?> it) return it;
            } catch (Throwable ignored) {}
        }

        // Strategy 2: scan all no-arg methods that return Collection/Iterable
        for (Method m : manager.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> ret = m.getReturnType();
            if (!Iterable.class.isAssignableFrom(ret) && !java.util.Collection.class.isAssignableFrom(ret)) continue;
            try {
                Object result = m.invoke(manager);
                if (result instanceof Iterable<?> it) {
                    // Verify it contains ImageMap objects by checking the first element
                    java.util.Iterator<?> iter = it.iterator();
                    if (!iter.hasNext()) continue;
                    Object first = iter.next();
                    // Check it has a getName() method
                    try {
                        first.getClass().getMethod("getName");
                        // Cache for next time
                        managerValuesMethod = m;
                        return it;
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        // Strategy 3: look for a field that holds a Map<?, ImageMap> and return its values
        for (java.lang.reflect.Field f : manager.getClass().getDeclaredFields()) {
            if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object val = f.get(manager);
                if (val instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
                    Object first = map.values().iterator().next();
                    try {
                        first.getClass().getMethod("getName");
                        return map.values();
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void cancelRevertTask(UUID frameId) {
        Integer taskId = revertTaskIds.remove(frameId);
        if (taskId != null) Bukkit.getScheduler().cancelTask(taskId);
    }

    private boolean isAvailable() {
        if (available) return true;
        if (initTried) return false;
        initTried = true;

        if (Bukkit.getPluginManager().getPlugin("ImageFrame") == null) {
            available = false;
            return false;
        }

        try {
            Class<?> imageFrameClass = Class.forName("com.loohp.imageframe.ImageFrame");
            imageMapManagerField = imageFrameClass.getDeclaredField("imageMapManager");
            imageMapManagerField.setAccessible(true);

            Class<?> managerClass = Class.forName("com.loohp.imageframe.objectholders.ImageMapManager");
            try {
                getFromNameMethod = managerClass.getMethod("getFromName", String.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                managerValuesMethod = managerClass.getMethod("values");
            } catch (NoSuchMethodException ignored) {}

            Class<?> imageMapClass = Class.forName("com.loohp.imageframe.objectholders.ImageMap");
            imageMapGetMapViewsMethod = imageMapClass.getMethod("getMapViews");
            imageMapGetNameMethod    = imageMapClass.getMethod("getName");

            available = true;
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[interactiveimage] ImageSwapManager failed to hook ImageFrame API: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            available = false;
            return false;
        }
    }

    /**
     * Computes the tile index of this frame within its source image.
     * Called once at focus time using the original (pre-swap) item.
     */
    private int computeTileIndex(ItemFrame frame, ItemStack originalItem) {
        try {
            if (originalItem == null || !(originalItem.getItemMeta() instanceof MapMeta mm)) return 0;
            MapView currentView = mm.getMapView();
            if (currentView == null) return 0;
            Object manager = imageMapManagerField.get(null);
            if (manager == null) return 0;
            Object sourceImage = getImageByMapView(manager, currentView);
            if (sourceImage == null) return 0;
            List<?> sourceViews = (List<?>) imageMapGetMapViewsMethod.invoke(sourceImage);
            if (sourceViews == null) return 0;
            for (int i = 0; i < sourceViews.size(); i++) {
                if (sourceViews.get(i) instanceof MapView sv && sv.getId() == currentView.getId()) {
                    return i;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * Gets the MapView at the given tile index from the named target image.
     */
    private MapView findMatchingMapViewByIndex(String targetImageName, int tileIndex) {
        try {
            Object manager = imageMapManagerField.get(null);
            if (manager == null) return null;
            Object targetImage = getImageByName(manager, targetImageName);
            if (targetImage == null) return null;
            List<?> targetViews = (List<?>) imageMapGetMapViewsMethod.invoke(targetImage);
            if (targetViews == null || targetViews.isEmpty()) return null;
            int idx = tileIndex < targetViews.size() ? tileIndex : 0;
            Object view = targetViews.get(idx);
            return view instanceof MapView mv ? mv : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getImageByName(Object manager, String name) {
        try {
            if (getFromNameMethod != null) {
                Object result = getFromNameMethod.invoke(manager, name);
                if (result != null) return result;
            }
            // Fallback: iterate all maps
            return iterateFindByName(manager, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getImageByMapView(Object manager, MapView mapView) {
        // Use getFromMapView if available (same reflection as ImageFrameResolver)
        try {
            Class<?> managerClass = manager.getClass();
            Method m = managerClass.getMethod("getFromMapView", MapView.class);
            return m.invoke(manager, mapView);
        } catch (Throwable ignored) {}
        return null;
    }

    private Object iterateFindByName(Object manager, String name) {
        try {
            Iterable<?> maps = getManagerIterable(manager);
            if (maps == null) return null;
            for (Object map : maps) {
                try {
                    Object n = imageMapGetNameMethod.invoke(map);
                    if (name.equalsIgnoreCase(n == null ? null : n.toString().trim())) {
                        return map;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
