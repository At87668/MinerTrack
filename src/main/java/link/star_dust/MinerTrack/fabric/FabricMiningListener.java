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
                // DEBUG: log argument types and their toString briefly
                StringBuilder sb = new StringBuilder("[MinerTrack:DEBUG] BlockBreak args: ");
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    sb.append("[").append(i).append("]=");
                    if (a == null) sb.append("null");
                    else {
                        sb.append(a.getClass().getName());
                        // Include a short toString (trim to 80 chars)
                        String ts = a.toString();
                        if (ts.length() > 80) ts = ts.substring(0, 77) + "...";
                        sb.append("(\"").append(ts.replace("\n","\\n")).append("\")");
                    }
                    sb.append(" ");
                }
                System.out.println(sb.toString());

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
            Object r = FabricReflection.callAny(world, "isClient", new Class<?>[0], new Object[0]);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isServerPlayer(Object player) {
        return player != null;
    }

    private void handleBlockBreak(Object player, Object pos, Object state, Object world) {
        System.out.println("[MinerTrack:DEBUG] handleBlockBreak: player.class=" + (player != null ? player.getClass().getName() : "null") +
            " pos.class=" + (pos != null ? pos.getClass().getName() : "null") +
            " state.class=" + (state != null ? state.getClass().getName() : "null") +
            " world.class=" + (world != null ? world.getClass().getName() : "null"));

        UUID playerId = readUuid(player);
        System.out.println("[MinerTrack:DEBUG]   readUuid()=" + playerId);

        String name = readPlayerName(player);
        System.out.println("[MinerTrack:DEBUG]   readPlayerName()=" + name);

        String dimensionId = readDimensionId(world);
        System.out.println("[MinerTrack:DEBUG]   readDimensionId()=" + dimensionId);

        int x = readInt(pos, "getX");
        int y = readInt(pos, "getY");
        int z = readInt(pos, "getZ");
        System.out.println("[MinerTrack:DEBUG]   pos=(" + x + "," + y + "," + z + ")");

        String blockType = blockIdForState(state);
        System.out.println("[MinerTrack:DEBUG]   blockType=" + blockType);

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
            // MC 26.1+: Level.dimension(); 1.18-1.21: getRegistryKey()
            Object registryKey = FabricReflection.callDimension(world);
            if (registryKey == null)
                return null;
            // MC 26.1+: ResourceKey.location(); 1.18-1.21: ResourceKey.getValue()
            Object value = FabricReflection.callResourceKeyValue(registryKey);
            return value == null ? null : FabricReflection.readString(value);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readInt(Object target, String method) {
        try {
            Object r = FabricReflection.callAny(target, method, new Class<?>[0], new Object[0]);
            if (r instanceof Number)
                return ((Number) r).intValue();
        } catch (Throwable t) {
        }
        return 0;
    }

    private static String blockIdForState(Object state) {
        try {
            Object block = FabricReflection.callAny(state, "getBlock", new Class<?>[0], new Object[0]);
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
