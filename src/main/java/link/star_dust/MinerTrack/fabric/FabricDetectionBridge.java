package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CoreConfig;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.DimensionId;
import link.star_dust.MinerTrack.common.YamlLoader;
import link.star_dust.MinerTrack.core.config.GroupConfigLoader;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric DetectionBridge. Block lookups via Fabric server's World API (no Mixin).
 * Block IDs are already minecraft:xxx canonical format.
 */
public class FabricDetectionBridge implements DetectionBridge {
    private static volatile FabricDetectionBridge active;
    public static FabricDetectionBridge getActive() { return active; }

    private final FabricAdapter adapter;
    private final YamlLoader loader;
    private final Map<UUID, Map<CommonLocation, Long>> placedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<CommonLocation, Long>> brokenAir = new ConcurrentHashMap<>();
    private final Map<String, Object> dimensionToWorld = new ConcurrentHashMap<>();
    private volatile link.star_dust.MinerTrack.core.detection.MiningCore miningCore;
    private CoreConfig coreConfig;
    private volatile boolean configLoaded = false;

    public FabricDetectionBridge(FabricAdapter adapter, YamlLoader loader) {
        this.adapter = adapter;
        this.loader = loader;
        active = this;
        registerWorldEventHandlers();
    }

    public void setMiningCore(link.star_dust.MinerTrack.core.detection.MiningCore miningCore) {
        this.miningCore = miningCore;
    }

    private void ensureConfigLoaded() {
        if (configLoaded) return;
        synchronized (this) {
            if (configLoaded) return;
            this.coreConfig = loadGroupConfigs();
            this.configLoaded = true;
        }
    }

    // ── World event registration ─────────────────────────────────────

    private void registerWorldEventHandlers() {
        // Seed the registry once the server is up. Fabric fires
        // SERVER_STARTED exactly once per server boot, after all
        // startup-time worlds are loaded.
        FabricEventBus.registerServerStarted(server -> {
            // Cache the server instance — MC 26.1+ has no static getServer().
            FabricReflection.setCachedServer(server);
            try {
                // 1.18.2+: getAllLevels(); also try getWorlds() for older mappings
                Object worlds = FabricReflection.callAny(server, "getAllLevels", new Class<?>[0], new Object[0]);
                if (worlds == null || !(worlds instanceof Iterable)) {
                    worlds = FabricReflection.callAny(server, "getWorlds", new Class<?>[0], new Object[0]);
                }
                if (worlds instanceof Iterable) {
                    for (Object w : (Iterable<?>) worlds) {
                        registerWorld(w);
                    }
                }
            } catch (Throwable t) {
                adapter.warning("Failed to enumerate startup worlds: " + t.getMessage());
            }
        });

        FabricEventBus.registerServerWorldLoad(args -> registerWorld(args[1]));
        FabricEventBus.registerServerWorldUnload(args -> unregisterWorld(args[1]));
    }

    private void registerWorld(Object world) {
        if (world == null) return;
        String dimId = dimensionIdForWorld(world);
        if (dimId != null) dimensionToWorld.put(dimId, world);
    }

    private void unregisterWorld(Object world) {
        if (world == null) return;
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : dimensionToWorld.entrySet()) {
            if (e.getValue() == world) toRemove.add(e.getKey());
        }
        for (String k : toRemove) dimensionToWorld.remove(k);
    }

    private String dimensionIdForWorld(Object world) {
        if (world == null) return null;
        try {
            // MC 26.1+: Level.dimension(); 1.18-1.21: Level.getRegistryKey()
            Object registryKey = FabricReflection.callDimension(world);
            if (registryKey == null) return null;
            // ResourceKey.toString() in 1.18.2:
            //   "ResourceKey[minecraft:dimension / minecraft:overworld]"
            //   The actual dimension ID is the SECOND token (after " / ").
            String s = registryKey.toString();
            int start = s.indexOf('[');
            int slash = s.indexOf(" / ");
            if (start >= 0 && slash > start) {
                int end = s.indexOf(']', slash);
                if (end > slash) return s.substring(slash + 3, end).trim();
                return s.substring(slash + 3).trim();
            }
            // fallback: try getValue()
            Object val = FabricReflection.callAny(registryKey, "getValue",
                FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            return val == null ? null : FabricReflection.readString(val);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── DetectionBridge surface ──────────────────────────────────────

    @Override
    public String getBlockType(String world, int x, int y, int z) {
        try {
            Object w = resolveWorld(world);
            if (w == null) return BlockId.AIR;
            // Build a BlockPos via reflection. {@code BlockPos(int, int, int)}
            // is a stable public constructor on every 1.18+ version.
            // The class moved from net.minecraft.util.math.BlockPos to
            // net.minecraft.core.BlockPos in MC 26.1+; FabricReflection.forName
            // handles the migration automatically.
            Object pos = FabricReflection.newInstance("net.minecraft.core.BlockPos",
                new Class<?>[]{int.class, int.class, int.class},
                new Object[]{x, y, z});
            if (pos == null) return BlockId.AIR;
            Object state = FabricReflection.call(w, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos});
            if (state == null) return BlockId.AIR;
            // BlockState.getBlock() → Block (redirected via constants)
            Object block = FabricReflection.callAny(state, "getBlock", new Class<?>[0], new Object[0]);
            if (block == null) return BlockId.AIR;
            String id = FabricReflection.getBlockId(block);
            if (id != null && !id.isEmpty()) return id;
            // Fallback: Block.toString() → "Block{minecraft:diorite}"
            String s = block.toString();
            int brace = s.indexOf('{');
            int close = s.indexOf('}');
            if (brace >= 0 && close > brace) return s.substring(brace + 1, close);
            return BlockId.AIR;
        } catch (Exception e) {
            return BlockId.AIR;
        }
    }

    @Override
    public boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = placedBlocks.get(playerId);
        if (map == null) return false;
        Long ts = map.get(location);
        if (ts == null) return false;
        int expireMs = getConfigForWorld(location.world, "xray.trace_remove", 15) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) {
            map.remove(location);
            return false;
        }
        return true;
    }

    @Override
    public String resolveDimensionId(String worldName) {
        // On Fabric, the world key IS the canonical id, but the
        // path may also pass a Bukkit-style folder name. Normalise
        // via the platform-neutral {@link DimensionId#normalize}
        // so the same `world`, `nether`, `the_end` shortcuts the
        // Bukkit path recognises also work on Fabric (defensive).
        return DimensionId.normalize(worldName);
    }

    @Override
    public Object getConfig(String path) {
        ensureConfigLoaded();
        return coreConfig.getMainConfig().get(path);
    }

    @Override
    public int getConfigInt(String path, int def) {
        ensureConfigLoaded();
        return coreConfig.getMainConfig().getInt(path, def);
    }

    @Override
    public boolean getConfigBoolean(String path, boolean def) {
        ensureConfigLoaded();
        return coreConfig.getMainConfig().getBoolean(path, def);
    }

    @Override
    public double getConfigDouble(String path, double def) {
        ensureConfigLoaded();
        return coreConfig.getMainConfig().getDouble(path, def);
    }

    @Override
    public List<String> getConfigStringList(String path) {
        ensureConfigLoaded();
        return coreConfig.getMainConfig().getStringList(path);
    }

    @Override
    public int getConfigForWorld(String worldName, String path, int def) {
        ensureConfigLoaded();
        return coreConfig.getIntForWorld(worldName, path, def);
    }

    @Override
    public boolean getConfigForWorldBoolean(String worldName, String path, boolean def) {
        ensureConfigLoaded();
        return coreConfig.getBooleanForWorld(worldName, path, def);
    }

    @Override
    public List<String> getConfigForWorldStringList(String worldName, String path) {
        ensureConfigLoaded();
        return coreConfig.getStringListForWorld(worldName, path);
    }

    @Override
    public boolean isWorldDetectionEnabled(String worldName) {
        return getConfigForWorldBoolean(worldName, "xray.enable", true);
    }

    @Override
    public int getWorldMaxHeight(String worldName) {
        return getConfigForWorld(worldName, "xray.max-height", 32);
    }

    @Override
    public List<String> getRareOres(String worldName) {
        return getConfigForWorldStringList(worldName, "xray.rare-ores");
    }

    @Override
    public int getTraceRemoveTime(String worldName) {
        return getConfigForWorld(worldName, "xray.trace_remove", 15);
    }

    @Override
    public int getArtificialAirRemoveTime(String worldName) {
        return getConfigForWorld(worldName, "xray.natural-detection.cave.artificial-air-remove-time", 30);
    }

    @Override
    public boolean isArtificialAir(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = brokenAir.get(playerId);
        if (map == null) return false;
        return map.containsKey(location);
    }

    @Override
    public boolean isWaterStill(String world, int x, int y, int z) {
        try {
            Object w = resolveWorld(world);
            if (w == null) return false;
            Object pos = FabricReflection.newInstance("net.minecraft.core.BlockPos",
                new Class<?>[]{int.class, int.class, int.class},
                new Object[]{x, y, z});
            if (pos == null) return false;
            Object state = FabricReflection.call(w, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos});
            if (state == null) return false;
            // Check if the block is a LiquidBlock (water still) by
            // walking the class hierarchy. The Fabric API exposes
            // LiquidBlock as a stable type on every 1.18+ server.
            Class<?> fluidBlockCls = FabricReflection.forName("net.minecraft.world.level.block.LiquidBlock");
            Class<?> blocksCls = FabricReflection.forName("net.minecraft.world.level.block.Blocks");
            Class<?> blockCls = FabricReflection.forName("net.minecraft.world.level.block.Block");
            Object block = FabricReflection.callAny(state, "getBlock", new Class<?>[0], new Object[0]); // BlockState.getBlock()
            if (block == null || blockCls == null) return false;
            if (fluidBlockCls != null && fluidBlockCls.isInstance(block)) {
                Object fluidState = FabricReflection.callAny(state, "getFluidState", new Class<?>[0], new Object[0]);
                if (fluidState == null) return false;
                Object fluid = FabricReflection.callAny(fluidState, "getFluid", new Class<?>[0], new Object[0]);
                if (fluid == null) return false;
                Object water = FabricReflection.getField(FabricReflection.forName("net.minecraft.world.level.material.Fluids"), "WATER");
                if (water == null) return false;
                Object still = FabricReflection.callAny(water, "getStill", new Class<?>[0], new Object[0]);
                return fluid.equals(water) || (still != null && fluid.equals(still));
            }
            // Fallback: literal Blocks.WATER comparison.
            if (blocksCls == null) return false;
            Object waterBlock = FabricReflection.getField(blocksCls, "WATER");
            return waterBlock != null && waterBlock.equals(block);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void trackPlacedBlock(UUID playerId, CommonLocation location) {
        placedBlocks.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(location, System.currentTimeMillis());
    }

    @Override
    public void trackBrokenAir(UUID playerId, CommonLocation location) {
        brokenAir.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(location, System.currentTimeMillis());
    }

    @Override
    public void clearPlayerTracking(UUID playerId) {
        placedBlocks.remove(playerId);
        brokenAir.remove(playerId);
    }

    @Override
    public void clearPlayerPath(UUID playerId) {
        link.star_dust.MinerTrack.core.detection.MiningCore mc = this.miningCore;
        if (mc != null && mc.getState() != null) {
            try {
                mc.getState().clearPlayerPath(playerId);
            } catch (Throwable t) {
                adapter.info("Failed to clear player path: " + t.getMessage());
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: resolves the player by UUID via the
     * {@code PlayerList}/{@code PlayerManager} reflection path, then
     * checks the fabric-permissions-api (LuckPerms) first and falls back
     * to the vanilla op-level / operator check.  Returns {@code false}
     * when the player is not online or the server instance is unavailable.
     */
    @Override
    public boolean hasPermission(UUID playerId, String node) {
        try {
            Object server = FabricReflection.getServer();
            if (server == null) return false;
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return false;
            Object player = FabricReflection.call(pm, "getPlayerByUUID",
                new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return false;
            // 1) Try fabric-permissions-api (LuckPerms integration)
            //    FabricCommandBridge.checkLPPermission is the same utility
            //    used by FabricCommandBridge; it iterates the registered
            //    permission providers and returns true on first match.
            if (FabricCommandBridge.checkLPPermission(player, node, 2)) return true;
            // 2) Fall back to vanilla op-level check.  The command source
            //    stack is a Player object on Fabric, so the op-level
            //    resolution in FabricCommandBridge.checkVanillaOpLevel can
            //    be reused directly (player is the source object).
            return FabricCommandBridge.checkVanillaOpLevel(player, 2);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void clearConfigCache() {
        configLoaded = false;
    }

    @Override
    public CoreConfig loadGroupConfigs() {
        File configDir = new File(adapter.getDataFolder(), "Configuration");
        if (!configDir.exists()) //noinspection ResultOfMethodCallIgnored
            configDir.mkdirs();
        File mainConfigFile = new File(adapter.getDataFolder(), "config.yml");
        link.star_dust.MinerTrack.common.CommonYaml mainConfig = this.loader.loadFile(mainConfigFile);
        GroupConfigLoader gcl = new GroupConfigLoader(adapter, mainConfig, this.loader);
        link.star_dust.MinerTrack.core.config.GroupConfigLoader.GroupLoadResult result = gcl.load();

        CoreConfig cc = new CoreConfig();
        cc.setMainConfig(mainConfig);
        cc.setGroupConfigs(result.groupConfigs);
        cc.setWorldToGroup(result.worldToGroup);
        cc.setGroupWorldPatterns(result.groupWorldPatterns);
        cc.setDefaultUnnamedGroupKey(result.defaultUnnamedGroupKey);
        // Publish to the bridge's cached state so subsequent
        // lazy-load callers don't reload again. Use a short
        // synchronized block to ensure visibility across
        // threads.
        synchronized (this) {
            this.coreConfig = cc;
            this.configLoaded = true;
        }
        return cc;
    }

    @Override
    public CoreConfig getCoreConfig() {
        ensureConfigLoaded();
        return coreConfig;
    }

    // ── World resolution ─────────────────────────────────────────────

    private Object resolveWorld(String worldKey) {
        if (worldKey == null) return null;
        Object registered = dimensionToWorld.get(worldKey);
        if (registered != null) return registered;
        try {
            Object server = FabricReflection.getServer();
            if (server == null) return null;
            // MC 26.1+: getAllLevels(); 1.18-1.21: getWorlds()
            Object worlds = FabricReflection.callAny(server, "getAllLevels", new Class<?>[0], new Object[0]);
            if (worlds == null || !(worlds instanceof Iterable)) {
                worlds = FabricReflection.callAny(server, "getWorlds", new Class<?>[0], new Object[0]);
            }
            if (!(worlds instanceof Iterable)) return null;
            for (Object w : (Iterable<?>) worlds) {
                Object registryKey = FabricReflection.callDimension(w);
                if (registryKey == null) continue;
                // ResourceKey.toString() → "ResourceKey[minecraft:dimension / minecraft:overworld]"
                String s = registryKey.toString();
                int start = s.indexOf('[');
                int slash = s.indexOf(" / ");
                String dim = null;
                if (start >= 0 && slash > start) {
                    int end = s.indexOf(']', slash);
                    dim = (end > slash) ? s.substring(slash + 3, end).trim() : s.substring(slash + 3).trim();
                }
                if (dim == null) {
                    Object val = FabricReflection.callAny(registryKey, "getValue", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                    if (val != null) dim = FabricReflection.readString(val);
                }
                if (dim != null && worldKey.equalsIgnoreCase(dim)) {
                    return w;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ── Block registry resolution ────────────────────────────────────
    // (Block-id resolution is now handled by FabricReflection.getBlockId(),
    // which uses DefaultedRegistry.getKey(T) / MappedRegistry.getKey(T)
    // and falls back to the builtInRegistryHolder chain.)
}
