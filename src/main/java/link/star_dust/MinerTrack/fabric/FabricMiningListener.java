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
                handleBlockPlace(player, hitResult, world);
            } catch (Throwable t) {
                /* silent */ }
            return false; // PASS
        });
    }

    private boolean isClientWorld(Object world) {
        try {
            Object r = FabricReflection.callAny(world, "isClientSide", new Class<?>[0], new Object[0]);
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

    private void handleBlockPlace(Object player, Object hitResult, Object world) {
        try {
            Object pos = FabricReflection.callAny(hitResult, "getBlockPos", new Class<?>[0], new Object[0]);
            if (pos == null)
                return;
            Object state = FabricReflection.call(world, "getBlockState", new Class<?>[] { pos.getClass() },
                    new Object[] { pos });
            if (state == null)
                return;
            String blockType = blockIdForState(state);
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

    private static UUID readUuid(Object player) {
        try {
            // MC 26.1+: Entity.getUUID() (uppercase); 1.18-1.21: getUuid()
            Object uuid = FabricReflection.callUuid(player);
            return uuid instanceof UUID ? (UUID) uuid : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readPlayerName(Object player) {
        try {
            // MC 26.1+: Entity.getName() returns Component
            // 1.18-1.21: returns String
            // readString() handles both — Component.getString() vs String passthrough
            Object name = FabricReflection.callAny(player, "getName", new Class<?>[0], new Object[0]);
            return FabricReflection.readString(name);
        } catch (Throwable t) {
            return "";
        }
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
    // Vec3i stores int x, y, z as private fields.  Their intermediary names
    // (field_NNNN) differ from Entity's method_5878/5626/5794.  We read the
    // fields directly by finding all int fields on Vec3i and reading them in
    // declaration order (x, y, z is the guaranteed order).

    private static int readInt(Object target, String method) {
        try {
            // Walk to Vec3i (BlockPos → extends Vec3i)
            Class<?> c = target.getClass();
            while (c != null && !c.getSimpleName().contains("Vec3i")
                    && !c.getSimpleName().contains("BaseBlockPosition")) {
                c = c.getSuperclass();
            }
            if (c == null) {
                // Fallback: try the method name directly
                return callIntMethod(target, method);
            }
            // Vec3i declares 3 int fields in order: x, y, z
            java.util.List<java.lang.reflect.Field> intFields = new java.util.ArrayList<>();
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() == int.class) intFields.add(f);
            }
            int idx = "getY".equals(method) ? 1 : "getZ".equals(method) ? 2 : 0;
            if (idx < intFields.size()) {
                intFields.get(idx).setAccessible(true);
                return intFields.get(idx).getInt(target);
            }
        } catch (Throwable t) { }
        return 0;
    }

    private static int callIntMethod(Object target, String method) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            Object r = m.invoke(target);
            return (r instanceof Number) ? ((Number) r).intValue() : 0;
        } catch (Throwable t) { return 0; }
    }

    // BlockState.getBlock() intermediary name — differs from any other "getBlock".
    private static final String BS_GET_BLOCK = "method_17049"; // BlockState.getBlock() → Block

    private static String blockIdForState(Object state) {
        try {
            // BlockState.getBlock() → Block (intermediary: method_17049)
            Object block = FabricReflection.callAny(state, BS_GET_BLOCK, new Class<?>[0], new Object[0]);
            if (block == null)
                return BlockId.AIR;
            // MC 26.1+: DefaultedRegistry.getKey(T) returns Identifier
            // 1.18-1.21: SimpleRegistry.getKey(T) returns Identifier
            // We delegate to FabricReflection.getBlockId() which handles both
            // versions and falls back to the holder-based lookup if getKey
            // returns null.
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
