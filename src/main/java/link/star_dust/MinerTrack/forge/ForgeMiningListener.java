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
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.detection.MiningCore;

import java.util.UUID;

/**
 * Forge mining listener: registers block-break/place callbacks via Forge events.
 */
public class ForgeMiningListener {
    private final MiningCore miningCore;
    private final DetectionBridge detectionBridge;
    private final ViolationManagerBridge vlBridge;
    private final ForgeDetectionBridge forgeBridge;

    public ForgeMiningListener(MiningCore miningCore,
            DetectionBridge detectionBridge,
            ViolationManagerBridge vlBridge,
            ForgeDetectionBridge forgeBridge) {
        this.miningCore = miningCore;
        this.detectionBridge = detectionBridge;
        this.vlBridge = vlBridge;
        this.forgeBridge = forgeBridge;
    }

    public void register() {
        // Register on the Forge main event bus
        Object eventBus = ForgeReflection.getMainEventBus();
        if (eventBus == null) return;

        // BlockEvent.BreakEvent: fires after a block is broken by a player.
        // Event is not cancellable for mining listeners (addListener without priority).
        ForgeReflection.registerEventListener(eventBus,
            ForgeReflection.forgeClass("net.minecraftforge.event.level.BlockEvent$BreakEvent"),
            rawEvent -> {
                try {
                    Object world = ForgeReflection.callAny(rawEvent, "getLevel",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    Object player = ForgeReflection.callAny(rawEvent, "getPlayer",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    Object pos = ForgeReflection.callAny(rawEvent, "getPos",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    Object state = ForgeReflection.callAny(rawEvent, "getState",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    if (isClientWorld(world)) return;
                    handleBlockBreak(player, pos, state, world);
                } catch (Throwable t) {}
            });

        // BlockEvent.EntityPlaceEvent: fires when a player places a block.
        ForgeReflection.registerEventListener(eventBus,
            ForgeReflection.forgeClass("net.minecraftforge.event.level.BlockEvent$EntityPlaceEvent"),
            rawEvent -> {
                try {
                    Object world = ForgeReflection.callAny(rawEvent, "getLevel",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    Object entity = ForgeReflection.callAny(rawEvent, "getEntity",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    Object pos = ForgeReflection.callAny(rawEvent, "getPos",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    Object state = ForgeReflection.callAny(rawEvent, "getPlacedBlock",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    if (isClientWorld(world)) return;
                    if (!isServerPlayer(entity)) return;
                    handleBlockPlace(entity, pos, state, world);
                } catch (Throwable t) {}
            });
    }

    private boolean isClientWorld(Object world) {
        // isClientSide is a METHOD on 1.18.2 (Searge m_5776_) and a FIELD on
        // 1.20.6+ (Mojang). Try method call first, then field access.
        try {
            Object r = ForgeReflection.callAny(world, "isClientSide",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable t) { /* fall through to field */ }
        try {
            Object r = ForgeReflection.getField(world, "isClientSide");
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) { return false; }
    }

    private boolean isServerPlayer(Object player) {
        if (player == null) return false;
        Class<?> serverPlayer = ForgeReflection.forName("net.minecraft.server.level.ServerPlayer");
        return serverPlayer != null && serverPlayer.isInstance(player);
    }

    private void handleBlockBreak(Object player, Object pos, Object state, Object world) {
        UUID playerId = readUuid(player);
        String name = readPlayerName(player);
        String dimensionId = readDimensionId(world);
        int x = readInt(pos, "getX");
        int y = readInt(pos, "getY");
        int z = readInt(pos, "getZ");
        String blockType = blockIdForState(state);
        miningCore.onBlockBreak(playerId, name, dimensionId, blockType, x, y, z);
    }

    private void handleBlockPlace(Object player, Object pos, Object state, Object world) {
        try {
            UUID playerId = readUuid(player);
            String dimensionId = readDimensionId(world);
            String blockType = blockIdForState(state);
            if (blockType == null || BlockId.AIR.equals(blockType)) return;
            int x = readInt(pos, "getX");
            int y = readInt(pos, "getY");
            int z = readInt(pos, "getZ");
            var rareOres = miningCore.getState().getRareOres(dimensionId);
            if (rareOres.contains(blockType)) {
                CommonLocation loc = new CommonLocation(dimensionId, x, y, z);
                forgeBridge.trackPlacedBlock(playerId, loc);
            }
        } catch (Throwable t) {}
    }

    private static UUID readUuid(Object player) {
        try {
            Object uuid = ForgeReflection.callUuid(player);
            return uuid instanceof UUID ? (UUID) uuid : null;
        } catch (Throwable t) { return null; }
    }

    private static String readPlayerName(Object player) {
        try {
            Object profile = ForgeReflection.callAny(player, "getGameProfile",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (profile != null) {
                String name = (String) profile.getClass().getMethod("getName").invoke(profile);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable t) {
            try {
                Object name = ForgeReflection.callAny(player, "getName",
                    ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                return ForgeReflection.readString(name);
            } catch (Throwable t2) {}
        }
        return "";
    }

    private static String readDimensionId(Object world) {
        try {
            Object registryKey = ForgeReflection.callDimension(world);
            if (registryKey == null) return null;
            String s = registryKey.toString();
            int start = s.indexOf('[');
            int slash = s.indexOf(" / ");
            if (start >= 0 && slash > start) {
                int end = s.indexOf(']', slash);
                if (end > slash) return s.substring(slash + 3, end).trim();
                return s.substring(slash + 3).trim();
            }
            String value = ForgeReflection.readString(
                ForgeReflection.callAny(registryKey, "getValue",
                    ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS));
            return value;
        } catch (Throwable t) { return null; }
    }

    private static int readInt(Object target, String method) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                String sn = c.getSimpleName();
                String nm = c.getName();
                if (sn.contains("Vec3i") || nm.contains("class_2382")) break;
                c = c.getSuperclass();
            }
            if (c != null) {
                java.util.List<java.lang.reflect.Field> intFields = new java.util.ArrayList<>();
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == int.class) intFields.add(f);
                }
                int idx = "getY".equals(method) ? 1 : "getZ".equals(method) ? 2 : 0;
                if (idx < intFields.size()) {
                    intFields.get(idx).setAccessible(true);
                    return intFields.get(idx).getInt(target);
                }
            }
        } catch (Throwable t) {}
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            Object r = m.invoke(target);
            return (r instanceof Number) ? ((Number) r).intValue() : 0;
        } catch (Throwable t) { return 0; }
    }

    private static String blockIdForState(Object state) {
        try {
            Object block = ForgeReflection.callAny(state, "getBlock",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (block == null) return BlockId.AIR;
            String id = ForgeReflection.getBlockId(block);
            if (id != null && !id.isEmpty()) return id;
            String s = block.toString();
            int brace = s.indexOf('{');
            int close = s.indexOf('}');
            if (brace >= 0 && close > brace) return s.substring(brace + 1, close);
            return BlockId.AIR;
        } catch (Throwable t) { return BlockId.AIR; }
    }
}
