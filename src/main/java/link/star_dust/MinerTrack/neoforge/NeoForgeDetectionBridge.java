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

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** NeoForge DetectionBridge. Mirrors ForgeDetectionBridge (uses net.neoforged.* at runtime). */
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

    public NeoForgeDetectionBridge(NeoForgeAdapter adapter, YamlLoader loader) { this.adapter = adapter; this.loader = loader; active = this; }

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
            this.coreConfig = cc; this.configLoaded = true; return cc;
        }
    }
    @Override public CoreConfig getCoreConfig() { if (!configLoaded) loadGroupConfigs(); return coreConfig; }
    @Override public void clearConfigCache() { configLoaded = false; }
    private void ensureConfigLoaded() { if (!configLoaded) loadGroupConfigs(); }

    public void registerWorld(Object world) {
        if (world == null) return;
        String dimId = dimensionIdForWorld(world); if (dimId != null) dimensionToWorld.put(dimId, world);
    }
    public void unregisterWorld(Object world) {
        if (world == null) return; java.util.List<String> rm = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : dimensionToWorld.entrySet()) { if (e.getValue() == world) rm.add(e.getKey()); }
        for (String k : rm) dimensionToWorld.remove(k);
    }
    private String dimensionIdForWorld(Object world) {
        if (world == null) return null;
        try { Object rk = NeoForgeReflection.callDimension(world); if (rk == null) return null; String s = rk.toString(); int start = s.indexOf('['), slash = s.indexOf(" / "); if (start >= 0 && slash > start) { int end = s.indexOf(']', slash); if (end > slash) return s.substring(slash + 3, end).trim(); return s.substring(slash + 3).trim(); } Object val = NeoForgeReflection.callAny(rk, "getValue", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); return val == null ? null : NeoForgeReflection.readString(val); }
        catch (Throwable t) { return null; }
    }

    @Override public String getBlockType(String world, int x, int y, int z) { try { Object w = resolveWorld(world); if (w == null) return BlockId.AIR; Object pos = NeoForgeReflection.newInstance("net.minecraft.core.BlockPos", new Class<?>[]{int.class, int.class, int.class}, new Object[]{x, y, z}); if (pos == null) return BlockId.AIR; Object state = NeoForgeReflection.call(w, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos}); if (state == null) return BlockId.AIR; Object block = NeoForgeReflection.callAny(state, "getBlock", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (block == null) return BlockId.AIR; String id = NeoForgeReflection.getBlockId(block); if (id != null && !id.isEmpty()) return id; String s = block.toString(); int brace = s.indexOf('{'), close = s.indexOf('}'); if (brace >= 0 && close > brace) return s.substring(brace + 1, close); return BlockId.AIR; } catch (Exception e) { return BlockId.AIR; } }
    @Override public boolean isPlayerPlacedBlock(UUID pid, CommonLocation loc) { Map<CommonLocation, Long> map = placedBlocks.get(pid); if (map == null) return false; Long ts = map.get(loc); if (ts == null) return false; int expire = getConfigForWorld(loc.world, "xray.trace_remove", 15) * 60 * 1000; if (System.currentTimeMillis() - ts > expire) { map.remove(loc); return false; } return true; }
    @Override public String resolveDimensionId(String n) { return DimensionId.normalize(n); }
    @Override public Object getConfig(String p) { ensureConfigLoaded(); return coreConfig.getMainConfig().get(p); }
    @Override public int getConfigInt(String p, int d) { ensureConfigLoaded(); return coreConfig.getMainConfig().getInt(p, d); }
    @Override public boolean getConfigBoolean(String p, boolean d) { ensureConfigLoaded(); return coreConfig.getMainConfig().getBoolean(p, d); }
    @Override public double getConfigDouble(String p, double d) { ensureConfigLoaded(); return coreConfig.getMainConfig().getDouble(p, d); }
    @Override public List<String> getConfigStringList(String p) { ensureConfigLoaded(); return coreConfig.getMainConfig().getStringList(p); }
    @Override public int getConfigForWorld(String w, String p, int d) { ensureConfigLoaded(); return coreConfig.getIntForWorld(w, p, d); }
    @Override public boolean getConfigForWorldBoolean(String w, String p, boolean d) { ensureConfigLoaded(); return coreConfig.getBooleanForWorld(w, p, d); }
    @Override public List<String> getConfigForWorldStringList(String w, String p) { ensureConfigLoaded(); return coreConfig.getStringListForWorld(w, p); }
    @Override public boolean isWorldDetectionEnabled(String w) { return getConfigForWorldBoolean(w, "xray.enable", true); }
    @Override public int getWorldMaxHeight(String w) { return getConfigForWorld(w, "xray.max-height", 32); }
    @Override public List<String> getRareOres(String w) { return getConfigForWorldStringList(w, "xray.rare-ores"); }
    @Override public int getTraceRemoveTime(String w) { return getConfigForWorld(w, "xray.trace_remove", 15); }
    @Override public int getArtificialAirRemoveTime(String w) { return getConfigForWorld(w, "xray.natural-detection.cave.artificial-air-remove-time", 30); }
    @Override public boolean isArtificialAir(UUID pid, CommonLocation loc) { Map<CommonLocation, Long> m = brokenAir.get(pid); return m != null && m.containsKey(loc); }

    /**
     * Check a NeoForge permission for a player. Delegates to the same
     * PermissionAPI + op-level fallback used by {@link NeoForgeCommandBridge}.
     * Without this override, {@code DetectionBridge.hasPermission} falls back
     * to the interface default (always {@code false}), so the
     * {@code disable_bypass_permission} setting was ignored on NeoForge.
     */
    @Override public boolean hasPermission(UUID playerId, String node) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return false;
            Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (pm == null) return false;
            Object player = NeoForgeReflection.call(pm, "getPlayerByUUID",
                new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return false;
            if (NeoForgeCommandBridge.checkNeoForgePermission(player, node)) return true;
            return NeoForgeCommandBridge.isPlayerOperator(player);
        } catch (Throwable t) { return false; }
    }
    @Override public boolean isWaterStill(String world, int x, int y, int z) { try { Object w = resolveWorld(world); if (w == null) return false; Object pos = NeoForgeReflection.newInstance("net.minecraft.core.BlockPos", new Class<?>[]{int.class, int.class, int.class}, new Object[]{x,y,z}); if (pos == null) return false; Object state = NeoForgeReflection.call(w, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos}); if (state == null) return false; Object block = NeoForgeReflection.callAny(state, "getBlock", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); Class<?> fbc = NeoForgeReflection.forName("net.minecraft.world.level.block.LiquidBlock"); if (block != null && fbc != null && fbc.isInstance(block)) { Object fs = NeoForgeReflection.callAny(w, "getFluidState", new Class<?>[]{pos.getClass()}, new Object[]{pos}); if (fs != null) { Object f = NeoForgeReflection.callAny(fs, "getType", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (f != null) { Object st = NeoForgeReflection.callAny(f, "getSource", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); return st != null && st == f; } } return true; } return false; } catch (Exception e) { return false; } }

    public void trackPlacedBlock(UUID pid, CommonLocation loc) { placedBlocks.computeIfAbsent(pid, k -> new ConcurrentHashMap<>()).put(loc, System.currentTimeMillis()); }
    public void trackBrokenAir(UUID pid, CommonLocation loc) { brokenAir.computeIfAbsent(pid, k -> new ConcurrentHashMap<>()).put(loc, System.currentTimeMillis()); }

    @Override public void clearPlayerTracking(UUID playerId) { placedBlocks.remove(playerId); brokenAir.remove(playerId); }

    @Override public void clearPlayerPath(UUID playerId) {
        link.star_dust.MinerTrack.core.detection.MiningCore mc = this.miningCore;
        if (mc != null && mc.getState() != null) { try { mc.getState().clearPlayerPath(playerId); } catch (Throwable t) {} }
    }

    public java.util.Collection<Object> getRegisteredWorlds() { return dimensionToWorld.values(); }
    private Object resolveWorld(String n) { return dimensionToWorld.get(n); }
}
