/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 *
 *  Copyright (c) At87668 (Author87668) <https://github.com/At87668>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package link.star_dust.MinerTrack.forge;

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
 * Forge DetectionBridge. Block lookups via Forge server's World API.
 * Uses ForgeReflection for Minecraft access (Forge uses Mojang names).
 */
public class ForgeDetectionBridge implements DetectionBridge {
    private static volatile ForgeDetectionBridge active;
    public static ForgeDetectionBridge getActive() { return active; }

    private final ForgeAdapter adapter;
    private final YamlLoader loader;
    private final Map<UUID, Map<CommonLocation, Long>> placedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<CommonLocation, Long>> brokenAir = new ConcurrentHashMap<>();
    private final Map<String, Object> dimensionToWorld = new ConcurrentHashMap<>();
    private volatile link.star_dust.MinerTrack.core.detection.MiningCore miningCore;
    private CoreConfig coreConfig;
    private volatile boolean configLoaded = false;

    public ForgeDetectionBridge(ForgeAdapter adapter, YamlLoader loader) {
        this.adapter = adapter;
        this.loader = loader;
        active = this;
    }

    public void setMiningCore(link.star_dust.MinerTrack.core.detection.MiningCore miningCore) { this.miningCore = miningCore; }

    @Override public CoreConfig loadGroupConfigs() {
        synchronized (this) {
            File configDir = new File(adapter.getDataFolder(), "Configuration");
            if (!configDir.exists()) configDir.mkdirs();
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
            this.coreConfig = cc;
            this.configLoaded = true;
            return cc;
        }
    }

    @Override public CoreConfig getCoreConfig() { if (!configLoaded) loadGroupConfigs(); return coreConfig; }

    @Override public void clearConfigCache() { configLoaded = false; }

    private void ensureConfigLoaded() { if (!configLoaded) loadGroupConfigs(); }

    public void registerWorld(Object world) {
        if (world == null) return;
        String dimId = dimensionIdForWorld(world);
        if (dimId != null) dimensionToWorld.put(dimId, world);
    }

    public void unregisterWorld(Object world) {
        if (world == null) return;
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : dimensionToWorld.entrySet()) { if (e.getValue() == world) toRemove.add(e.getKey()); }
        for (String k : toRemove) dimensionToWorld.remove(k);
    }

    private String dimensionIdForWorld(Object world) {
        if (world == null) return null;
        try {
            Object registryKey = ForgeReflection.callDimension(world); if (registryKey == null) return null;
            String s = registryKey.toString(); int start = s.indexOf('['), slash = s.indexOf(" / ");
            if (start >= 0 && slash > start) { int end = s.indexOf(']', slash); if (end > slash) return s.substring(slash + 3, end).trim(); return s.substring(slash + 3).trim(); }
            Object val = ForgeReflection.callAny(registryKey, "getValue", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            return val == null ? null : ForgeReflection.readString(val);
        } catch (Throwable t) { return null; }
    }

    @Override public String getBlockType(String world, int x, int y, int z) {
        try {
            Object w = resolveWorld(world); if (w == null) return BlockId.AIR;
            Object pos = ForgeReflection.newInstance("net.minecraft.core.BlockPos", new Class<?>[]{int.class, int.class, int.class}, new Object[]{x, y, z});
            if (pos == null) return BlockId.AIR;
            Object state = ForgeReflection.call(w, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos});
            if (state == null) return BlockId.AIR;
            Object block = ForgeReflection.callAny(state, "getBlock", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (block == null) return BlockId.AIR;
            String id = ForgeReflection.getBlockId(block); if (id != null && !id.isEmpty()) return id;
            String s = block.toString(); int brace = s.indexOf('{'), close = s.indexOf('}'); if (brace >= 0 && close > brace) return s.substring(brace + 1, close);
            return BlockId.AIR;
        } catch (Exception e) { return BlockId.AIR; }
    }

    @Override public boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = placedBlocks.get(playerId); if (map == null) return false;
        Long ts = map.get(location); if (ts == null) return false;
        int expireMs = getConfigForWorld(location.world, "xray.trace_remove", 15) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) { map.remove(location); return false; } return true;
    }

    @Override public String resolveDimensionId(String worldName) { return DimensionId.normalize(worldName); }
    @Override public Object getConfig(String path) { ensureConfigLoaded(); return coreConfig.getMainConfig().get(path); }
    @Override public int getConfigInt(String path, int def) { ensureConfigLoaded(); return coreConfig.getMainConfig().getInt(path, def); }
    @Override public boolean getConfigBoolean(String path, boolean def) { ensureConfigLoaded(); return coreConfig.getMainConfig().getBoolean(path, def); }
    @Override public double getConfigDouble(String path, double def) { ensureConfigLoaded(); return coreConfig.getMainConfig().getDouble(path, def); }
    @Override public List<String> getConfigStringList(String path) { ensureConfigLoaded(); return coreConfig.getMainConfig().getStringList(path); }
    @Override public int getConfigForWorld(String worldName, String path, int def) { ensureConfigLoaded(); return coreConfig.getIntForWorld(worldName, path, def); }
    @Override public boolean getConfigForWorldBoolean(String worldName, String path, boolean def) { ensureConfigLoaded(); return coreConfig.getBooleanForWorld(worldName, path, def); }
    @Override public List<String> getConfigForWorldStringList(String worldName, String path) { ensureConfigLoaded(); return coreConfig.getStringListForWorld(worldName, path); }
    @Override public boolean isWorldDetectionEnabled(String worldName) { return getConfigForWorldBoolean(worldName, "xray.enable", true); }
    @Override public int getWorldMaxHeight(String worldName) { return getConfigForWorld(worldName, "xray.max-height", 32); }
    @Override public List<String> getRareOres(String worldName) { return getConfigForWorldStringList(worldName, "xray.rare-ores"); }
    @Override public int getTraceRemoveTime(String worldName) { return getConfigForWorld(worldName, "xray.trace_remove", 15); }
    @Override public int getArtificialAirRemoveTime(String worldName) { return getConfigForWorld(worldName, "xray.natural-detection.cave.artificial-air-remove-time", 30); }
    @Override public boolean isArtificialAir(UUID playerId, CommonLocation location) { Map<CommonLocation, Long> map = brokenAir.get(playerId); return map != null && map.containsKey(location); }
    @Override public boolean isWaterStill(String world, int x, int y, int z) {
        try {
            Object w = resolveWorld(world); if (w == null) return false;
            Object pos = ForgeReflection.newInstance("net.minecraft.core.BlockPos", new Class<?>[]{int.class, int.class, int.class}, new Object[]{x, y, z}); if (pos == null) return false;
            Object state = ForgeReflection.call(w, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos}); if (state == null) return false;
            Object block = ForgeReflection.callAny(state, "getBlock", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            Class<?> fluidBlockCls = ForgeReflection.forName("net.minecraft.world.level.block.LiquidBlock");
            if (block != null && fluidBlockCls != null && fluidBlockCls.isInstance(block)) {
                Object fluidState = ForgeReflection.callAny(w, "getFluidState", new Class<?>[]{pos.getClass()}, new Object[]{pos});
                if (fluidState != null) { Object fluid = ForgeReflection.callAny(fluidState, "getType", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (fluid != null) { Object still = ForgeReflection.callAny(fluid, "getSource", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); return still != null && still == fluid; } } return true;
            } return false;
        } catch (Exception e) { return false; }
    }

    /** When ForgeBlockListener calls on block-place, the world map is seeded via ForgePlatform. */
    public void registerKnownWorld(String dimId, Object world) { dimensionToWorld.put(dimId, world); }

    public void trackPlacedBlock(UUID playerId, CommonLocation location) { placedBlocks.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(location, System.currentTimeMillis()); }
    public void trackBrokenAir(UUID playerId, CommonLocation location) { brokenAir.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(location, System.currentTimeMillis()); }

    @Override public void clearPlayerTracking(UUID playerId) { placedBlocks.remove(playerId); brokenAir.remove(playerId); }

    @Override public void clearPlayerPath(UUID playerId) {
        link.star_dust.MinerTrack.core.detection.MiningCore mc = this.miningCore;
        if (mc != null && mc.getState() != null) { try { mc.getState().clearPlayerPath(playerId); } catch (Throwable t) {} }
    }

    public java.util.Collection<Object> getRegisteredWorlds() { return dimensionToWorld.values(); }

    private Object resolveWorld(String worldName) { return dimensionToWorld.get(worldName); }
}
