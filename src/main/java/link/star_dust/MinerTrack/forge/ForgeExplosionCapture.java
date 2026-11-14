/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 */

package link.star_dust.MinerTrack.forge;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.ExplosionSnapshot;
import link.star_dust.MinerTrack.common.ExplosionSource;
import link.star_dust.MinerTrack.common.PreExplosionBlock;
import link.star_dust.MinerTrack.core.detection.MiningCore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ForgeExplosionCapture {
    private ForgeExplosionCapture() {}

    static void register(Object eventBus, MiningCore miningCore) {
        Class<?> event = firstLoadable(
            "net.minecraftforge.event.level.ExplosionEvent$Detonate",
            "net.minecraftforge.event.world.ExplosionEvent$Detonate");
        if (event == null) return;
        ForgeReflection.registerEventListener(eventBus, event, rawEvent -> {
            try {
                handle(rawEvent, miningCore);
            } catch (Throwable ignored) {}
        });
    }

    private static void handle(Object event, MiningCore miningCore) {
        Object world = ForgeMiningListener.getEventWorld(event);
        if (world == null || ForgeMiningListener.isClientWorld(world)) return;
        Object explosion = callFirst(event, "getExplosion");
        String dimension = ForgeMiningListener.readDimensionId(world);
        CommonLocation origin = readOrigin(explosion, dimension);
        List<PreExplosionBlock> blocks = readBlocks(event, explosion, world, dimension);
        if (explosion == null || origin == null || blocks == null) return;

        Object exploder = callFirst(explosion, "getExploder", "getDirectSourceEntity");
        Object indirect = callFirst(explosion, "getIndirectSourceEntity");
        Object eventSource = callFirst(event, "getSource", "getExploder", "getIndirectSourceEntity");
        boolean tnt = isTntSource(exploder) || isTntSource(indirect) || isTntSource(eventSource);
        ExplosionSource source = tnt ? ExplosionSource.TNT : ExplosionSource.OTHER;
        Object player = tnt ? resolvePlayer(exploder, indirect, eventSource) : null;
        UUID playerId = ForgeMiningListener.readUuid(player);
        String playerName = ForgeMiningListener.readPlayerName(player);
        miningCore.onExplosion(new ExplosionSnapshot(origin, source, playerId, playerName, List.copyOf(blocks)));
    }

    private static List<PreExplosionBlock> readBlocks(Object event, Object explosion,
            Object world, String dimension) {
        Object positions = callFirst(event, "getToBlow", "getAffectedBlocks");
        if (positions == null) positions = callFirst(explosion, "getToBlow", "getAffectedBlocks");
        if (!(positions instanceof Iterable<?> iterable) || dimension == null) return null;
        List<PreExplosionBlock> blocks = new ArrayList<>();
        for (Object position : iterable) {
            if (position == null) return null;
            Object state = ForgeReflection.callAny(world, "getBlockState",
                new Class<?>[]{position.getClass()}, new Object[]{position});
            if (state == null) return null;
            CommonLocation location = new CommonLocation(dimension,
                ForgeMiningListener.readInt(position, "getX"),
                ForgeMiningListener.readInt(position, "getY"),
                ForgeMiningListener.readInt(position, "getZ"));
            blocks.add(new PreExplosionBlock(location,
                ForgeMiningListener.blockIdForState(state)));
        }
        return List.copyOf(blocks);
    }

    private static CommonLocation readOrigin(Object explosion, String dimension) {
        if (explosion == null || dimension == null) return null;
        Object position = callFirst(explosion, "getPosition", "getCenter", "center");
        Double x = readCoordinate(position, "x");
        Double y = readCoordinate(position, "y");
        Double z = readCoordinate(position, "z");
        if (x == null || y == null || z == null) return null;
        return new CommonLocation(dimension, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private static Double readCoordinate(Object position, String axis) {
        if (position == null) return null;
        Object value = ForgeReflection.getField(position, axis);
        if (!(value instanceof Number)) {
            value = callFirst(position, axis,
                "get" + Character.toUpperCase(axis.charAt(0)) + axis.substring(1));
        }
        if (!(value instanceof Number)) {
            int index = "y".equals(axis) ? 1 : "z".equals(axis) ? 2 : 0;
            Class<?> type = position.getClass();
            while (type != null) {
                List<java.lang.reflect.Field> coordinates = new ArrayList<>();
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (field.getType() == double.class) coordinates.add(field);
                }
                if (index < coordinates.size()) {
                    try {
                        coordinates.get(index).setAccessible(true);
                        value = coordinates.get(index).get(position);
                    } catch (Throwable ignored) {}
                    break;
                }
                type = type.getSuperclass();
            }
        }
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private static Object resolvePlayer(Object... sources) {
        for (Object source : sources) {
            Object player = playerFrom(source);
            if (ForgeMiningListener.isServerPlayer(player)) return player;
        }
        return null;
    }

    private static Object playerFrom(Object source) {
        if (source == null) return null;
        if (ForgeMiningListener.isServerPlayer(source)) return source;
        if (isTntSource(source)) {
            Object owner = callFirst(source, "getOwner");
            if (ForgeMiningListener.isServerPlayer(owner)) return owner;
        }
        Object direct = callFirst(source, "getDirectEntity", "getEntity");
        if (direct != source && isTntSource(direct)) return playerFrom(direct);
        return null;
    }

    private static boolean isTntSource(Object source) {
        if (isKnownTnt(source)) return true;
        Object direct = callFirst(source, "getDirectEntity", "getEntity");
        return direct != source && isKnownTnt(direct);
    }

    private static boolean isKnownTnt(Object source) {
        if (source == null) return false;
        Class<?> primedTnt = ForgeReflection.forName("net.minecraft.world.entity.item.PrimedTnt");
        Class<?> tntEntity = ForgeReflection.forName("net.minecraft.entity.item.TNTEntity");
        String className = source.getClass().getName();
        return (primedTnt != null && primedTnt.isInstance(source))
            || (tntEntity != null && tntEntity.isInstance(source))
            || className.endsWith(".PrimedTnt") || className.endsWith(".TNTEntity");
    }

    private static Object callFirst(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Object value = ForgeReflection.callAny(target, name,
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (value != null) return value;
        }
        return null;
    }

    private static Class<?> firstLoadable(String... names) {
        for (String name : names) {
            Class<?> type = ForgeReflection.forgeClass(name);
            if (type != null) return type;
        }
        return null;
    }
}
