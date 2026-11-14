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
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ExplosionSnapshot;
import link.star_dust.MinerTrack.common.ExplosionSource;
import link.star_dust.MinerTrack.common.PreExplosionBlock;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.detection.MiningCore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NeoForge mining listener: registers block-break/place callbacks via NeoForge events.
 */
public class NeoForgeMiningListener {
    private final MiningCore miningCore;
    private final DetectionBridge detectionBridge;
    private final ViolationManagerBridge vlBridge;
    private final NeoForgeDetectionBridge neoForgeBridge;

    public NeoForgeMiningListener(MiningCore miningCore,
            DetectionBridge detectionBridge,
            ViolationManagerBridge vlBridge,
            NeoForgeDetectionBridge neoForgeBridge) {
        this.miningCore = miningCore;
        this.detectionBridge = detectionBridge;
        this.vlBridge = vlBridge;
        this.neoForgeBridge = neoForgeBridge;
    }

    public void register() {
        Object eventBus = NeoForgeReflection.getMainEventBus();
        if (eventBus == null) return;

        // NeoForge 26.2+ uses BreakBlockEvent (independent class in level.block package)
        // Older NeoForge uses BlockEvent$BreakEvent (inner class of BlockEvent)
        Class<?> breakEventCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.event.level.block.BreakBlockEvent");
        if (breakEventCls == null) {
            breakEventCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.event.level.BlockEvent$BreakEvent");
        }
        if (breakEventCls != null) {
            NeoForgeReflection.registerEventListener(eventBus, breakEventCls,
                rawEvent -> {
                    try {
                        Object world = getEventWorld(rawEvent);
                        Object player = NeoForgeReflection.callAny(rawEvent, "getPlayer", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                        Object pos = NeoForgeReflection.callAny(rawEvent, "getPos", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                        Object state = NeoForgeReflection.callAny(rawEvent, "getState", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                        if (isClientWorld(world)) return;
                        handleBlockBreak(player, pos, state, world);
                    } catch (Throwable t) {}
                });
        }

        // BlockEvent.EntityPlaceEvent (unchanged across NeoForge versions)
        Class<?> placeEventCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.event.level.BlockEvent$EntityPlaceEvent");
        if (placeEventCls != null) {
            NeoForgeReflection.registerEventListener(eventBus, placeEventCls,
                rawEvent -> {
                    try {
                        Object world = getEventWorld(rawEvent);
                        Object entity = NeoForgeReflection.callAny(rawEvent, "getEntity", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                        Object pos = NeoForgeReflection.callAny(rawEvent, "getPos", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                        Object state = NeoForgeReflection.callAny(rawEvent, "getPlacedBlock", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                        if (isClientWorld(world)) return;
                        if (!isServerPlayer(entity)) return;
                        handleBlockPlace(entity, pos, state, world);
                    } catch (Throwable t) {}
                });
        }

        Class<?> explosionEventCls = firstLoadable(
            "net.neoforged.neoforge.event.level.ExplosionEvent$Detonate",
            "net.neoforged.neoforge.event.world.ExplosionEvent$Detonate");
        if (explosionEventCls != null) {
            NeoForgeReflection.registerEventListener(eventBus, explosionEventCls,
                rawEvent -> {
                    try {
                        handleExplosion(rawEvent);
                    } catch (Throwable t) {}
                });
        }
    }

    private static Class<?> firstLoadable(String... names) {
        for (String name : names) {
            Class<?> cls = NeoForgeReflection.neoClass(name);
            if (cls != null) return cls;
        }
        return null;
    }

    /**
     * Resolve the world/level from a NeoForge BlockEvent. Tries both
     * {@code getLevel()} and {@code getWorld()} and validates the result is a
     * {@code Level} instance, so the reflection scan fallback cannot return an
     * unrelated no-arg method (e.g. {@code getPlayer()}) as the world.
     */
    private static Object getEventWorld(Object rawEvent) {
        if (rawEvent == null) return null;
        Class<?> levelCls = NeoForgeReflection.forName("net.minecraft.world.level.Level");
        for (String name : new String[]{"getLevel", "getWorld"}) {
            try {
                Object w = NeoForgeReflection.callAny(rawEvent, name, NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                if (w != null && (levelCls == null || levelCls.isInstance(w))) return w;
            } catch (Throwable t) { /* try next */ }
        }
        return null;
    }

    private boolean isClientWorld(Object world) {
        try { Object r = NeoForgeReflection.callAny(world, "isClientSide", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (r instanceof Boolean) return (Boolean) r; } catch (Throwable t) {}
        try { Object r = NeoForgeReflection.getField(world, "isClientSide"); return r instanceof Boolean && (Boolean) r; }
        catch (Throwable t) { return false; }
    }

    private static boolean isServerPlayer(Object player) {
        if (player == null) return false;
        Class<?> serverPlayer = NeoForgeReflection.forName("net.minecraft.server.level.ServerPlayer");
        return serverPlayer != null && serverPlayer.isInstance(player);
    }

    private void handleBlockBreak(Object player, Object pos, Object state, Object world) {
        UUID playerId = readUuid(player);
        String name = readPlayerName(player);
        String dimensionId = readDimensionId(world);
        int x = readInt(pos, "getX"), y = readInt(pos, "getY"), z = readInt(pos, "getZ");
        String blockType = blockIdForState(state);
        miningCore.onBlockBreak(playerId, name, dimensionId, blockType, x, y, z);
    }

    private void handleBlockPlace(Object player, Object pos, Object state, Object world) {
        try {
            UUID playerId = readUuid(player);
            String dimensionId = readDimensionId(world);
            String blockType = blockIdForState(state);
            if (blockType == null || BlockId.AIR.equals(blockType)) return;
            int x = readInt(pos, "getX"), y = readInt(pos, "getY"), z = readInt(pos, "getZ");
            var rareOres = miningCore.getState().getRareOres(dimensionId);
            if (rareOres.contains(blockType)) {
                CommonLocation loc = new CommonLocation(dimensionId, x, y, z);
                neoForgeBridge.trackPlacedBlock(playerId, loc);
            }
        } catch (Throwable t) {}
    }

    private void handleExplosion(Object rawEvent) {
        Object world = getEventWorld(rawEvent);
        if (world == null || isClientWorld(world)) return;

        Object explosion = NeoForgeReflection.callAny(rawEvent, "getExplosion",
            NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
        if (explosion == null) return;

        String dimensionId = readDimensionId(world);
        if (dimensionId == null || dimensionId.isEmpty()) return;

        Object originObject = firstNonNull(
            callNoArg(explosion, "getPosition"),
            callNoArg(explosion, "getCenter"),
            callNoArg(explosion, "position"),
            callNoArg(explosion, "center"),
            callNoArg(rawEvent, "getPosition"),
            callNoArg(rawEvent, "getCenter"));
        CommonLocation origin = readExplosionOrigin(dimensionId, originObject);
        if (origin == null) return;

        Object affected = firstNonNull(
            callNoArg(rawEvent, "getAffectedBlocks"),
            callNoArg(explosion, "getToBlow"),
            callNoArg(explosion, "getAffectedBlocks"));
        if (!(affected instanceof Iterable<?>)) return;

        ExplosionEvidence evidence = resolveExplosionEvidence(rawEvent, explosion);
        UUID playerId = evidence.player == null ? null : readUuid(evidence.player);
        String playerName = playerId == null ? "" : readPlayerName(evidence.player);

        List<PreExplosionBlock> blocks = new ArrayList<>();
        for (Object pos : (Iterable<?>) affected) {
            if (pos == null) return;
            Object state = NeoForgeReflection.call(world, "getBlockState",
                new Class<?>[]{pos.getClass()}, new Object[]{pos});
            if (state == null) return;

            int x = readInt(pos, "getX");
            int y = readInt(pos, "getY");
            int z = readInt(pos, "getZ");
            CommonLocation location = new CommonLocation(dimensionId, x, y, z);
            boolean playerPlaced = playerId != null
                && neoForgeBridge.isPlayerPlacedBlock(playerId, location);
            blocks.add(new PreExplosionBlock(location, blockIdForState(state), playerPlaced));
        }

        ExplosionSource source = evidence.tnt ? ExplosionSource.TNT : ExplosionSource.OTHER;
        miningCore.onExplosion(new ExplosionSnapshot(origin, source, playerId, playerName, blocks));
    }

    private static CommonLocation readExplosionOrigin(String dimensionId, Object position) {
        if (position == null) return null;
        Double x = readDouble(position, "x");
        Double y = readDouble(position, "y");
        Double z = readDouble(position, "z");
        if (x == null || y == null || z == null) return null;
        return new CommonLocation(dimensionId, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private static Double readDouble(Object target, String coordinate) {
        for (String method : new String[]{coordinate, "get" + Character.toUpperCase(coordinate.charAt(0)) + coordinate.substring(1)}) {
            Object value = callNoArg(target, method);
            if (value instanceof Number) return ((Number) value).doubleValue();
        }
        for (String field : new String[]{coordinate, coordinate + "Coord"}) {
            Object value = NeoForgeReflection.getField(target, field);
            if (value instanceof Number) return ((Number) value).doubleValue();
        }
        return null;
    }

    private static ExplosionEvidence resolveExplosionEvidence(Object rawEvent, Object explosion) {
        Object direct = firstNonNull(
            callNoArg(explosion, "getDirectSourceEntity"),
            callNoArg(explosion, "getExploder"),
            callNoArg(rawEvent, "getExploder"));
        Object indirect = firstNonNull(
            callNoArg(explosion, "getIndirectSourceEntity"),
            callNoArg(explosion, "getSourceMob"),
            callNoArg(explosion, "getExploder"),
            callNoArg(rawEvent, "getExploder"));
        Object eventSource = firstNonNull(callNoArg(rawEvent, "getSource"), callNoArg(rawEvent, "getExploder"));

        boolean tnt = isTntSource(direct) || isTntSource(indirect) || isTntSource(eventSource);
        if (!tnt) return new ExplosionEvidence(null, false);

        Object player = firstServerPlayer(
            indirect,
            direct,
            callNoArg(direct, "getOwner"),
            callNoArg(indirect, "getOwner"),
            callNoArg(eventSource, "getOwner"),
            eventSource,
            callNoArg(eventSource, "getDirectEntity"),
            callNoArg(eventSource, "getEntity"));
        return new ExplosionEvidence(player, true);
    }

    private static boolean isTntSource(Object source) {
        if (source == null) return false;
        if (isPrimedTnt(source)) return true;
        for (String method : new String[]{"getDirectEntity", "getEntity", "getExploder"}) {
            if (isPrimedTnt(callNoArg(source, method))) return true;
        }
        return false;
    }

    private static boolean isPrimedTnt(Object source) {
        Class<?> primedTnt = NeoForgeReflection.forName("net.minecraft.world.entity.item.PrimedTnt");
        return primedTnt != null && source != null && primedTnt.isInstance(source);
    }

    private static Object firstServerPlayer(Object... candidates) {
        for (Object candidate : candidates) {
            if (isServerPlayer(candidate)) return candidate;
        }
        return null;
    }

    private static Object callNoArg(Object target, String method) {
        return NeoForgeReflection.callAny(target, method,
            NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static final class ExplosionEvidence {
        private final Object player;
        private final boolean tnt;

        private ExplosionEvidence(Object player, boolean tnt) {
            this.player = player;
            this.tnt = tnt;
        }
    }

    private static UUID readUuid(Object player) { try { Object uuid = NeoForgeReflection.callUuid(player); return uuid instanceof UUID ? (UUID) uuid : null; } catch (Throwable t) { return null; } }

    private static String readPlayerName(Object player) {
        try {
            Object profile = NeoForgeReflection.callAny(player, "getGameProfile", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (profile != null) { String name = (String) profile.getClass().getMethod("getName").invoke(profile); if (name != null && !name.isEmpty()) return name; }
        } catch (Throwable t) {
            try { Object name = NeoForgeReflection.callAny(player, "getName", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); return NeoForgeReflection.readString(name); } catch (Throwable t2) {}
        }
        return "";
    }

    private static String readDimensionId(Object world) {
        try {
            Object registryKey = NeoForgeReflection.callDimension(world); if (registryKey == null) return null;
            String s = registryKey.toString(); int start = s.indexOf('['), slash = s.indexOf(" / ");
            if (start >= 0 && slash > start) { int end = s.indexOf(']', slash); if (end > slash) return s.substring(slash + 3, end).trim(); return s.substring(slash + 3).trim(); }
            return NeoForgeReflection.readString(NeoForgeReflection.callAny(registryKey, "getValue", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS));
        } catch (Throwable t) { return null; }
    }

    private static int readInt(Object target, String method) {
        try {
            Class<?> c = target.getClass();
            while (c != null) { String sn = c.getSimpleName(); if (sn.contains("Vec3i") || c.getName().contains("class_2382")) break; c = c.getSuperclass(); }
            if (c != null) {
                java.util.List<java.lang.reflect.Field> intFields = new java.util.ArrayList<>();
                for (java.lang.reflect.Field f : c.getDeclaredFields()) { if (f.getType() == int.class) intFields.add(f); }
                int idx = "getY".equals(method) ? 1 : "getZ".equals(method) ? 2 : 0;
                if (idx < intFields.size()) { intFields.get(idx).setAccessible(true); return intFields.get(idx).getInt(target); }
            }
        } catch (Throwable t) {}
        try { java.lang.reflect.Method m = target.getClass().getMethod(method); Object r = m.invoke(target); return (r instanceof Number) ? ((Number) r).intValue() : 0; }
        catch (Throwable t) { return 0; }
    }

    private static String blockIdForState(Object state) {
        try {
            Object block = NeoForgeReflection.callAny(state, "getBlock", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (block == null) return BlockId.AIR;
            String id = NeoForgeReflection.getBlockId(block); if (id != null && !id.isEmpty()) return id;
            String s = block.toString(); int brace = s.indexOf('{'), close = s.indexOf('}'); if (brace >= 0 && close > brace) return s.substring(brace + 1, close);
            return BlockId.AIR;
        } catch (Throwable t) { return BlockId.AIR; }
    }
}
