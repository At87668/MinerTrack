/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 */

package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.ExplosionSnapshot;
import link.star_dust.MinerTrack.common.ExplosionSource;
import link.star_dust.MinerTrack.common.PreExplosionBlock;
import link.star_dust.MinerTrack.core.detection.MiningCore;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Observes vanilla primed TNT without linking against Minecraft classes.
 * ENTITY_UNLOAD is the pre-explosion boundary for vanilla TNT; the end tick
 * callback compares the bounded capture with the live world after the blast.
 */
final class FabricExplosionListener {
    private static final int TNT_RADIUS = 6;

    private final MiningCore miningCore;
    private final FabricDetectionBridge detectionBridge;
    private final Map<Object, PendingTnt> pending = new IdentityHashMap<>();
    private final Map<Object, Long> handledAtTick = new IdentityHashMap<>();
    private long currentTick = Long.MIN_VALUE;

    FabricExplosionListener(MiningCore miningCore, FabricDetectionBridge detectionBridge) {
        this.miningCore = miningCore;
        this.detectionBridge = detectionBridge;
    }

    void register() {
        FabricEventBus.registerEntityUnload(args -> onEntityUnload(args[0], args[1]));
        FabricEventBus.registerEndServerTick(this::onEndServerTick);
        FabricEventBus.registerServerStopping(server -> {
            pending.clear();
            handledAtTick.clear();
        });
    }

    private void onEntityUnload(Object entity, Object world) {
        if (!isPrimedTnt(entity) || world == null || pending.containsKey(entity)) return;
        Long handledTick = handledAtTick.get(entity);
        if (handledTick != null && handledTick == currentTick) return;

        Object source = readTntSource(entity);
        Class<?> serverPlayerClass = FabricReflection.forName(
            FabricReflectionConstants.CLS_SERVER_PLAYER);
        if (source == null || serverPlayerClass == null || !serverPlayerClass.isInstance(source)) return;

        UUID playerId = FabricMiningListener.readUuid(source);
        String playerName = FabricMiningListener.readPlayerName(source);
        String dimensionId = FabricMiningListener.readDimensionId(world);
        if (playerId == null || playerName == null || playerName.isBlank() || dimensionId == null) return;

        int x = readEntityCoordinate(entity, "getX", "method_23317");
        int y = readEntityCoordinate(entity, "getY", "method_23318");
        int z = readEntityCoordinate(entity, "getZ", "method_23321");
        List<PreExplosionBlock> blocks = capture(dimensionId, x, y, z);
        pending.put(entity, new PendingTnt(world, dimensionId, x, y, z,
            playerId, playerName, ExplosionSource.TNT, blocks));
    }

    private void onEndServerTick(Object server) {
        long tick = readTick(server);
        if (tick != currentTick) {
            currentTick = tick;
            handledAtTick.entrySet().removeIf(entry -> entry.getValue() < tick);
        }

        Iterator<Map.Entry<Object, PendingTnt>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Object, PendingTnt> entry = iterator.next();
            PendingTnt tnt = entry.getValue();
            List<PreExplosionBlock> affected = verify(tnt);
            if (affected == null) {
                iterator.remove();
                handledAtTick.put(entry.getKey(), currentTick);
                continue;
            }
            if (!affected.isEmpty()) {
                miningCore.onExplosion(new ExplosionSnapshot(
                    new CommonLocation(tnt.dimensionId, tnt.x, tnt.y, tnt.z),
                    tnt.source, tnt.playerId, tnt.playerName, affected));
            }
            iterator.remove();
            handledAtTick.put(entry.getKey(), currentTick);
        }
    }

    private List<PreExplosionBlock> capture(String dimensionId, int x, int y, int z) {
        List<PreExplosionBlock> blocks = new ArrayList<>();
        for (int dx = -TNT_RADIUS; dx <= TNT_RADIUS; dx++) {
            for (int dy = -TNT_RADIUS; dy <= TNT_RADIUS; dy++) {
                for (int dz = -TNT_RADIUS; dz <= TNT_RADIUS; dz++) {
                    int blockX = x + dx;
                    int blockY = y + dy;
                    int blockZ = z + dz;
                    String blockType = BlockId.normalize(
                        detectionBridge.getBlockType(dimensionId, blockX, blockY, blockZ));
                    if (blockType == null || BlockId.isAir(blockType)) continue;
                    blocks.add(new PreExplosionBlock(
                        new CommonLocation(dimensionId, blockX, blockY, blockZ), blockType));
                }
            }
        }
        return blocks;
    }

    private List<PreExplosionBlock> verify(PendingTnt tnt) {
        List<PreExplosionBlock> affected = new ArrayList<>();
        for (PreExplosionBlock block : tnt.blocks) {
            CommonLocation location = block.location();
            String current = readBlockType(tnt.world, location.x, location.y, location.z);
            if (current == null) return null;
            if (isAirOrFire(current)) affected.add(block);
        }
        return affected;
    }

    private String readBlockType(Object world, int x, int y, int z) {
        try {
            Object pos = FabricReflection.newInstance("net.minecraft.core.BlockPos",
                new Class<?>[]{int.class, int.class, int.class}, new Object[]{x, y, z});
            if (pos == null) return null;
            Object state = FabricReflection.call(world, "getBlockState",
                new Class<?>[]{pos.getClass()}, new Object[]{pos});
            if (state == null) return null;
            Object block = FabricReflection.callAny(state, "getBlock",
                FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (block == null) return null;
            String id = FabricReflection.getBlockId(block);
            if (id == null || id.isBlank()) {
                String text = block.toString();
                int start = text.indexOf('{');
                int end = text.indexOf('}', start + 1);
                if (start >= 0 && end > start) id = text.substring(start + 1, end);
            }
            return BlockId.normalize(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object readTntSource(Object entity) {
        Object source = invokeExact(entity, FabricReflectionConstants.M_TNT_GET_OWNER);
        if (source == null) {
            source = invokeExact(entity, FabricReflectionConstants.M_TNT_GET_CAUSING_ENTITY);
        }
        return source;
    }

    /**
     * Read an entity coordinate. Unlike block positions, entity accessors
     * return {@code double} and are not backed by {@code Vec3i int} fields, so
     * the block-position reader would silently fall through to {@code 0}.
     */
    private static int readEntityCoordinate(Object entity, String namedMethod, String intermediaryMethod) {
        for (String methodName : new String[] { namedMethod, intermediaryMethod }) {
            try {
                java.lang.reflect.Method method = entity.getClass().getMethod(methodName);
                Object value = method.invoke(entity);
                if (value instanceof Number) return (int) Math.floor(((Number) value).doubleValue());
            } catch (Throwable ignored) {
                // fall through to the next candidate
            }
        }
        return FabricMiningListener.readInt(entity, namedMethod);
    }

    private Object invokeExact(Object target, String methodName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isPrimedTnt(Object entity) {
        Class<?> tntClass = FabricReflection.forName(FabricReflectionConstants.CLS_PRIMED_TNT);
        return tntClass != null && entity != null && tntClass.isInstance(entity);
    }

    private long readTick(Object server) {
        Object value = FabricReflection.callAny(server, "getTickCount",
            FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
        if (value instanceof Number) return ((Number) value).longValue();
        return currentTick == Long.MIN_VALUE ? 0L : currentTick + 1L;
    }

    private static boolean isAirOrFire(String blockType) {
        return BlockId.isAir(blockType)
            || "minecraft:fire".equals(blockType)
            || "minecraft:soul_fire".equals(blockType);
    }

    private static final class PendingTnt {
        private final Object world;
        private final String dimensionId;
        private final int x;
        private final int y;
        private final int z;
        private final UUID playerId;
        private final String playerName;
        private final ExplosionSource source;
        private final List<PreExplosionBlock> blocks;

        private PendingTnt(Object world, String dimensionId, int x, int y, int z,
                           UUID playerId, String playerName, ExplosionSource source,
                           List<PreExplosionBlock> blocks) {
            this.world = world;
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.playerId = playerId;
            this.playerName = playerName;
            this.source = source;
            this.blocks = blocks;
        }
    }
}
