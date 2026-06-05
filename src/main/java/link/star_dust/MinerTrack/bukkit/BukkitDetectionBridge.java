package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.PluginAdapter;
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
 */
public class BukkitDetectionBridge implements DetectionBridge {
    private final PluginAdapter adapter;
    private final Map<UUID, Map<CommonLocation, Long>> placedBlocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Map<CommonLocation, Long>> brokenAir = new java.util.concurrent.ConcurrentHashMap<>();

    public BukkitDetectionBridge(PluginAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public String getBlockType(String world, int x, int y, int z) {
        try {
            org.bukkit.World w = Bukkit.getWorld(world);
            if (w == null) return "AIR";
            Block b = w.getBlockAt(x, y, z);
            return b.getType().name();
        } catch (Exception e) {
            return "AIR";
        }
    }

    @Override
    public boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = placedBlocks.get(playerId);
        if (map == null) return false;
        Long ts = map.get(location);
        if (ts == null) return false;
        // Expire after trace-remove time (minutes -> ms)
        int expireMs = getConfigForWorld(location.world, "xray.trace_remove", 15) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) {
            map.remove(location);
            return false;
        }
        return true;
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

    private org.bukkit.configuration.file.YamlConfiguration configCache;
    private long configCacheTime;

    /** Invalidate the config cache so the next access re-reads from disk. */
    public void clearConfigCache() {
        configCache = null;
        configCacheTime = 0;
    }

    private org.bukkit.configuration.file.YamlConfiguration loadConfig() {
        long now = System.currentTimeMillis();
        if (configCache != null && now - configCacheTime < 5000) return configCache;
        File configFile = new File(adapter.getDataFolder(), "config.yml");
        configCache = ConfigMerger.loadAndMerge(configFile, "config.yml", (BukkitAdapter) adapter);
        configCacheTime = now;
        return configCache;
    }

    @Override // DetectionBridge.mergeGroupConfigs
    public void mergeGroupConfigs(link.star_dust.MinerTrack.common.PluginAdapter adapter) {
        File configDir = new File(adapter.getDataFolder(), "Configuration");
        if (!configDir.exists()) configDir.mkdirs();

        // Ensure missing group files referenced in xray.worlds exist
        try {
            Object worldsObj = loadConfig().get("xray.worlds");
            if (worldsObj instanceof org.bukkit.configuration.ConfigurationSection) {
                org.bukkit.configuration.ConfigurationSection worldsSection =
                        (org.bukkit.configuration.ConfigurationSection) worldsObj;
                for (String fileKey : worldsSection.getKeys(false)) {
                    String groupKey = fileKey;
                    if (groupKey.toLowerCase().endsWith(".yml")) groupKey = groupKey.substring(0, groupKey.length() - 4);
                    String filename = groupKey + ".yml";
                    File out = new File(configDir, filename);
                    if (!out.exists()) {
                        try {
                            adapter.saveResource("Configuration/" + filename, false);
                        } catch (Exception e) {
                            // fallback: copy overworld.yml
                            try (java.io.InputStream is = adapter.getResource("Configuration/overworld.yml")) {
                                if (is != null) {
                                    try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                                        byte[] buf = new byte[8192];
                                        int r;
                                        while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Merge every group file in Configuration/ with its JAR resource default
        File[] files = configDir.listFiles((d, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                try {
                    ConfigMerger.loadAndMerge(f, "Configuration/" + f.getName(), adapter);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public int getConfigForWorld(String worldName, String path, int def) {
        // Delegate to ConfigEngine when available; for now fall back to config lookup
        return loadConfig().getInt(path, def);
    }

    @Override
    public boolean getConfigForWorldBoolean(String worldName, String path, boolean def) {
        return loadConfig().getBoolean(path, def);
    }

    @Override
    public List<String> getConfigForWorldStringList(String worldName, String path) {
        return loadConfig().getStringList(path);
    }

    @Override
    public boolean isWorldDetectionEnabled(String worldName) {
        return loadConfig().getBoolean("xray.worlds." + worldName + ".enable", false);
    }

    @Override
    public int getWorldMaxHeight(String worldName) {
        return loadConfig().getInt("xray.worlds." + worldName + ".max-height", -1);
    }

    @Override
    public List<String> getRareOres(String worldName) {
        return loadConfig().getStringList("xray.rare-ores");
    }

    @Override
    public int getTraceRemoveTime(String worldName) {
        return loadConfig().getInt("xray.trace_remove", 15);
    }

    @Override
    public int getArtificialAirRemoveTime(String worldName) {
        int def = loadConfig().getInt("xray.natural-detection.cave.air-monitor.remove-time", 20);
        return loadConfig().getInt("xray.natural-detection.cave.artificial-air-remove-time", def);
    }

    @Override
    public boolean isArtificialAir(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = brokenAir.get(playerId);
        if (map == null) return false;
        Long ts = map.get(location);
        if (ts == null) return false;
        int expireMs = getArtificialAirRemoveTime(location.world) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) {
            map.remove(location);
            return false;
        }
        return true;
    }

    @Override
    public boolean isWaterStill(String world, int x, int y, int z) {
        try {
            org.bukkit.World w = Bukkit.getWorld(world);
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
