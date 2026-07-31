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

package link.star_dust.MinerTrack.neoforge;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CoreConfig;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.DimensionId;
import link.star_dust.MinerTrack.common.YamlLoader;
import link.star_dust.MinerTrack.core.config.GroupConfigLoader;
import link.star_dust.MinerTrack.fabric.FabricReflection;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge DetectionBridge. Mirrors ForgeDetectionBridge.
 */
public class NeoForgeDetectionBridge implements DetectionBridge {
    private static volatile NeoForgeDetectionBridge active;
    public static NeoForgeDetectionBridge getActive() { return active; }

    private final NeoForgeAdapter adapter;
    private final YamlLoader loader;
    private final Map<UUID, Map<CommonLocation, Long>> placedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<CommonLocation, Long>> brokenAir = new ConcurrentHashMap<>();
    private final Map<String, Object> dimensionToWorld = new ConcurrentHashMap<>();
    private volatile link.star_dust.MinerTrack.core.detection.MiningCore miningCore;
    private CoreConfig coreConfig;
    private volatile boolean configLoaded = false;

    public NeoForgeDetectionBridge(NeoForgeAdapter adapter, YamlLoader loader) {
        this.adapter = adapter;
        this.loader = loader;
        active = this;
    }

    public void setMiningCore(link.star_dust.MinerTrack.core.detection.MiningCore miningCore) { this.miningCore = miningCore; }

    public void loadGroupConfigs() {
        synchronized (this) { this.coreConfig = GroupConfigLoader.load(new File(adapter.getDataFolder(), "Configuration"), loader, adapter); this.configLoaded = true; }
    }

    private void ensureConfigLoaded() { if (!configLoaded) { synchronized (this) { if (!configLoaded) loadGroupConfigs(); } } }

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
            Object registryKey = FabricReflection.callDimension(world);
            if (registryKey == null) return null;
            String s = registryKey.toString();
            int start = s.indexOf('['), slash = s.indexOf(" / ");
            if (start >= 0 && slash > start) { int end = s.indexOf(']', slash); if (end > slash) return s.substring(slash + 3, end).trim(); return s.substring(slash + 3).trim(); }
            Object val = FabricReflection.callAny(registryKey, "getValue", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            return val == null ? null : FabricReflection.readString(val);
        } catch (Throwable t) { return null; }
    }

    @Override public String getBlockType(String world, int x, int y, int z) {
        try {
            Object w = resolveWorld(world); if (w == null) return BlockId.AIR;
            Object pos = FabricReflection.newInstance("net.minecraft.core.BlockPos", new Class<?>[]{int.class, int.class, int.class}, new Object[]{x, y, z});
            if (pos == null) return BlockId.AIR;
            Object state = FabricReflection.call(w, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos});
            if (state == null) return BlockId.AIR;
            Object block = FabricReflection.callAny(state, "getBlock", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (block == null) return BlockId.AIR;
            String id = FabricReflection.getBlockId(block); if (id != null && !id.isEmpty()) return id;
            String s = block.toString(); int brace = s.indexOf('{'), close = s.indexOf('}'); if (brace >= 0 && close > brace) return s.substring(brace + 1, close);
            return BlockId.AIR;
        } catch (Exception e) { return BlockId.AIR; }
    }

    @Override public boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = placedBlocks.get(playerId); if (map == null) return false;
        Long ts = map.get(location); if (ts == null) return false;
        int expireMs = getConfigForWorld(location.world, "xray.trace_remove", 15) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) { map.remove(location); return false; }
        return true;
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
            Object pos = FabricReflection.newInstance("net.minecraft.core.BlockPos", new Class<?>[]{int.class, int.class, int.class}, new Object[]{x, y, z});
            if (pos == null) return false;
            Object state = FabricReflection.call(w, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos});
            if (state == null) return false;
            Object block = FabricReflection.callAny(state, "getBlock", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            Class<?> fluidBlockCls = FabricReflection.forName("net.minecraft.world.level.block.LiquidBlock");
            if (block != null && fluidBlockCls != null && fluidBlockCls.isInstance(block)) {
                Object fluidState = FabricReflection.callAny(w, "getFluidState", new Class<?>[]{pos.getClass()}, new Object[]{pos});
                if (fluidState != null) {
                    Object fluid = FabricReflection.callAny(fluidState, "getType", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                    if (fluid != null) { Object still = FabricReflection.callAny(fluid, "getSource", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS); return still != null && still == fluid; }
                }
                return true;
            }
            return false;
        } catch (Exception e) { return false; }
    }

    public void trackPlacedBlock(UUID playerId, CommonLocation location) {
        placedBlocks.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(location, System.currentTimeMillis());
    }

    public void trackBrokenAir(UUID playerId, CommonLocation location) {
        brokenAir.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(location, System.currentTimeMillis());
    }

    public java.util.Collection<Object> getRegisteredWorlds() { return dimensionToWorld.values(); }

    private Object resolveWorld(String worldName) { return dimensionToWorld.get(worldName); }
}
