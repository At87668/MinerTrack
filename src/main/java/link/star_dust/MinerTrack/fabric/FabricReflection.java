package link.star_dust.MinerTrack.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

final class FabricReflection {

    private static volatile Object cachedServer;

    private FabricReflection() {}

    static void setCachedServer(Object server) { cachedServer = server; }

    static Object getServer() {
        if (cachedServer != null) return cachedServer;
        return callStatic("net.minecraft.server.MinecraftServer", "getServer", new Class<?>[0], new Object[0]);
    }

    static Object callServer(String mc26Method, String legacyMethod, Class<?>[] paramTypes, Object[] args) {
        Object server = getServer();
        if (server == null) return null;
        Object result = call(server, mc26Method, paramTypes, args);
        if (result != null || !mc26Method.equals(legacyMethod)) {
            try { if (result == null) result = call(server, legacyMethod, paramTypes, args); } catch (Throwable t) {}
            return result;
        }
        return null;
    }

    static Object callMigrated(Object target, String mc26Method, String legacyMethod, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try { Method m = findMethod(target.getClass(), mc26Method, paramTypes); if (m != null) { m.setAccessible(true); return m.invoke(target, args); } }
        catch (Throwable t) {}
        return call(target, legacyMethod, paramTypes, args);
    }

    private static final Map<String, String> API_MIGRATIONS = new HashMap<>();
    static {
        API_MIGRATIONS.put("getPlayerManager", "getPlayerList");
        API_MIGRATIONS.put("getCommandManager", "getCommands");
        API_MIGRATIONS.put("getWorlds", "getAllLevels");
        API_MIGRATIONS.put("getWorld", "getLevel");
        API_MIGRATIONS.put("getCommandSource", "createCommandSourceStack");
        API_MIGRATIONS.put("withSilent", "withSuppressedOutput");
        API_MIGRATIONS.put("getTicks", "getTickCount");
        API_MIGRATIONS.put("isExecutedByPlayer", "isPlayer");
        API_MIGRATIONS.put("hasPermissionLevel", "hasPermission");
        API_MIGRATIONS.put("executeWithPrefix", "performCommand");
    }

    static Method findMethodWithMigration(Class<?> cls, String methodName, Class<?>[] paramTypes) {
        Method m = findMethod(cls, methodName, paramTypes);
        if (m != null) return m;
        String migrated = API_MIGRATIONS.get(methodName);
        if (migrated != null) return findMethod(cls, migrated, paramTypes);
        return null;
    }

    static Object callStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> cls = forName(className); if (cls == null) return null;
            try { Method m = cls.getDeclaredMethod(methodName, paramTypes); m.setAccessible(true); return m.invoke(null, args); }
            catch (NoSuchMethodException e) { Method m = cls.getMethod(methodName, paramTypes); m.setAccessible(true); return m.invoke(null, args); }
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) { return null; }
    }

    static Object call(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try {
            Class<?> cls = target.getClass();
            try { Method m = cls.getMethod(methodName, paramTypes); return m.invoke(target, args); }
            catch (NoSuchMethodException e) { Method m = cls.getDeclaredMethod(methodName, paramTypes); m.setAccessible(true); return m.invoke(target, args); }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) { return null; }
    }

    static Object callAny(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try { Method m = findMethod(target.getClass(), methodName, paramTypes); if (m == null) return null; m.setAccessible(true); return m.invoke(target, args); }
        catch (IllegalAccessException | InvocationTargetException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    static <T> T getField(Object target, String fieldName) {
        if (target == null) return null;
        try { Field f = findField(target.getClass(), fieldName); if (f == null) return null; f.setAccessible(true); return (T) f.get(target); }
        catch (IllegalAccessException e) { return null; }
    }

    static Object callUuid(Object target) {
        if (target == null) return null;
        Object r = callAny(target, "getUUID", new Class<?>[0], new Object[0]); if (r != null) return r;
        return callAny(target, "getUuid", new Class<?>[0], new Object[0]);
    }

    static Object callDimension(Object world) {
        if (world == null) return null;
        Object r = callAny(world, "dimension", new Class<?>[0], new Object[0]); if (r != null) return r;
        return callAny(world, "getRegistryKey", new Class<?>[0], new Object[0]);
    }

    static Object callResourceKeyValue(Object key) {
        if (key == null) return null;
        Object r = callAny(key, "identifier", new Class<?>[0], new Object[0]); if (r != null) return r;
        r = callAny(key, "location", new Class<?>[0], new Object[0]); if (r != null) return r;
        return callAny(key, "getValue", new Class<?>[0], new Object[0]);
    }

    static String readString(Object source) {
        if (source == null) return null;
        if (source instanceof String) return (String) source;
        try { Method m = source.getClass().getMethod("getString"); Object r = m.invoke(source); if (r instanceof String) return (String) r; }
        catch (Throwable ignored) {}
        String s = source.toString();
        if (s.startsWith("literal(") && s.endsWith(")")) return s.substring("literal(".length(), s.length() - 1);
        return s;
    }

    static String getBlockId(Object block) {
        if (block == null) return null;
        Object blockRegistry = null;
        try { Class<?> birCls = forName("net.minecraft.core.registries.BuiltInRegistries"); if (birCls != null) { Field f = birCls.getField("BLOCK"); blockRegistry = f.get(null); } } catch (Throwable t) {}
        if (blockRegistry == null) { try { Class<?> regCls = forName("net.minecraft.core.Registry"); if (regCls != null) { Field f = regCls.getField("BLOCK"); blockRegistry = f.get(null); } } catch (Throwable t) {} }
        if (blockRegistry == null) { try { Class<?> regsCls = forName("net.minecraft.core.registries.Registries"); if (regsCls == null) regsCls = forName("net.minecraft.registry.Registries"); if (regsCls != null) { Field f = regsCls.getField("BLOCK"); Object mr = f.get(null); if (mr != null) { try { mr.getClass().getMethod("getKey", Object.class); blockRegistry = mr; } catch (NoSuchMethodException e) {} } } } catch (Throwable t) {} }
        if (blockRegistry != null) {
            try { Method m = blockRegistry.getClass().getMethod("getKey", Object.class); Object id = m.invoke(blockRegistry, block); if (id != null) { String s = readString(id); if (s != null) return s; } } catch (Throwable t) {}
            try { Method m = findMethod(blockRegistry.getClass(), "getResourceKey", new Class<?>[]{Object.class}); if (m != null) { Object rv = m.invoke(blockRegistry, block); if (rv instanceof java.util.Optional) { java.util.Optional<?> opt = (java.util.Optional<?>) rv; if (opt.isPresent()) { Object key = opt.get(); Object loc = callResourceKeyValue(key); if (loc != null) { String s = readString(loc); if (s != null) return s; } } } } } catch (Throwable t) {}
        }
        try { Object holder = callAny(block, "builtInRegistryHolder", new Class<?>[0], new Object[0]); if (holder != null) { Object key = callAny(holder, "getKey", new Class<?>[0], new Object[0]); if (key != null) { Object loc = callResourceKeyValue(key); if (loc != null) { String s = readString(loc); if (s != null) return s; } } } } catch (Throwable t) {}
        return null;
    }

    static Object newInstance(String className, Class<?>[] paramTypes, Object[] args) {
        try { Class<?> cls = forName(className); if (cls == null) return null; Constructor<?> c = cls.getDeclaredConstructor(paramTypes); c.setAccessible(true); return c.newInstance(args); }
        catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) { return null; }
    }

    static Class<?> forName(String className) {
        try { return Class.forName(className); } catch (ClassNotFoundException e) { return tryMcMigration(className); }
    }

    private static Class<?> tryMcMigration(String className) {
        if ("net.minecraft.server.level.ServerLevel".equals(className)) return tryLoad("net.minecraft.class_3218");
        if ("net.minecraft.server.level.ServerPlayer".equals(className)) return tryLoad("net.minecraft.class_3222");
        if ("net.minecraft.server.level.ServerPlayerGameMode".equals(className)) return tryLoad("net.minecraft.class_3225");
        if ("net.minecraft.server.players.PlayerList".equals(className)) return tryLoad("net.minecraft.class_3324");
        if ("net.minecraft.server.network.ServerGamePacketListenerImpl".equals(className)) return tryLoad("net.minecraft.class_3244");
        if ("net.minecraft.commands.CommandSourceStack".equals(className)) return tryLoad("net.minecraft.class_2168");
        if ("net.minecraft.world.entity.LightningBolt".equals(className)) return tryLoad("net.minecraft.class_1538");
        if ("net.minecraft.world.entity.EntityType".equals(className)) return tryLoad("net.minecraft.class_1299");
        if ("net.minecraft.world.entity.Entity".equals(className)) return tryLoad("net.minecraft.class_1297");
        if ("net.minecraft.world.entity.player.Player".equals(className)) return tryLoad("net.minecraft.class_1657");
        if ("net.minecraft.world.level.Level".equals(className)) return tryLoad("net.minecraft.class_1937");
        if ("net.minecraft.world.level.block.Block".equals(className)) return tryLoad("net.minecraft.class_2248");
        if ("net.minecraft.world.level.block.Blocks".equals(className)) return tryLoad("net.minecraft.class_2246");
        if ("net.minecraft.world.level.block.state.BlockState".equals(className)) return tryLoad("net.minecraft.class_2680");
        if ("net.minecraft.world.level.block.LiquidBlock".equals(className)) return tryLoad("net.minecraft.class_2404");
        if ("net.minecraft.world.level.material.Fluids".equals(className)) return tryLoad("net.minecraft.class_3612");
        if ("net.minecraft.world.level.material.Fluid".equals(className)) return tryLoad("net.minecraft.class_3611");
        if ("net.minecraft.world.level.GameType".equals(className)) return tryLoad("net.minecraft.class_1934");
        if ("net.minecraft.core.BlockPos".equals(className)) return tryLoad("net.minecraft.class_2338");
        if ("net.minecraft.core.Registry".equals(className)) return tryLoad("net.minecraft.class_2378");
        if ("net.minecraft.core.registries.Registries".equals(className)) return tryLoad("net.minecraft.class_7923");
        if ("net.minecraft.core.registries.BuiltInRegistries".equals(className)) return tryLoad("net.minecraft.class_7922");
        if ("net.minecraft.network.chat.Component".equals(className)) return tryLoad("net.minecraft.class_2561");
        if ("net.minecraft.network.chat.ChatType".equals(className)) return tryLoad("net.minecraft.class_2556");
        if ("net.minecraft.network.chat.MutableComponent".equals(className)) return tryLoad("net.minecraft.class_5250");
        if ("net.minecraft.world.InteractionResult".equals(className)) return tryLoad("net.minecraft.class_1269");
        if ("net.minecraft.world.phys.Vec3".equals(className)) return tryLoad("net.minecraft.class_243");
        if ("net.minecraft.world.phys.Vec2".equals(className)) return tryLoad("net.minecraft.class_241");
        if ("net.minecraft.stats.ServerStatsCounter".equals(className)) return tryLoad("net.minecraft.class_3442");
        if ("net.minecraft.text.Text".equals(className)) return tryLoad("net.minecraft.network.chat.Component");
        if ("net.minecraft.text.LiteralText".equals(className)) return tryLoad("net.minecraft.network.chat.Component");
        if ("net.minecraft.text.MutableText".equals(className)) return tryLoad("net.minecraft.network.chat.MutableComponent");
        if ("net.minecraft.registry.Registries".equals(className)) return tryLoad("net.minecraft.core.registries.Registries");
        if ("net.minecraft.util.math.BlockPos".equals(className)) return tryLoad("net.minecraft.core.BlockPos");
        if ("net.minecraft.entity.LightningEntity".equals(className)) return tryLoad("net.minecraft.world.entity.LightningBolt");
        if ("net.minecraft.entity.EntityType".equals(className)) return tryLoad("net.minecraft.world.entity.EntityType");
        if ("net.minecraft.server.world.ServerWorld".equals(className)) return tryLoad("net.minecraft.server.level.ServerLevel");
        if ("net.minecraft.server.network.ServerPlayerEntity".equals(className)) return tryLoad("net.minecraft.server.level.ServerPlayer");
        if ("net.minecraft.server.command.ServerCommandSource".equals(className)) return tryLoad("net.minecraft.commands.CommandSourceStack");
        if ("net.minecraft.fluid.Fluids".equals(className)) return tryLoad("net.minecraft.world.level.material.Fluids");
        if ("net.minecraft.fluid.Fluid".equals(className)) return tryLoad("net.minecraft.world.level.material.Fluid");
        if ("net.minecraft.block.FluidBlock".equals(className)) return tryLoad("net.minecraft.world.level.block.LiquidBlock");
        if ("net.minecraft.block.Blocks".equals(className)) return tryLoad("net.minecraft.world.level.block.Blocks");
        if ("net.minecraft.block.Block".equals(className)) return tryLoad("net.minecraft.world.level.block.Block");
        if ("net.minecraft.block.BlockState".equals(className)) return tryLoad("net.minecraft.world.level.block.state.BlockState");
        if ("net.minecraft.network.chat.TextComponent".equals(className)) return tryLoad("net.minecraft.text.LiteralText");
        return null;
    }

    private static Class<?> tryLoad(String className) { try { return Class.forName(className); } catch (ClassNotFoundException e) { return null; } }

    static Class<?> mc(String className) { return forName("net.minecraft." + className); }

    static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null) return null;
        try { return cls.getMethod(name, paramTypes); } catch (NoSuchMethodException ignored) {}
        try { return cls.getDeclaredMethod(name, paramTypes); } catch (NoSuchMethodException ignored) {}
        Class<?> superCls = cls.getSuperclass();
        if (superCls != null && superCls != Object.class) return findMethod(superCls, name, paramTypes);
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        if (cls == null) return null;
        try { return cls.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        Class<?> superCls = cls.getSuperclass();
        if (superCls != null && superCls != Object.class) return findField(superCls, name);
        return null;
    }
}