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

package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.detection.MiningCore;

import java.util.UUID;

/**
 * Fabric mining listener: registers block-break/place callbacks via Fabric API
 * (no Mixin).
 */
public class FabricMiningListener {
    private final MiningCore miningCore;
    private final DetectionBridge detectionBridge;
    private final ViolationManagerBridge vlBridge;
    private final FabricDetectionBridge fabricBridge;

    public FabricMiningListener(MiningCore miningCore,
            DetectionBridge detectionBridge,
            ViolationManagerBridge vlBridge,
            FabricDetectionBridge fabricBridge) {
        this.miningCore = miningCore;
        this.detectionBridge = detectionBridge;
        this.vlBridge = vlBridge;
        this.fabricBridge = fabricBridge;
    }

    public void register() {
        FabricEventBus.registerBlockBreakAfter(args -> {
            try {
                // Fabric API 1.18.2: PlayerBlockBreakEvents$After signature is
                // args[0]=world, args[1]=player, args[2]=pos, args[3]=state
                Object world = args[0];
                Object player = args[1];
                Object pos = args[2];
                Object state = args[3];
                if (isClientWorld(world))
                    return;
                if (!isServerPlayer(player))
                    return;
                handleBlockBreak(player, pos, state, world);
            } catch (Throwable t) {
                /* silent */ }
        });
        FabricEventBus.registerUseBlock((player, world, hand, hitResult) -> {
            try {
                if (isClientWorld(world))
                    return false;
                if (!isServerPlayer(player))
                    return false;
                handleBlockPlace(player, hand, hitResult, world);
            } catch (Throwable t) {
                /* silent */ }
            return false; // PASS
        });
    }

    private boolean isClientWorld(Object world) {
        try {
            Object r = FabricReflection.getField(world, "isClientSide");
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isServerPlayer(Object player) {
        return player != null;
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

    /**
     * Handle block placement by reading the held item rather than the
     * world state.  {@code UseBlockCallback} fires <em>before</em> the
     * block is actually placed, so {@code world.getBlockState(pos)}
     * would return the old block (AIR or whatever was there before).
     *
     * <p>Instead we inspect the player's held {@code ItemStack}:
     * {@code getItemInHand(hand) → getItem() → instanceof BlockItem →
     * getBlock() → Block.toString()}.
     *
     * <p>When the stack is empty (player placed their last block),
     * {@code ItemStack.isEmpty()} guards against NPE.</p>
     */
    private void handleBlockPlace(Object player, Object hand, Object hitResult, Object world) {
        try {
            // 1. Get the held ItemStack
            Object stack = FabricReflection.callAny(player, "getItemInHand",
                    new Class<?>[] { hand.getClass() }, new Object[] { hand });
            if (stack == null)
                return;
            // 2. Guard: player placed their last block (stack is now AIR/empty)
            if (isStackEmpty(stack))
                return;
            // 3. Get the Item, check if it's a BlockItem
            Object item = FabricReflection.callAny(stack, "getItem",
                    FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (item == null)
                return;
            Class<?> blockItemClass = FabricReflection.forName("net.minecraft.world.item.BlockItem");
            if (blockItemClass == null || !blockItemClass.isInstance(item))
                return;
            // 4. Get the Block and its string id
            Object block = FabricReflection.callAny(item, "getBlock",
                    FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (block == null)
                return;
            String blockType = blockToString(block);
            // 5. Get the placement position (adjacent to the clicked face)
            Object pos = FabricReflection.callAny(hitResult, "getBlockPos",
                    FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (pos == null)
                return;
            String dimensionId = readDimensionId(world);
            var rareOres = miningCore.getState().getRareOres(dimensionId);
            if (rareOres.contains(blockType)) {
                CommonLocation loc = new CommonLocation(dimensionId,
                        readInt(pos, "getX"), readInt(pos, "getY"), readInt(pos, "getZ"));
                fabricBridge.trackPlacedBlock(readUuid(player), loc);
            }
        } catch (Throwable t) {
            // Silent.
        }
    }

    /** Check whether an ItemStack is empty (no items or AIR). */
    private static boolean isStackEmpty(Object stack) {
        try {
            Object result = FabricReflection.callAny(stack, "isEmpty",
                    FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            return true; // defensive: treat as empty
        }
    }

    /** Extract block id from {@code Block.toString()} → {@code "Block{minecraft:diamond_ore}"}. */
    private static String blockToString(Object block) {
        String s = block.toString();
        int brace = s.indexOf('{');
        int close = s.indexOf('}');
        if (brace >= 0 && close > brace) {
            return s.substring(brace + 1, close);
        }
        String id = FabricReflection.getBlockId(block);
        return id != null && !id.isEmpty() ? id : BlockId.AIR;
    }

    private static UUID readUuid(Object player) {
        try {
            // MC 26.1+: Entity.getUUID() (uppercase); 1.18-1.21: getUuid()
            Object uuid = FabricReflection.callUuid(player);
            return uuid instanceof UUID ? (UUID) uuid : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read the player's name via their GameProfile.
     *
     * <p>Entity.getName() intermediary (method_37908) collides with
     * ServerLevel.getName() (via Nameable), causing 'getName' to resolve
     * to ServerLevel when the world arg is mistakenly passed as player.
     *
     * ServerPlayer.getGameProfile() has no collision, and
     * GameProfile is a Mojang authlib class (not obfuscated), so
     * {@code getName()} works directly without intermediary redirect.</p>
     */
    private static String readPlayerName(Object player) {
        try {
            // ServerPlayer.getGameProfile() → GameProfile.getName()
            Object profile = FabricReflection.callAny(player, "getGameProfile",
                FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (profile != null) {
                String name = (String) profile.getClass().getMethod("getName").invoke(profile);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable t) {
            // Fallback: try getName → readString
            try {
                Object name = FabricReflection.callAny(player, "getName",
                    FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                return FabricReflection.readString(name);
            } catch (Throwable t2) { }
        }
        return "";

    }

    private static String readDimensionId(Object world) {
        try {
            // MC 26.1+: Level.dimension(); 1.18-1.21: Level.getRegistryKey()
            Object registryKey = FabricReflection.callDimension(world);
            if (registryKey == null)
                return null;
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
            // Fallback: try getValue() which returns a ResourceLocation
            String value = FabricReflection.readString(
                FabricReflection.callAny(registryKey, "getValue", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS));
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── BlockPos / Vec3i coordinate access ────────────────────────────
    //
    // Vec3i (intermediary class_2382) has private int fields x, y, z.
    // On production the class name is NOT "Vec3i" — it's "class_2382".
    // We walk up to the first superclass whose getName() contains "class_"
    // OR try Vec3i by name, then read the int fields in declaration order.
    // Fallback: direct getX/getY/getZ method call bypassing redirect.

    private static int readInt(Object target, String method) {
        try {
            // Walk to Vec3i superclass (intermediary name: class_2382)
            Class<?> c = target.getClass();
            while (c != null) {
                String sn = c.getSimpleName();
                String nm = c.getName();
                if (sn.contains("Vec3i") || nm.contains("class_2382")) break;
                c = c.getSuperclass();
            }
            if (c != null) {
                // Read Vec3i's int fields in declaration order: x, y, z
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
        } catch (Throwable t) { }
        // Fallback: call getX/getY/getZ directly on the target class
        // (bypass redirect — getMethod uses Java's inheritance resolution)
        return callIntMethod(target, method);
    }

    private static int callIntMethod(Object target, String method) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            Object r = m.invoke(target);
            return (r instanceof Number) ? ((Number) r).intValue() : 0;
        } catch (Throwable t) { return 0; }
    }

    private static String blockIdForState(Object state) {
        try {
            // BlockState.getBlock() → Block (redirected via constants)
            Object block = FabricReflection.callAny(state, "getBlock", new Class<?>[0], new Object[0]);
            if (block == null)
                return BlockId.AIR;
            // 1. Block.toString() → "Block{minecraft:diorite}"
            //    Reliable on ALL MC versions, no reflection registry needed.
            String s = block.toString();
            int brace = s.indexOf('{');
            int close = s.indexOf('}');
            if (brace >= 0 && close > brace) {
                return s.substring(brace + 1, close);
            }
            // 2. Fallback: FabricReflection.getBlockId() (registry-based)
            String id = FabricReflection.getBlockId(block);
            if (id != null && !id.isEmpty()) {
                return id;
            }
            return BlockId.AIR;
        } catch (Throwable t) {
            return BlockId.AIR;
        }
    }
}
