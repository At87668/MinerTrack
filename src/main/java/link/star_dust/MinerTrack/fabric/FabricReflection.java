package link.star_dust.MinerTrack.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class FabricReflection {

    private static volatile Object cachedServer;
    private static final boolean IS_DEV = FabricLoader.getInstance().isDevelopmentEnvironment();
    static boolean DEBUG_REFLECTION = false;
    static void setDebugReflection(boolean on) { DEBUG_REFLECTION = on; }

    private static MappingResolver resolver() { return FabricLoader.getInstance().getMappingResolver(); }

    // -- Hardcoded mappings (mojang -> official), cross-referenced from Mojang ProGuard + Fabric intermediary --
    // Class:  mojang.FQN -> official class name (short name or FQN)
    // Method: mojang method name -> official method name (within a class)
    // Field:  mojang field name -> official field name (within a class)
    // These never change for 1.18-1.21.x. MC 26+ ships unobfuscated so forName succeeds directly.

    private static final Map<String,String> MOJANG_CLASS_TO_OFFICIAL;
    private static final Map<String,Map<String,String>> MOJANG_METHODS;
    private static final Map<String,Map<String,String>> MOJANG_FIELDS;
    private static final Map<String,Map<String,String>> INTERMEDIARY_METHODS;
    private static final Map<String,Map<String,String>> INTERMEDIARY_FIELDS;
    private static final Map<String,String> RUNTIME_TO_MOJANG;

    static {
        Map<String,String> c = new HashMap<>();
        c.put("net/minecraft/server/MinecraftServer","net/minecraft/server/MinecraftServer");
        c.put("net/minecraft/server/level/ServerPlayer","adx");
        c.put("net/minecraft/server/level/ServerLevel","adw");
        c.put("net/minecraft/server/players/PlayerList","agn");
        c.put("net/minecraft/commands/CommandSourceStack","dm");
        c.put("net/minecraft/world/entity/LightningBolt","axx");
        c.put("net/minecraft/world/entity/EntityType","axo");
        c.put("net/minecraft/world/entity/Entity","axk");
        c.put("net/minecraft/world/entity/player/Player","byr");
        c.put("net/minecraft/world/level/Level","cav");
        c.put("net/minecraft/world/level/block/Block","cdq");
        c.put("net/minecraft/world/level/block/Blocks","cdr");
        c.put("net/minecraft/world/level/block/state/BlockState","cov");
        c.put("net/minecraft/world/level/block/LiquidBlock","chu");
        c.put("net/minecraft/world/level/material/Fluids","diy");
        c.put("net/minecraft/world/level/material/Fluid","diw");
        c.put("net/minecraft/world/level/GameType","cas");
        c.put("net/minecraft/core/BlockPos","gj");
        c.put("net/minecraft/core/Registry","hb");
        c.put("net/minecraft/core/registries/BuiltInRegistries","lt");
        c.put("net/minecraft/core/registries/Registries","lu");
        c.put("net/minecraft/network/chat/Component","qk");
        c.put("net/minecraft/network/chat/ChatType","qh");
        c.put("net/minecraft/network/chat/MutableComponent","qq");
        c.put("net/minecraft/world/InteractionResult","awh");
        c.put("net/minecraft/world/phys/Vec3","axw");
        c.put("net/minecraft/world/phys/Vec2","axv");
        c.put("net/minecraft/server/level/ServerPlayerGameMode","ady");
        c.put("net/minecraft/server/network/ServerGamePacketListenerImpl","aeo");
        c.put("net/minecraft/stats/ServerStatsCounter","ahq");
        MOJANG_CLASS_TO_OFFICIAL = Collections.unmodifiableMap(c);

        Map<String,Map<String,String>> m = new HashMap<>();

        // MinecraftServer methods
        Map<String,String> ms = new HashMap<>();
        ms.put("getPlayerList","a"); ms.put("getAllLevels","A"); ms.put("getCommands","aC");
        ms.put("createCommandSourceStack","a");
        ms.put("getTickCount","W"); ms.put("getTicks","W"); ms.put("getLevel","d");
        ms.put("getWorld","d"); ms.put("getPlayerManager","a"); ms.put("getCommandManager","aC");
        ms.put("getWorlds","A"); ms.put("getCommandSource","a");
        m.put("net/minecraft/server/MinecraftServer",ms);

        // ServerPlayer methods
        Map<String,String> sp = new HashMap<>();
        sp.put("getName","W"); sp.put("getUUID","dn"); sp.put("getUuid","dn");
        sp.put("getX","t"); sp.put("getY","u"); sp.put("getZ","v");
        sp.put("getGameProfile","fp");
        sp.put("sendSystemMessage","c"); sp.put("sendMessage","c");
        m.put("net/minecraft/server/level/ServerPlayer",sp);

        // ServerLevel methods
        Map<String,String> sl = new HashMap<>();
        sl.put("getBlockState","c"); sl.put("dimension","E"); sl.put("getRegistryKey","E");
        m.put("net/minecraft/server/level/ServerLevel",sl);

        // PlayerList methods
        Map<String,String> pl = new HashMap<>();
        pl.put("getPlayer","a"); pl.put("getPlayerByName","d"); pl.put("getPlayers","o");
        pl.put("getPlayerList","o"); pl.put("isOp","d"); pl.put("broadcastSystemMessage","a");
        pl.put("broadcastMessage","a"); pl.put("broadcast","a");
        m.put("net/minecraft/server/players/PlayerList",pl);

        // Entity methods
        Map<String,String> ent = new HashMap<>();
        ent.put("getUUID","cn"); ent.put("getUuid","cn"); ent.put("getName","W");
        ent.put("getX","t"); ent.put("getY","u"); ent.put("getZ","v");
        ent.put("setPos","e"); ent.put("refreshPositionAfterTeleport","e");
        m.put("net/minecraft/world/entity/Entity",ent);

        // LightningBolt methods
        Map<String,String> lb = new HashMap<>();
        lb.put("setPos","e");
        m.put("net/minecraft/world/entity/LightningBolt",lb);

        // Level methods
        Map<String,String> lv = new HashMap<>();
        lv.put("getBlockState","c"); lv.put("isClient","F");
        lv.put("dimension","E"); lv.put("getRegistryKey","E");
        m.put("net/minecraft/world/level/Level",lv);

        // Registry methods
        Map<String,String> reg = new HashMap<>();
        reg.put("getKey","e"); reg.put("getResourceKey","f");
        m.put("net/minecraft/core/Registry",reg);

        // BlockState methods
        Map<String,String> bs = new HashMap<>();
        bs.put("getBlock","b"); bs.put("getFluidState","l");
        m.put("net/minecraft/world/level/block/state/BlockState",bs);

        // FluidState methods
        // (FluidState is not in our class table but accessed via getFluidState() on BlockState)

        // Block methods
        Map<String,String> blk = new HashMap<>();
        blk.put("builtInRegistryHolder","a");
        m.put("net/minecraft/world/level/block/Block",blk);

        // Component methods — getString has same name in all namespaces; resolved directly
        m.put("net/minecraft/network/chat/Component",new HashMap<>());

        // ResourceKey methods
        // ResourceKey is not in our table; accessed via callResourceKeyValue which tries identifier/location/getValue

        // Commands methods (the class name changes; accessed via MinecraftServer.getCommands())
        Map<String,String> cmds = new HashMap<>();
        cmds.put("performCommand","a"); cmds.put("performPrefixedCommand","a");
        cmds.put("executeWithPrefix","a");
        m.put("net/minecraft/commands/Commands",cmds);

        // CommandSourceStack methods
        Map<String,String> css = new HashMap<>();
        css.put("isPlayer","l"); css.put("isExecutedByPlayer","l");
        css.put("getPlayer","h"); css.put("getEntity","n");
        css.put("getServer","j"); css.put("withSuppressedOutput","g");
        css.put("withSilent","g"); css.put("sendSystemMessage","a");
        css.put("sendMessage","a"); css.put("sendSuccess","a");
        css.put("hasPermission","b"); css.put("hasPermissionLevel","b");
        m.put("net/minecraft/commands/CommandSourceStack",css);

        // ServerGamePacketListenerImpl methods
        Map<String,String> sgpl = new HashMap<>();
        sgpl.put("disconnect","a"); sgpl.put("onDisconnect","a");
        m.put("net/minecraft/server/network/ServerGamePacketListenerImpl",sgpl);

        // Fluid methods — getStill has same name in all namespaces; resolved directly
        m.put("net/minecraft/world/level/material/Fluid",new HashMap<>());

        MOJANG_METHODS = Collections.unmodifiableMap(m);

        Map<String,Map<String,String>> f = new HashMap<>();

        // EntityType fields
        Map<String,String> et = new HashMap<>();
        et.put("LIGHTNING_BOLT","M");
        f.put("net/minecraft/world/entity/EntityType",et);

        // BuiltInRegistries fields
        Map<String,String> bir = new HashMap<>();
        bir.put("BLOCK","a");
        f.put("net/minecraft/core/registries/BuiltInRegistries",bir);

        // Registry fields
        Map<String,String> rf = new HashMap<>();
        rf.put("BLOCK","f");
        f.put("net/minecraft/core/Registry",rf);

        // Registries fields
        Map<String,String> regs = new HashMap<>();
        regs.put("BLOCK","b");
        f.put("net/minecraft/core/registries/Registries",regs);

        // Blocks fields
        Map<String,String> blks = new HashMap<>();
        blks.put("WATER","O");
        f.put("net/minecraft/world/level/block/Blocks",blks);

        // Fluids fields
        Map<String,String> fls = new HashMap<>();
        fls.put("WATER","c");
        f.put("net/minecraft/world/level/material/Fluids",fls);

        // ChatType fields
        Map<String,String> ct = new HashMap<>();
        ct.put("CHAT","a"); ct.put("SYSTEM","b");
        f.put("net/minecraft/network/chat/ChatType",ct);

        // InteractionResult fields
        Map<String,String> ir = new HashMap<>();
        ir.put("PASS","a"); ir.put("SUCCESS","b"); ir.put("FAIL","c");
        f.put("net/minecraft/world/InteractionResult",ir);

        // ServerPlayer fields
        Map<String,String> spf = new HashMap<>();
        spf.put("connection","b");
        f.put("net/minecraft/server/level/ServerPlayer",spf);

        MOJANG_FIELDS = Collections.unmodifiableMap(f);

        // -- Hardcoded intermediary method/field names (method_NNNNN/field_NNNNN) --
        // Keyed by mojang class name (slashed). Cross-referenced from 1.18.2
        // intermediary-v2.tiny (ProGuard→intermediary). These are version-locked
        // to 1.18.2 and always correct on intermediary-runtime servers.
        Map<String,Map<String,String>> im = new HashMap<>();

        Map<String,String> MinecraftServer_im = new HashMap<>();
        MinecraftServer_im.put("createCommandSourceStack","method_29735");
        MinecraftServer_im.put("getAllLevels","method_3831");
        MinecraftServer_im.put("getCommandManager","method_3772");
        MinecraftServer_im.put("getCommandSource","method_29735");
        MinecraftServer_im.put("getCommands","method_3772");
        MinecraftServer_im.put("getLevel","method_3864");
        MinecraftServer_im.put("getPlayerList","method_29735");
        MinecraftServer_im.put("getPlayerManager","method_29735");
        MinecraftServer_im.put("getTickCount","method_3796");
        MinecraftServer_im.put("getTicks","method_3796");
        MinecraftServer_im.put("getWorld","method_3864");
        MinecraftServer_im.put("getWorlds","method_3831");
        im.put("net/minecraft/server/MinecraftServer",MinecraftServer_im);

        Map<String,String> ServerPlayer_im = new HashMap<>();
        ServerPlayer_im.put("getX","method_14239");
        ServerPlayer_im.put("getY","method_14244");
        ServerPlayer_im.put("sendMessage","method_32748");
        ServerPlayer_im.put("sendSystemMessage","method_32748");
        im.put("net/minecraft/server/level/ServerPlayer",ServerPlayer_im);

        Map<String,String> ServerLevel_im = new HashMap<>();
        ServerLevel_im.put("dimension","method_29198");
        ServerLevel_im.put("getBlockState","method_14177");
        ServerLevel_im.put("getRegistryKey","method_29198");
        im.put("net/minecraft/server/level/ServerLevel",ServerLevel_im);

        Map<String,String> PlayerList_im = new HashMap<>();
        PlayerList_im.put("broadcast","method_14596");
        PlayerList_im.put("broadcastMessage","method_14596");
        PlayerList_im.put("broadcastSystemMessage","method_14596");
        PlayerList_im.put("getPlayer","method_14596");
        PlayerList_im.put("getPlayerByName","method_14609");
        PlayerList_im.put("getPlayerList","method_14614");
        PlayerList_im.put("getPlayers","method_14614");
        PlayerList_im.put("isOp","method_14609");
        im.put("net/minecraft/server/players/PlayerList",PlayerList_im);

        Map<String,String> Entity_im = new HashMap<>();
        Entity_im.put("getName","method_37908");
        Entity_im.put("getUUID","method_5845");
        Entity_im.put("getUuid","method_5845");
        Entity_im.put("getX","method_5878");
        Entity_im.put("getY","method_5626");
        Entity_im.put("getZ","method_5794");
        Entity_im.put("refreshPositionAfterTeleport","method_23323");
        Entity_im.put("setPos","method_23323");
        im.put("net/minecraft/world/entity/Entity",Entity_im);

        Map<String,String> LightningBolt_im = new HashMap<>();
        LightningBolt_im.put("setPos","method_37219");
        im.put("net/minecraft/world/entity/LightningBolt",LightningBolt_im);

        Map<String,String> Level_im = new HashMap<>();
        Level_im.put("getBlockState","method_8496");
        im.put("net/minecraft/world/level/Level",Level_im);

        Map<String,String> Registry_im = new HashMap<>();
        Registry_im.put("getKey","method_40269");
        Registry_im.put("getResourceKey","method_39667");
        im.put("net/minecraft/core/Registry",Registry_im);

        Map<String,String> Block_im = new HashMap<>();
        Block_im.put("builtInRegistryHolder","method_33615");
        im.put("net/minecraft/world/level/block/Block",Block_im);

        Map<String,String> CommandSourceStack_im = new HashMap<>();
        CommandSourceStack_im.put("getPlayer","method_9207");
        CommandSourceStack_im.put("getServer","method_9211");
        CommandSourceStack_im.put("hasPermission","method_9224");
        CommandSourceStack_im.put("hasPermissionLevel","method_9224");
        CommandSourceStack_im.put("sendMessage","method_9209");
        CommandSourceStack_im.put("sendSuccess","method_9209");
        CommandSourceStack_im.put("sendSystemMessage","method_9209");
        CommandSourceStack_im.put("withSilent","method_9229");
        CommandSourceStack_im.put("withSuppressedOutput","method_9229");
        im.put("net/minecraft/commands/CommandSourceStack",CommandSourceStack_im);

        Map<String,String> ServerGamePacketListenerImpl_im = new HashMap<>();
        ServerGamePacketListenerImpl_im.put("disconnect","method_31276");
        ServerGamePacketListenerImpl_im.put("onDisconnect","method_31276");
        im.put("net/minecraft/server/network/ServerGamePacketListenerImpl",ServerGamePacketListenerImpl_im);

        INTERMEDIARY_METHODS = Collections.unmodifiableMap(im);

        Map<String,Map<String,String>> iF = new HashMap<>();

        Map<String,String> EntityType_iF = new HashMap<>();
        EntityType_iF.put("LIGHTNING_BOLT","field_6139");
        iF.put("net/minecraft/world/entity/EntityType",EntityType_iF);

        Map<String,String> BuiltInRegistries_iF = new HashMap<>();
        BuiltInRegistries_iF.put("BLOCK","field_35314");
        iF.put("net/minecraft/core/registries/BuiltInRegistries",BuiltInRegistries_iF);

        Map<String,String> Registry_iF = new HashMap<>();
        Registry_iF.put("BLOCK","field_25103");
        iF.put("net/minecraft/core/Registry",Registry_iF);

        Map<String,String> Blocks_iF = new HashMap<>();
        Blocks_iF.put("WATER","field_10511");
        iF.put("net/minecraft/world/level/block/Blocks",Blocks_iF);

        Map<String,String> Fluids_iF = new HashMap<>();
        Fluids_iF.put("WATER","field_15910");
        iF.put("net/minecraft/world/level/material/Fluids",Fluids_iF);

        Map<String,String> ChatType_iF = new HashMap<>();
        ChatType_iF.put("CHAT","field_11737"); ChatType_iF.put("SYSTEM","field_11735");
        iF.put("net/minecraft/network/chat/ChatType",ChatType_iF);

        Map<String,String> InteractionResult_iF = new HashMap<>();
        InteractionResult_iF.put("PASS","field_5812"); InteractionResult_iF.put("SUCCESS","field_21466");
        InteractionResult_iF.put("FAIL","field_33562");
        iF.put("net/minecraft/world/InteractionResult",InteractionResult_iF);

        Map<String,String> ServerPlayer_iF = new HashMap<>();
        ServerPlayer_iF.put("connection","field_13987");
        iF.put("net/minecraft/server/level/ServerPlayer",ServerPlayer_iF);

        INTERMEDIARY_FIELDS = Collections.unmodifiableMap(iF);

        // Build reverse map: runtime class name (slashed) → Mojang class name (slashed)
        // On production (intermediary), cls.getName() returns intermediary names like
        // net.minecraft.class_3222, but MOJANG_METHODS/MOJANG_FIELDS are keyed by Mojang
        // names like net/minecraft/server/level/ServerPlayer. This table bridges the gap.
        Map<String,String> rtm = new HashMap<>();
        if (!IS_DEV) {
            MappingResolver mr = FabricLoader.getInstance().getMappingResolver();
            for (Map.Entry<String,String> e : MOJANG_CLASS_TO_OFFICIAL.entrySet()) {
                String mojang = e.getKey();
                String official = e.getValue();
                try {
                    String intermediary = mr.unmapClassName("official", official);
                    if (!intermediary.equals(official)) {
                        rtm.put(intermediary, mojang);
                    }
                } catch (Throwable t) {}
                rtm.put(mojang, mojang);
            }
        } else {
            for (String mojang : MOJANG_CLASS_TO_OFFICIAL.keySet()) {
                rtm.put(mojang, mojang);
            }
        }
        // Hardcoded intermediary→mojang fallbacks (for when MappingResolver is unavailable
        // or the intermediary names differ from what the resolver returns)
        rtm.put("net/minecraft/class_3218", "net/minecraft/server/level/ServerLevel");
        rtm.put("net/minecraft/class_3222", "net/minecraft/server/level/ServerPlayer");
        rtm.put("net/minecraft/class_3225", "net/minecraft/server/level/ServerPlayerGameMode");
        rtm.put("net/minecraft/class_3324", "net/minecraft/server/players/PlayerList");
        rtm.put("net/minecraft/class_3244", "net/minecraft/server/network/ServerGamePacketListenerImpl");
        rtm.put("net/minecraft/class_2168", "net/minecraft/commands/CommandSourceStack");
        rtm.put("net/minecraft/class_1538", "net/minecraft/world/entity/LightningBolt");
        rtm.put("net/minecraft/class_1299", "net/minecraft/world/entity/EntityType");
        rtm.put("net/minecraft/class_1297", "net/minecraft/world/entity/Entity");
        rtm.put("net/minecraft/class_1657", "net/minecraft/world/entity/player/Player");
        rtm.put("net/minecraft/class_1937", "net/minecraft/world/level/Level");
        rtm.put("net/minecraft/class_2248", "net/minecraft/world/level/block/Block");
        rtm.put("net/minecraft/class_2246", "net/minecraft/world/level/block/Blocks");
        rtm.put("net/minecraft/class_2680", "net/minecraft/world/level/block/state/BlockState");
        rtm.put("net/minecraft/class_2404", "net/minecraft/world/level/block/LiquidBlock");
        rtm.put("net/minecraft/class_3612", "net/minecraft/world/level/material/Fluids");
        rtm.put("net/minecraft/class_3611", "net/minecraft/world/level/material/Fluid");
        rtm.put("net/minecraft/class_1934", "net/minecraft/world/level/GameType");
        rtm.put("net/minecraft/class_2338", "net/minecraft/core/BlockPos");
        rtm.put("net/minecraft/class_2378", "net/minecraft/core/Registry");
        rtm.put("net/minecraft/class_7923", "net/minecraft/core/registries/Registries");
        rtm.put("net/minecraft/class_7922", "net/minecraft/core/registries/BuiltInRegistries");
        rtm.put("net/minecraft/class_2561", "net/minecraft/network/chat/Component");
        rtm.put("net/minecraft/class_2556", "net/minecraft/network/chat/ChatType");
        rtm.put("net/minecraft/class_5250", "net/minecraft/network/chat/MutableComponent");
        rtm.put("net/minecraft/class_1269", "net/minecraft/world/InteractionResult");
        rtm.put("net/minecraft/class_243", "net/minecraft/world/phys/Vec3");
        rtm.put("net/minecraft/class_241", "net/minecraft/world/phys/Vec2");
        rtm.put("net/minecraft/class_3442", "net/minecraft/stats/ServerStatsCounter");
        RUNTIME_TO_MOJANG = Collections.unmodifiableMap(rtm);
    }

    private FabricReflection() {}

    // Thread-safe debug log wrapper
    private static void log(String msg) { System.out.println("[MinerTrack:Reflection] " + msg); }

    static void setCachedServer(Object server) { cachedServer = server; }

    static Object getServer() {
        if (cachedServer != null) return cachedServer;
        return callStatic("net.minecraft.server.MinecraftServer","getServer",new Class<?>[0],new Object[0]);
    }

    static Object callMigrated(Object target, String mc26Method, String legacyMethod,
                               Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try { Method mt = findMethod(target.getClass(), mc26Method, paramTypes); if (mt != null) { mt.setAccessible(true); return mt.invoke(target, args); } }
        catch (Throwable t) {}
        return call(target, legacyMethod, paramTypes, args);
    }

    private static final Map<String, String> API_MIGRATIONS = new HashMap<>();
    static {
        API_MIGRATIONS.put("getPlayerManager","getPlayerList");
        API_MIGRATIONS.put("getCommandManager","getCommands");
        API_MIGRATIONS.put("getWorlds","getAllLevels");
        API_MIGRATIONS.put("getWorld","getLevel");
        API_MIGRATIONS.put("getCommandSource","createCommandSourceStack");
        API_MIGRATIONS.put("withSilent","withSuppressedOutput");
        API_MIGRATIONS.put("getTicks","getTickCount");
        API_MIGRATIONS.put("isExecutedByPlayer","isPlayer");
        API_MIGRATIONS.put("hasPermissionLevel","hasPermission");
        API_MIGRATIONS.put("executeWithPrefix","performCommand");
    }

    static Method findMethodWithMigration(Class<?> cls, String methodName, Class<?>[] paramTypes) {
        Method mt = findMethod(cls, methodName, paramTypes); if (mt != null) return mt;
        String migrated = API_MIGRATIONS.get(methodName);
        if (migrated != null) return findMethod(cls, migrated, paramTypes);
        return null;
    }

    // -- Reflection primitives --

    static Object callStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        try { Class<?> cls = forName(className); if (cls == null) return null; Method mt = findMethod(cls, methodName, paramTypes); if (mt == null) return null; if (!java.lang.reflect.Modifier.isStatic(mt.getModifiers())) return null; mt.setAccessible(true); return mt.invoke(null, args); }
        catch (IllegalAccessException | InvocationTargetException e) { return null; }
    }

    static Object call(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try { Method mt = findMethod(target.getClass(), methodName, paramTypes); if (mt == null) return null; mt.setAccessible(true); return mt.invoke(target, args); }
        catch (IllegalAccessException | InvocationTargetException e) { return null; }
    }

    static Object callAny(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        return call(target, methodName, paramTypes, args);
    }

    @SuppressWarnings("unchecked")
    static <T> T getField(Object target, String fieldName) {
        if (target == null) return null;
        try { Field f = findField(target.getClass(), fieldName); if (f == null) return null; f.setAccessible(true); return (T) f.get(target); }
        catch (IllegalAccessException e) { return null; }
    }

    // -- Version-aware helpers --

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
        try { Method m = findMethod(source.getClass(), "getString", new Class<?>[0]); if (m != null) { Object r = m.invoke(source); if (r instanceof String) return (String) r; } }
        catch (Throwable ignored) {}
        // Scan toString() after a deserialization-friendly invocation fails
        try { Method m = findMethod(source.getClass(), "getString", new Class<?>[0]); if (m == null) { Object ts = source.getClass().getMethod("toString").invoke(source); if (ts instanceof String) return (String) ts; } } catch (Throwable ignored2) {}
        String s = source.toString();
        if (s.startsWith("literal(") && s.endsWith(")")) return s.substring("literal(".length(), s.length() - 1);
        return s;
    }

    static String getBlockId(Object block) {
        if (block == null) return null;
        Object blockRegistry = null;
        try { Class<?> birCls = forName("net.minecraft.core.registries.BuiltInRegistries"); if (birCls != null) { Field f = findField(birCls, "BLOCK"); if (f != null) blockRegistry = f.get(null); } } catch (Throwable t) {}
        if (blockRegistry == null) { try { Class<?> regCls = forName("net.minecraft.core.Registry"); if (regCls != null) { Field f = findField(regCls, "BLOCK"); if (f != null) blockRegistry = f.get(null); } } catch (Throwable t) {} }
        if (blockRegistry == null) { try { Class<?> regsCls = forName("net.minecraft.core.registries.Registries"); if (regsCls == null) regsCls = forName("net.minecraft.registry.Registries"); if (regsCls != null) { Field f = findField(regsCls, "BLOCK"); if (f != null) { Object mr = f.get(null); if (mr != null) { try { Method m = findMethod(mr.getClass(), "getKey", new Class<?>[]{Object.class}); if (m != null) blockRegistry = mr; } catch (Throwable e) {} } } } } catch (Throwable t) {} }
        if (blockRegistry != null) {
            try { Method m = findMethod(blockRegistry.getClass(), "getKey", new Class<?>[]{Object.class}); if (m != null) { Object id = m.invoke(blockRegistry, block); if (id != null) { String s = readString(id); if (s != null) return s; } } } catch (Throwable t) {}
            try { Method m = findMethod(blockRegistry.getClass(), "getResourceKey", new Class<?>[]{Object.class}); if (m != null) { Object rv = m.invoke(blockRegistry, block); if (rv instanceof java.util.Optional) { java.util.Optional<?> opt = (java.util.Optional<?>) rv; if (opt.isPresent()) { Object key = opt.get(); Object loc = callResourceKeyValue(key); if (loc != null) { String s = readString(loc); if (s != null) return s; } } } } } catch (Throwable t) {}
        }
        // Last resort: scan all methods in registry for one that takes Object and returns Identifier-like
        if (blockRegistry != null) {
            try {
                for (Method m : blockRegistry.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == Object.class) {
                        try { Object id = m.invoke(blockRegistry, block); if (id != null) { String s = readString(id); if (s != null && s.contains(":")) return s; } } catch (Throwable t2) {}
                    }
                }
            } catch (Throwable t) {}
        }
        try { Object holder = callAny(block, "builtInRegistryHolder", new Class<?>[0], new Object[0]); if (holder != null) { Object key = callAny(holder, "getKey", new Class<?>[0], new Object[0]); if (key != null) { Object loc = callResourceKeyValue(key); if (loc != null) { String s = readString(loc); if (s != null) return s; } } } } catch (Throwable t) {}
        return null;
    }

    static Object newInstance(String className, Class<?>[] paramTypes, Object[] args) {
        try { Class<?> cls = forName(className); if (cls == null) return null; Constructor<?> c = cls.getDeclaredConstructor(paramTypes); c.setAccessible(true); return c.newInstance(args); }
        catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) { return null; }
    }

    // -- Class resolution --

    static Class<?> forName(String className) {
        try { return Class.forName(className); } catch (ClassNotFoundException e) { return tryMcMigration(className); }
    }

    private static Class<?> tryMcMigration(String className) {
        // Try mojang→intermediary via official table + MappingResolver
        if (!IS_DEV) {
            String key = className.replace('.','/');
            String official = MOJANG_CLASS_TO_OFFICIAL.get(key);
            if (official != null) {
                try {
                    String intermediary = resolver().unmapClassName("official", official);
                    if (!official.equals(intermediary)) {
                        Class<?> cls = tryLoad(intermediary.replace('/','.'));
                        if (cls != null) return cls;
                    }
                } catch (Throwable t) {}
            }
        }

        // Fallback: try intermediary class_NNNN names directly
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

    // -- Method lookup --

    static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null) return null;
        String className = cls.getName();

        // 1. Try mojang name directly (dev/named, MC 26+)
        try { return cls.getMethod(name, paramTypes); } catch (NoSuchMethodException ignored) {}
        try { Method mt = cls.getDeclaredMethod(name, paramTypes); mt.setAccessible(true); return mt; } catch (NoSuchMethodException ignored) {}

        // 2. Try hardcoded intermediary name (method_NNNNN from 1.18.2 cross-reference)
        String mk = RUNTIME_TO_MOJANG.get(className.replace('.','/'));
        if (mk != null) {
            Map<String,String> imap = INTERMEDIARY_METHODS.get(mk);
            if (imap != null) {
                String interName = imap.get(name);
                if (interName != null) {
                    try { Method mt = cls.getMethod(interName, paramTypes); mt.setAccessible(true); if (DEBUG_REFLECTION) log("M-INTER-GET " + className + "." + name + " -> " + interName); return mt; } catch (NoSuchMethodException ignored) {}
                    try { Method mt = cls.getDeclaredMethod(interName, paramTypes); mt.setAccessible(true); if (DEBUG_REFLECTION) log("M-INTER-DECL " + className + "." + name + " -> " + interName); return mt; } catch (NoSuchMethodException ignored) {}
                }
            }
        }

        // 3. Try ProGuard (official) name directly
        if (mk != null) {
            Map<String,String> mMap = MOJANG_METHODS.get(mk);
            if (mMap != null) {
                String pg = mMap.get(name);
                if (pg != null) {
                    try { Method mt = cls.getMethod(pg, paramTypes); mt.setAccessible(true); if (DEBUG_REFLECTION) log("M-PG-GET " + className + "." + name + " -> " + pg); return mt; } catch (NoSuchMethodException ignored) {}
                    try { Method mt = cls.getDeclaredMethod(pg, paramTypes); mt.setAccessible(true); if (DEBUG_REFLECTION) log("M-PG-DECL " + className + "." + name + " -> " + pg); return mt; } catch (NoSuchMethodException ignored) {}
                }
            }
        }

        if (DEBUG_REFLECTION) log("M-MISS " + className + "." + name + " mk=" + mk);

        // 4. Superclass traversal
        Class<?> sup = cls.getSuperclass();
        if (sup != null && sup != Object.class) {
            return findMethod(sup, name, paramTypes);
        }
        return null;
    }

    // -- Field lookup --

    private static Field findField(Class<?> cls, String name) {
        if (cls == null) return null;

        // 1. Try mojang name directly
        try { return cls.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        try { return cls.getField(name); } catch (NoSuchFieldException ignored) {}

        // 2. Try hardcoded intermediary name
        String mk = RUNTIME_TO_MOJANG.get(cls.getName().replace('.','/'));
        if (mk != null) {
            Map<String,String> ifMap = INTERMEDIARY_FIELDS.get(mk);
            if (ifMap != null) {
                String interName = ifMap.get(name);
                if (interName != null) {
                    try { return cls.getDeclaredField(interName); } catch (NoSuchFieldException ignored) {}
                    try { return cls.getField(interName); } catch (NoSuchFieldException ignored) {}
                }
            }
        }

        // 3. Try ProGuard (official) name directly
        if (mk != null) {
            Map<String,String> fMap = MOJANG_FIELDS.get(mk);
            if (fMap != null) {
                String pg = fMap.get(name);
                if (pg != null) {
                    try { return cls.getDeclaredField(pg); } catch (NoSuchFieldException ignored) {}
                    try { return cls.getField(pg); } catch (NoSuchFieldException ignored) {}
                }
            }
        }

        // Superclass traversal
        Class<?> superCls = cls.getSuperclass();
        if (superCls != null && superCls != Object.class) return findField(superCls, name);
        return null;
    }
}