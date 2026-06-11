package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.detection.MiningCore;

import java.util.UUID;

/**
 * Fabric mining listener: registers block-break and block-place
 * callbacks via the public Fabric API (no Mixin).
 *
 * <p>The callbacks fire on the main server thread, so the
 * detection core's bookkeeping structures can be updated
 * without a separate queue. The translation of a Fabric world
 * / block to the canonical {@code minecraft:xxx} ids is done
 * via {@link FabricReflection} so this class compiles on a
 * non-Fabric classpath.
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
                if (isClientWorld(world)) return;
                if (!isServerPlayer(player)) return;
                handleBlockBreak(player, pos, state, world);
            } catch (Throwable t) {
                // Catch-all so a detection failure never
                // aborts the server's break handling.
            }
        });
        FabricEventBus.registerUseBlock((player, world, hand, hitResult) -> {
            try {
                if (isClientWorld(world)) return false;
                if (!isServerPlayer(player)) return false;
                handleBlockPlace(player, hitResult, world);
            } catch (Throwable t) {
                // Silent.
            }
            return false; // PASS
        });
    }

    private boolean isClientWorld(Object world) {
        try {
            Object r = FabricReflection.callAny(world, "isClient", new Class<?>[0], new Object[0]);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isServerPlayer(Object player) {
        // ServerPlayerEntity is the server-side player; the
        // Player interface itself is the client + server common
        // supertype. We don't need a strict ServerPlayerEntity
        // check (the platform never receives a client-side
        // player on a dedicated server), so any non-null
        // Player is acceptable.
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
            if (pos == null) return;
            Object state = FabricReflection.call(world, "getBlockState", new Class<?>[]{pos.getClass()}, new Object[]{pos});
            if (state == null) return;
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
            Object uuid = FabricReflection.callAny(player, "getUuid", new Class<?>[0], new Object[0]);
            return uuid instanceof UUID ? (UUID) uuid : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readPlayerName(Object player) {
        try {
            Object name = FabricReflection.callAny(player, "getName", new Class<?>[0], new Object[0]);
            return name == null ? "" : name.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readDimensionId(Object world) {
        try {
            Object registryKey = FabricReflection.callAny(world, "getRegistryKey", new Class<?>[0], new Object[0]);
            if (registryKey == null) return null;
            Object value = FabricReflection.callAny(registryKey, "getValue", new Class<?>[0], new Object[0]);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readInt(Object target, String method) {
        try {
            Object r = FabricReflection.callAny(target, method, new Class<?>[0], new Object[0]);
            if (r instanceof Number) return ((Number) r).intValue();
        } catch (Throwable t) {
        }
        return 0;
    }

    private static String blockIdForState(Object state) {
        try {
            Object block = FabricReflection.callAny(state, "getBlock", new Class<?>[0], new Object[0]);
            if (block == null) return BlockId.AIR;
            Class<?> registriesCls = FabricReflection.forName("net.minecraft.registry.Registries");
            if (registriesCls == null) return BlockId.AIR;
            Object blockRegistryField = null;
            try {
                java.lang.reflect.Field f = registriesCls.getField("BLOCK");
                blockRegistryField = f.get(null);
            } catch (Throwable t) {
                try {
                    java.lang.reflect.Field f = registriesCls.getField("BLOCK_REGISTRY");
                    blockRegistryField = f.get(null);
                } catch (Throwable t2) {
                    return BlockId.AIR;
                }
            }
            Object id = FabricReflection.callAny(blockRegistryField, "getId",
                new Class<?>[]{block.getClass()}, new Object[]{block});
            return id == null ? BlockId.AIR : id.toString();
        } catch (Throwable t) {
            return BlockId.AIR;
        }
    }
}
