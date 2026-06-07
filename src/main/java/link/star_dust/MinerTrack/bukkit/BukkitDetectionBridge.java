package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.CoreConfig;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.MaterialMapper;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bukkit implementation of DetectionBridge.
 *
 * <p>Block names returned from {@link #getBlockType(String, int, int, int)}
 * are always in the canonical Minecraft namespace format
 * ({@code minecraft:diamond_ore}), regardless of the underlying Bukkit
 * {@code Material} enum value. Internal calls that still use Bukkit enums
 * (e.g. {@code Material.WATER}) are translated via {@link MaterialMapper}
 * to keep the comparison path platform-neutral.
 */
public class BukkitDetectionBridge implements DetectionBridge {
    private final PluginAdapter adapter;
    private final YamlLoader loader;
    private final Map<UUID, Map<CommonLocation, Long>> placedBlocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Map<CommonLocation, Long>> brokenAir = new java.util.concurrent.ConcurrentHashMap<>();

    public BukkitDetectionBridge(PluginAdapter adapter, YamlLoader loader) {
        this.adapter = adapter;
        this.loader = loader;
        active = this;
    }

    @Override
    public String getBlockType(String world, int x, int y, int z) {
        try {
            // The core layer passes canonical dimension ids (e.g.
            // `minecraft:overworld`) to this method, but Bukkit's
            // `getWorld` only knows about world folder names (`world`,
            // `world_nether`, ...). Without the resolveWorld() fallback
            // below, every lookup against a canonical id would silently
            // return AIR, breaking:
            //   - `EnvironmentAnalyzer.isInNaturalEnvironment` (everything
            //     counted as air → airCount > threshold → "natural" → VL
            //     increment branch never ran);
            //   - `DetectionEngine.getVeinLocations` (no ore could be
            //     found → vein count never increased);
            //   - `isWaterStill` (the water block couldn't be found).
            org.bukkit.World w = resolveWorld(world);
            if (w == null) return BlockId.AIR;
            Block b = w.getBlockAt(x, y, z);
            return MaterialMapper.bukkitToMinecraft(b.getType().name());
        } catch (Exception e) {
            return BlockId.AIR;
        }
    }

    /**
     * Resolve a world identifier to a live Bukkit {@link org.bukkit.World}.
     * Accepts both:
     * <ul>
     *   <li>Bukkit folder names (e.g. {@code world}, {@code world_nether}) —
     *       looked up directly via {@link Bukkit#getWorld(String)}.</li>
     *   <li>Canonical Minecraft dimension ids (e.g.
     *       {@code minecraft:overworld}, {@code minecraft:the_nether}) —
     *       matched by iterating {@link Bukkit#getWorlds()} and comparing
     *       the world's {@link org.bukkit.World.Environment}.</li>
     * </ul>
     * Returns {@code null} when no matching world is loaded. This is the
     * canonical world-resolution helper used by every method in this
     * bridge that needs a live world (block lookups, water checks, etc.).
     */
    private org.bukkit.World resolveWorld(String worldKey) {
        if (worldKey == null) return null;
        // Fast path: the caller passed a folder name (the original
        // Bukkit convention). `Bukkit.getWorld` will resolve it
        // directly and is the only way to handle server-installed
        // worlds whose folder names don't match the default vanilla
        // convention (e.g. a flatland plugin that mounts a custom
        // dimension with its own folder name).
        org.bukkit.World direct = Bukkit.getWorld(worldKey);
        if (direct != null) return direct;
        // Fallback: the caller passed a canonical dimension id
        // (`minecraft:overworld` etc.) — translate to a folder-name
        // match by walking every loaded world's environment.
        org.bukkit.World.Environment env = environmentForDimensionId(worldKey);
        if (env == null) return null;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == env) return w;
        }
        return null;
    }

    /**
     * Inverse of {@link #resolveDimensionId(String)}: map a canonical
     * Minecraft dimension id to its Bukkit {@link World.Environment}.
     * Returns {@code null} when the input is not a recognised dimension
     * id.
     */
    private org.bukkit.World.Environment environmentForDimensionId(String dimId) {
        if (dimId == null) return null;
        String norm = link.star_dust.MinerTrack.common.DimensionId.normalize(dimId);
        if (norm == null) return null;
        if (norm.equals(link.star_dust.MinerTrack.common.DimensionId.OVERWORLD)) {
            return org.bukkit.World.Environment.NORMAL;
        }
        if (norm.equals(link.star_dust.MinerTrack.common.DimensionId.THE_NETHER)) {
            return org.bukkit.World.Environment.NETHER;
        }
        if (norm.equals(link.star_dust.MinerTrack.common.DimensionId.THE_END)) {
            return org.bukkit.World.Environment.THE_END;
        }
        return null;
    }

    @Override
    public boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = placedBlocks.get(playerId);
        if (map == null) return false;
        Long ts = map.get(location);
        if (ts == null) return false;
        // Expire after trace-remove time (minutes -> ms)
        int expireMs = getConfigForWorld(resolveDimensionId(location.world), "xray.trace_remove", 15) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) {
            map.remove(location);
            return false;
        }
        return true;
    }

    @Override
    public String resolveDimensionId(String worldName) {
        // Authoritative source: the live Bukkit World's environment. This
        // works regardless of how the server admin named the world
        // folders (world / world_nether / world_the_end on vanilla;
        // custom names on modded servers).
        try {
            org.bukkit.World w = Bukkit.getWorld(worldName);
            if (w != null) {
                switch (w.getEnvironment()) {
                    case NETHER:  return link.star_dust.MinerTrack.common.DimensionId.THE_NETHER;
                    case THE_END: return link.star_dust.MinerTrack.common.DimensionId.THE_END;
                    case NORMAL:
                    default:      return link.star_dust.MinerTrack.common.DimensionId.OVERWORLD;
                }
            }
        } catch (Throwable ignored) {
            // World may be unloaded (shutdown, async tick). Fall through.
        }
        return link.star_dust.MinerTrack.common.DimensionId.normalize(worldName);
    }

    @Override
    public Object getConfig(String path) {
        // Load from data folder config.yml on demand
        return loadConfig().get(path);
    }

    @Override
    public int getConfigInt(String path, int def) {
        return loadConfig().getInt(path, def);
    }

    @Override
    public boolean getConfigBoolean(String path, boolean def) {
        return loadConfig().getBoolean(path, def);
    }

    @Override
    public double getConfigDouble(String path, double def) {
        return loadConfig().getDouble(path, def);
    }

    @Override
    public List<String> getConfigStringList(String path) {
        return loadConfig().getStringList(path);
    }

    private CommonYaml configCache;
    private long configCacheTime;
    private CoreConfig coreConfig;

    // Static reference so the PluginAdapter can call back into the active bridge
    // during reloadConfig() without depending on ServicesManager ceremony.
    private static volatile BukkitDetectionBridge active;

    public static BukkitDetectionBridge getActive() { return active; }

    /** Invalidate the config cache so the next access re-reads from disk. */
    public void clearConfigCache() {
        configCache = null;
        configCacheTime = 0;
    }

    private CommonYaml loadConfig() {
        long now = System.currentTimeMillis();
        if (configCache != null && now - configCacheTime < 5000) return configCache;
        File configFile = new File(adapter.getDataFolder(), "config.yml");
        configCache = link.star_dust.MinerTrack.core.config.ConfigMerger.loadAndMerge(configFile, "config.yml", adapter, loader);
        configCacheTime = now;
        return configCache;
    }

    @Override
    public CoreConfig loadGroupConfigs() {
        link.star_dust.MinerTrack.core.config.GroupConfigLoader loader =
                new link.star_dust.MinerTrack.core.config.GroupConfigLoader(adapter, loadConfig(), this.loader);
        link.star_dust.MinerTrack.core.config.GroupConfigLoader.GroupLoadResult r = loader.load();
        coreConfig = new CoreConfig();
        coreConfig.setMainConfig(loadConfig());
        coreConfig.setGroupConfigs(r.groupConfigs);
        coreConfig.setWorldToGroup(r.worldToGroup);
        coreConfig.setGroupWorldPatterns(r.groupWorldPatterns);
        coreConfig.setDefaultUnnamedGroupKey(r.defaultUnnamedGroupKey);
        return coreConfig;
    }

    @Override
    public CoreConfig getCoreConfig() {
        if (coreConfig == null) loadGroupConfigs();
        return coreConfig;
    }

    @Override
    public int getConfigForWorld(String worldName, String path, int def) {
        // worldName is the canonical minecraft:xxx dimension id (the
        // MiningListener resolves folder names via resolveDimensionId()
        // before passing to the core). Delegate to CoreConfig so the
        // resolved group config (overworld.yml / nether.yml / end.yml)
        // is the source of truth.
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getIntForWorld(worldName, path, def);
        return loadConfig().getInt(path, def);
    }

    @Override
    public boolean getConfigForWorldBoolean(String worldName, String path, boolean def) {
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getBooleanForWorld(worldName, path, def);
        return loadConfig().getBoolean(path, def);
    }

    @Override
    public List<String> getConfigForWorldStringList(String worldName, String path) {
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getStringListForWorld(worldName, path);
        return loadConfig().getStringList(path);
    }

    @Override
    public boolean isWorldDetectionEnabled(String worldName) {
        // Per-world enable flag lives in the resolved group config
        // (overworld.yml / nether.yml / end.yml), not in the main
        // xray.worlds mapping. Fall back to the main config's xray.enable
        // for legacy files that still surface the flag there.
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getBooleanForWorld(worldName, "xray.enable", false);
        return loadConfig().getBoolean("xray.enable", false);
    }

    @Override
    public int getWorldMaxHeight(String worldName) {
        // Per-world max-height is declared in the group config file
        // (overworld.yml: `max-height: 32`, nether.yml: `max-height: 128`).
        // -1 means "no limit" (use the world build height instead).
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getIntForWorld(worldName, "xray.max-height", -1);
        return loadConfig().getInt("xray.max-height", -1);
    }

    @Override
    public List<String> getRareOres(String worldName) {
        // worldName is the canonical minecraft:xxx dimension id; resolve
        // through CoreConfig so the group config (overworld.yml,
        // nether.yml, …) is the source of truth and the list is normalised.
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getStringListForWorld(worldName, "xray.rare-ores");
        // Fallback (pre-group-config state): use the main config and
        // normalise manually.
        List<String> raw = loadConfig().getStringList("xray.rare-ores");
        java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
        for (String s : raw) {
            String n = BlockId.normalize(s);
            out.add(n != null ? n : s);
        }
        return out;
    }

    @Override
    public int getTraceRemoveTime(String worldName) {
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getIntForWorld(worldName, "xray.trace_remove", 15);
        return loadConfig().getInt("xray.trace_remove", 15);
    }

    @Override
    public int getArtificialAirRemoveTime(String worldName) {
        int def = loadConfig().getInt("xray.natural-detection.cave.air-monitor.remove-time", 20);
        CoreConfig cc = getCoreConfig();
        if (cc != null) {
            return cc.getIntForWorld(worldName, "xray.natural-detection.cave.artificial-air-remove-time", def);
        }
        return loadConfig().getInt("xray.natural-detection.cave.artificial-air-remove-time", def);
    }

    @Override
    public boolean isArtificialAir(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = brokenAir.get(playerId);
        if (map == null) return false;
        Long ts = map.get(location);
        if (ts == null) return false;
        int expireMs = getArtificialAirRemoveTime(resolveDimensionId(location.world)) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) {
            map.remove(location);
            return false;
        }
        return true;
    }

    @Override
    public boolean isWaterStill(String world, int x, int y, int z) {
        try {
            // The core layer passes a canonical dimension id
            // (`minecraft:xxx`) here as well, so we must use the same
            // resolveWorld() helper as getBlockType() — otherwise the
            // check-running-water feature silently does nothing because
            // Bukkit can never find the world.
            org.bukkit.World w = resolveWorld(world);
            if (w == null) return false;
            Block b = w.getBlockAt(x, y, z);
            if (b.getType() != Material.WATER) return false;
            return b.getBlockData() instanceof Levelled && ((Levelled) b.getBlockData()).getLevel() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Block tracking helpers (called from MiningListener) ---

    public void trackPlacedBlock(UUID playerId, CommonLocation location) {
        placedBlocks.computeIfAbsent(playerId, k -> new java.util.HashMap<>())
            .put(location, System.currentTimeMillis());
    }

    public void trackBrokenAir(UUID playerId, CommonLocation location) {
        brokenAir.computeIfAbsent(playerId, k -> new java.util.HashMap<>())
            .put(location, System.currentTimeMillis());
    }

    public void clearPlayerTracking(UUID playerId) {
        placedBlocks.remove(playerId);
        brokenAir.remove(playerId);
    }
}
