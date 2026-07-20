package link.star_dust.MinerTrack.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

/**
 * Centralized runtime-name constants for all Minecraft classes, methods and
 * fields accessed via reflection.
 *
 * <p>Fabric remaps the Minecraft JAR to <b>intermediary</b> names in
 * production; on MC 26+ / dev the named/mojang name is used directly.
 * Each constant is resolved at class-load time: the {@link MappingResolver}
 * is tried first (dev / MC 26+), falling back to a hardcoded
 * intermediary name cross-referenced from the 1.18.2 {@code .tiny} mappings.
 */
final class FabricReflectionConstants {

    private FabricReflectionConstants() {}

    /* ---- empty arrays shared with FabricReflection ---- */

    static final Class<?>[] NO_ARGS = new Class<?>[0];
    static final Object[]   NO_VALS = new Object[0];

    /* ---- mapping resolver ---- */

    private static final MappingResolver MR;
    private static final boolean IS_DEV;
    static {
        FabricLoader fl = FabricLoader.getInstance();
        MR = fl.getMappingResolver();
        IS_DEV = fl.isDevelopmentEnvironment();
    }

    // ==================================================================
    // Resolution helpers
    // ==================================================================

    /**
     * Resolve a method name to its runtime form.
     *
     * <ol>
     *   <li>Try {@code named→intermediary} via {@link MappingResolver#mapMethodName}
     *       (works in dev / MC 26+ where "named" namespace is available).</li>
     *   <li>Hardcoded intermediary fallback from the 1.18.2
     *       {@code intermediary-v2.tiny} cross-reference.</li>
     *   <li>Return the named name unchanged (last resort).</li>
     * </ol>
     */
    private static String im(String namedOwner, String named,
                             String desc, String interFallback) {
        // 1) named→intermediary (dev / MC 26+)
        try {
            String r = MR.mapMethodName("named", namedOwner, named, desc);
            if (r != null && !r.equals(named)) return r;
        } catch (Throwable ignore) {}
        // 2) hardcoded intermediary (production 1.18–1.21)
        if (!IS_DEV && interFallback != null) return interFallback;
        // 3) give back the original name
        return named;
    }

    /** Same as {@link #im} but for fields. */
    private static String ifd(String namedOwner, String named,
                              String desc, String interFallback) {
        try {
            String r = MR.mapFieldName("named", namedOwner, named, desc);
            if (r != null && !r.equals(named)) return r;
        } catch (Throwable ignore) {}
        if (!IS_DEV && interFallback != null) return interFallback;
        return named;
    }

    // ==================================================================
    // CLASS NAMES  (mojang/named — forName handles intermediary fallback)
    // ==================================================================

    static final String CLS_MINECRAFT_SERVER         = "net.minecraft.server.MinecraftServer";
    static final String CLS_SERVER_PLAYER             = "net.minecraft.server.level.ServerPlayer";
    static final String CLS_SERVER_LEVEL              = "net.minecraft.server.level.ServerLevel";
    static final String CLS_PLAYER_LIST               = "net.minecraft.server.players.PlayerList";
    static final String CLS_COMMAND_SOURCE_STACK      = "net.minecraft.commands.CommandSourceStack";
    static final String CLS_LIGHTNING_BOLT            = "net.minecraft.world.entity.LightningBolt";
    static final String CLS_ENTITY_TYPE               = "net.minecraft.world.entity.EntityType";
    static final String CLS_ENTITY                    = "net.minecraft.world.entity.Entity";
    static final String CLS_PLAYER                    = "net.minecraft.world.entity.player.Player";
    static final String CLS_LEVEL                     = "net.minecraft.world.level.Level";
    static final String CLS_BLOCK                     = "net.minecraft.world.level.block.Block";
    static final String CLS_BLOCKS                    = "net.minecraft.world.level.block.Blocks";
    static final String CLS_BLOCK_STATE               = "net.minecraft.world.level.block.state.BlockState";
    static final String CLS_LIQUID_BLOCK              = "net.minecraft.world.level.block.LiquidBlock";
    static final String CLS_FLUIDS                    = "net.minecraft.world.level.material.Fluids";
    static final String CLS_FLUID                     = "net.minecraft.world.level.material.Fluid";
    static final String CLS_GAME_TYPE                 = "net.minecraft.world.level.GameType";
    static final String CLS_BLOCK_POS                 = "net.minecraft.core.BlockPos";
    static final String CLS_REGISTRY                  = "net.minecraft.core.Registry";
    static final String CLS_BUILT_IN_REGISTRIES       = "net.minecraft.core.registries.BuiltInRegistries";
    static final String CLS_REGISTRIES                = "net.minecraft.core.registries.Registries";
    static final String CLS_COMPONENT                 = "net.minecraft.network.chat.Component";
    static final String CLS_CHAT_TYPE                 = "net.minecraft.network.chat.ChatType";
    static final String CLS_MUTABLE_COMPONENT         = "net.minecraft.network.chat.MutableComponent";
    static final String CLS_INTERACTION_RESULT        = "net.minecraft.world.InteractionResult";
    static final String CLS_VEC3                      = "net.minecraft.world.phys.Vec3";
    static final String CLS_SERVER_GAME_PACKET_LISTENER = "net.minecraft.server.network.ServerGamePacketListenerImpl";

    // ==================================================================
    // METHOD NAMES
    //   im(namedOwner, namedName, descriptor, intermediaryFallback)
    // ==================================================================

    // -- MinecraftServer -------------------------------------------------
    static final String M_GET_PLAYER_LIST      = im("net.minecraft.server.MinecraftServer", "getPlayerList",          "()Lnet/minecraft/server/players/PlayerList;",                  "method_37330");
    static final String M_GET_ALL_LEVELS       = im("net.minecraft.server.MinecraftServer", "getAllLevels",           "()Ljava/lang/Iterable;",                                       "method_3831");
    static final String M_GET_COMMANDS         = im("net.minecraft.server.MinecraftServer", "getCommands",           "()Lnet/minecraft/commands/Commands;",                          "method_3772");
    static final String M_CREATE_COMMAND_SOURCE_STACK = im("net.minecraft.server.MinecraftServer", "createCommandSourceStack", "()Lnet/minecraft/commands/CommandSourceStack;",       "method_37330");
    static final String M_GET_TICK_COUNT       = im("net.minecraft.server.MinecraftServer", "getTickCount",          "()I",                                                          "method_3796");
    static final String M_GET_LEVEL            = im("net.minecraft.server.MinecraftServer", "getLevel",              "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;", "method_3864");
    static final String M_SEND_SYSTEM_MSG_SRV  = im("net.minecraft.server.MinecraftServer", "sendSystemMessage",     "(Lnet/minecraft/network/chat/Component;)V",                     "method_37330");

    // -- ServerPlayer ----------------------------------------------------
    static final String M_GET_NAME             = im("net.minecraft.server.level.ServerPlayer", "getName",             "()Lnet/minecraft/network/chat/Component;",                     "method_37908");
    static final String M_GET_UUID             = im("net.minecraft.server.level.ServerPlayer", "getUUID",             "()Ljava/util/UUID;",                                           "method_5845");
    static final String M_GET_X                = im("net.minecraft.world.entity.Entity",       "getX",                "()D",                                                          "method_5878");
    static final String M_GET_Y                = im("net.minecraft.world.entity.Entity",       "getY",                "()D",                                                          "method_5626");
    static final String M_GET_Z                = im("net.minecraft.world.entity.Entity",       "getZ",                "()D",                                                          "method_5794");
    static final String M_GET_GAME_PROFILE     = im("net.minecraft.server.level.ServerPlayer", "getGameProfile",      "()Lcom/mojang/authlib/GameProfile;",                           "method_5809");
    static final String M_SEND_SYSTEM_MSG_PLR  = im("net.minecraft.server.level.ServerPlayer", "sendSystemMessage",   "(Lnet/minecraft/network/chat/Component;)V",                     "method_32748");
    static final String M_SEND_MSG_PLR_CMP     = im("net.minecraft.server.level.ServerPlayer", "sendMessage",         "(Lnet/minecraft/network/chat/Component;)V",                     "method_32748");
    static final String M_SEND_MSG_PLR_CMP_UUID = im("net.minecraft.server.level.ServerPlayer", "sendMessage",        "(Lnet/minecraft/network/chat/Component;Ljava/util/UUID;)V",     "method_32748");
    static final String M_LEVEL                = im("net.minecraft.server.level.ServerPlayer", "level",               "()Lnet/minecraft/world/level/Level;",                           "method_37908");

    // -- Level -----------------------------------------------------------
    static final String M_GET_BLOCK_STATE      = im("net.minecraft.world.level.Level",        "getBlockState",        "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", "method_8496");
    static final String M_DIMENSION            = im("net.minecraft.world.level.Level",        "dimension",            "()Lnet/minecraft/resources/ResourceKey;",                                           "method_27983");
    static final String M_IS_CLIENT_SIDE       = im("net.minecraft.world.level.Level",        "isClientSide",         "()Z",                                                                              "method_8608");

    // -- PlayerList ------------------------------------------------------
    static final String M_GET_PLAYER_UUID      = im("net.minecraft.server.players.PlayerList","getPlayer",            "(Ljava/util/UUID;)Lnet/minecraft/server/level/ServerPlayer;",   "method_14596");
    static final String M_GET_PLAYER_BY_NAME   = im("net.minecraft.server.players.PlayerList","getPlayerByName",      "(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;", "method_14609");
    static final String M_GET_PLAYERS          = im("net.minecraft.server.players.PlayerList","getPlayers",           "()Ljava/util/List;",                                           "method_14614");
    static final String M_IS_OP                = im("net.minecraft.server.players.PlayerList","isOp",                 "(Lcom/mojang/authlib/GameProfile;)Z",                          "method_14609");
    static final String M_BROADCAST_SYSTEM_MSG = im("net.minecraft.server.players.PlayerList","broadcastSystemMessage","(Lnet/minecraft/network/chat/Component;Z)V",                   "method_14596");
    static final String M_BROADCAST_MSG        = im("net.minecraft.server.players.PlayerList","broadcastMessage",     "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/ChatType;Ljava/util/UUID;)V", "method_14596");
    static final String M_BROADCAST            = im("net.minecraft.server.players.PlayerList","broadcast",            "(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;ZZ)V",            "method_14596");

    // -- Entity ----------------------------------------------------------
    static final String M_SET_POS              = im("net.minecraft.world.entity.Entity",       "setPos",               "(DDD)V",                                                       "method_23323");

    // -- BlockState ------------------------------------------------------
    static final String M_GET_BLOCK            = im("net.minecraft.world.level.block.state.BlockState", "getBlock",    "()Lnet/minecraft/world/level/block/Block;",                    "method_17049");
    static final String M_GET_FLUID_STATE      = im("net.minecraft.world.level.block.state.BlockState", "getFluidState","()Lnet/minecraft/world/level/material/FluidState;",           "method_17772");

    // -- FluidState / Fluid ----------------------------------------------
    static final String M_GET_FLUID            = im("net.minecraft.world.level.material.FluidState", "getFluid",       "()Lnet/minecraft/world/level/material/Fluid;",                 "method_15782");
    static final String M_GET_STILL            = im("net.minecraft.world.level.material.Fluid",      "getStill",       "()Lnet/minecraft/world/level/material/FluidState;",           "method_15782");

    // -- ServerGamePacketListenerImpl ------------------------------------
    static final String M_DISCONNECT           = im("net.minecraft.server.network.ServerGamePacketListenerImpl", "disconnect", "(Lnet/minecraft/network/chat/Component;)V",          "method_33898");

    // -- Commands --------------------------------------------------------
    static final String M_PERFORM_COMMAND      = im("net.minecraft.commands.Commands",           "performCommand",       "(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)I",  "method_3772");
    static final String M_PERFORM_PREFIXED_CMD = im("net.minecraft.commands.Commands",           "performPrefixedCommand","(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V",  "method_3772");

    // -- CommandSourceStack ----------------------------------------------
    static final String M_GET_SERVER           = im("net.minecraft.commands.CommandSourceStack", "getServer",           "()Lnet/minecraft/server/MinecraftServer;",                                  "method_9211");
    static final String M_IS_PLAYER            = im("net.minecraft.commands.CommandSourceStack", "isPlayer",            "()Z",                                                                      "method_9224");
    static final String M_GET_PLAYER           = im("net.minecraft.commands.CommandSourceStack", "getPlayer",           "()Lnet/minecraft/server/level/ServerPlayer;",                               "method_9207");
    static final String M_GET_ENTITY           = im("net.minecraft.commands.CommandSourceStack", "getEntity",           "()Lnet/minecraft/world/entity/Entity;",                                     "method_9205");
    static final String M_SEND_SYSTEM_MSG_CSS  = im("net.minecraft.commands.CommandSourceStack", "sendSystemMessage",   "(Lnet/minecraft/network/chat/Component;)V",                                 "method_9209");
    static final String M_SEND_MSG_CSS         = im("net.minecraft.commands.CommandSourceStack", "sendMessage",         "(Lnet/minecraft/network/chat/Component;)V",                                 "method_9209");
    static final String M_WITH_SUPPRESSED_OUTPUT = im("net.minecraft.commands.CommandSourceStack", "withSuppressedOutput","()Lnet/minecraft/commands/CommandSourceStack;",                            "method_9229");
    static final String M_HAS_PERMISSION       = im("net.minecraft.commands.CommandSourceStack", "hasPermission",       "(I)Z",                                                                     "method_9224");

    // ==================================================================
    // FIELD NAMES
    //   ifd(namedOwner, namedName, descriptor, intermediaryFallback)
    // ==================================================================

    static final String F_ENTITY_TYPE_LIGHTNING = ifd("net.minecraft.world.entity.EntityType",   "LIGHTNING_BOLT", "Lnet/minecraft/world/entity/EntityType;", "field_6139");
    static final String F_BUILTIN_BLOCK         = ifd("net.minecraft.core.registries.BuiltInRegistries", "BLOCK", "Lnet/minecraft/core/DefaultedRegistry;",  "field_35314");
    static final String F_REGISTRY_BLOCK        = ifd("net.minecraft.core.Registry",             "BLOCK",          "Lnet/minecraft/core/DefaultedRegistry;",  "field_25103");
    static final String F_REGISTRIES_BLOCK      = ifd("net.minecraft.core.registries.Registries","BLOCK",          "Lnet/minecraft/resources/ResourceKey;",   "field_25103");
    static final String F_BLOCKS_WATER          = ifd("net.minecraft.world.level.block.Blocks",  "WATER",          "Lnet/minecraft/world/level/block/Block;", "field_10511");
    static final String F_FLUIDS_WATER          = ifd("net.minecraft.world.level.material.Fluids","WATER",         "Lnet/minecraft/world/level/material/Fluid;","field_15910");
    static final String F_CHAT_TYPE_CHAT        = ifd("net.minecraft.network.chat.ChatType",     "CHAT",           "Lnet/minecraft/network/chat/ChatType;",   "field_11737");
    static final String F_CHAT_TYPE_SYSTEM      = ifd("net.minecraft.network.chat.ChatType",     "SYSTEM",         "Lnet/minecraft/network/chat/ChatType;",   "field_11735");
    static final String F_INTERACTION_PASS      = ifd("net.minecraft.world.InteractionResult",   "PASS",           "Lnet/minecraft/world/InteractionResult;", "field_5812");
    static final String F_INTERACTION_SUCCESS   = ifd("net.minecraft.world.InteractionResult",   "SUCCESS",        "Lnet/minecraft/world/InteractionResult;", "field_21466");
    static final String F_INTERACTION_FAIL      = ifd("net.minecraft.world.InteractionResult",   "FAIL",           "Lnet/minecraft/world/InteractionResult;", "field_33562");
    static final String F_CONNECTION            = ifd("net.minecraft.server.level.ServerPlayer", "connection",     "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;", "field_13987");

    // ==================================================================
    // Runtime method name redirector
    //
    // On production servers callers may pass mojang method names (e.g.
    // "dimension") but the runtime class only knows the intermediary name
    // (e.g. "method_27983").  This map bridges the gap at lookup time.
    // ==================================================================

    private static final java.util.Map<String,String> METHOD_REDIRECT = new java.util.HashMap<>();
    static {
        // Grouped by Mojang class → (mojangName → resolvedName)
        // Level
        putM("dimension",           M_DIMENSION);
        putM("getBlockState",       M_GET_BLOCK_STATE);
        putM("isClientSide",        M_IS_CLIENT_SIDE);
        putM("getRegistryKey",      M_DIMENSION);          // 1.18 alias
        // MinecraftServer
        putM("getPlayerList",       M_GET_PLAYER_LIST);
        putM("getAllLevels",        M_GET_ALL_LEVELS);
        putM("getCommands",         M_GET_COMMANDS);
        putM("createCommandSourceStack", M_CREATE_COMMAND_SOURCE_STACK);
        putM("getTickCount",        M_GET_TICK_COUNT);
        putM("getLevel",            M_GET_LEVEL);
        putM("sendSystemMessage",   M_SEND_SYSTEM_MSG_SRV);
        putM("getTicks",            M_GET_TICK_COUNT);     // MC 26 alias
        putM("getWorld",            M_GET_LEVEL);          // 1.18 alias
        putM("getPlayerManager",    M_GET_PLAYER_LIST);    // 1.18 alias
        putM("getCommandManager",   M_GET_COMMANDS);       // 1.18 alias
        putM("getWorlds",           M_GET_ALL_LEVELS);     // 1.18 alias
        putM("getCommandSource",    M_CREATE_COMMAND_SOURCE_STACK);
        // ServerPlayer / Entity
        putM("getName",             M_GET_NAME);
        putM("getUUID",             M_GET_UUID);
        putM("getUuid",             M_GET_UUID);           // 1.18 alias
        putM("getX",                M_GET_X);
        putM("getY",                M_GET_Y);
        putM("getZ",                M_GET_Z);
        putM("getGameProfile",      M_GET_GAME_PROFILE);
        putM("sendMessage",         M_SEND_MSG_PLR_CMP);
        putM("level",               M_LEVEL);
        // PlayerList
        putM("getPlayer",           M_GET_PLAYER_UUID);
        putM("getPlayerByName",     M_GET_PLAYER_BY_NAME);
        putM("getPlayers",          M_GET_PLAYERS);
        putM("getPlayerList",       M_GET_PLAYERS);        // 1.18 alias
        putM("isOp",                M_IS_OP);
        putM("broadcastSystemMessage", M_BROADCAST_SYSTEM_MSG);
        putM("broadcastMessage",    M_BROADCAST_MSG);
        putM("broadcast",           M_BROADCAST);
        // Entity
        putM("setPos",              M_SET_POS);
        putM("refreshPositionAfterTeleport", M_SET_POS);   // MC 26 alias
        // BlockState
        putM("getBlock",            M_GET_BLOCK);
        putM("getFluidState",       M_GET_FLUID_STATE);
        // FluidState / Fluid
        putM("getFluid",            M_GET_FLUID);
        putM("getStill",            M_GET_STILL);
        // ServerGamePacketListenerImpl
        putM("disconnect",          M_DISCONNECT);
        putM("onDisconnect",        M_DISCONNECT);         // 1.18 alias
        // Commands
        putM("performCommand",      M_PERFORM_COMMAND);
        putM("performPrefixedCommand", M_PERFORM_PREFIXED_CMD);
        putM("executeWithPrefix",   M_PERFORM_PREFIXED_CMD); // 1.18 alias
        // CommandSourceStack
        putM("getServer",           M_GET_SERVER);
        putM("isPlayer",            M_IS_PLAYER);
        putM("getPlayer",           M_GET_PLAYER);
        putM("getEntity",           M_GET_ENTITY);
        putM("withSuppressedOutput", M_WITH_SUPPRESSED_OUTPUT);
        putM("hasPermission",       M_HAS_PERMISSION);
        putM("isExecutedByPlayer",  M_IS_PLAYER);          // 1.18 alias
        putM("withSilent",          M_WITH_SUPPRESSED_OUTPUT); // 1.18 alias
        putM("hasPermissionLevel",  M_HAS_PERMISSION);     // 1.18 alias
        putM("sendSuccess",         M_SEND_SYSTEM_MSG_CSS);
        putM("sendFailure",         M_SEND_SYSTEM_MSG_CSS);
        // BlockPos
        putM("getBlockPos",         "getBlockPos");        // named, keep as-is
        // HitResult
        putM("getBlockPos",         "getBlockPos");        // named
        putM("getPos",              "getPos");             // named
        // Entity.getName → already mapped
        // Misc
        putM("nameAndId",           "nameAndId");          // named
        putM("getStill",            M_GET_STILL);
        // Registry (used in getBlockId / getKey / getResourceKey)
        // Registry.getKey(T) intermediary: method_40269
        // Registry.getResourceKey(T) intermediary: method_39667
        putM("getKey",              "method_40269");       // Registry.getKey(T)
        putM("getResourceKey",      "method_39667");       // Registry.getResourceKey(T)
    }

    private static void putM(String mojangName, String resolved) {
        METHOD_REDIRECT.put(mojangName, resolved);
    }

    /**
     * Redirect a bare mojang method name to its resolved runtime form.
     * Returns the input unchanged if no redirect is known.
     */
    static String redirectMethod(String bareName) {
        String r = METHOD_REDIRECT.get(bareName);
        return r != null ? r : bareName;
    }

    private static final java.util.Map<String,String> FIELD_REDIRECT = new java.util.HashMap<>();
    static {
        putF("connection",          F_CONNECTION);
        putF("networkHandler",      F_CONNECTION);         // 1.18 alias
    }

    private static void putF(String mojangName, String resolved) {
        FIELD_REDIRECT.put(mojangName, resolved);
    }

    /** Redirect a bare mojang field name to its resolved runtime form. */
    static String redirectField(String bareName) {
        String r = FIELD_REDIRECT.get(bareName);
        return r != null ? r : bareName;
    }

    // ==================================================================
    // Class-name fallback  (named → intermediary for production servers)
    // ==================================================================

    private static final java.util.Map<String,String> NAMED_TO_INTER = new java.util.HashMap<>();
    static {
        NAMED_TO_INTER.put("net.minecraft.server.MinecraftServer",              "net.minecraft.server.MinecraftServer");
        NAMED_TO_INTER.put("net.minecraft.server.level.ServerPlayer",           "net.minecraft.class_3222");
        NAMED_TO_INTER.put("net.minecraft.server.level.ServerLevel",            "net.minecraft.class_3218");
        NAMED_TO_INTER.put("net.minecraft.server.players.PlayerList",           "net.minecraft.class_3324");
        NAMED_TO_INTER.put("net.minecraft.commands.CommandSourceStack",         "net.minecraft.class_2168");
        NAMED_TO_INTER.put("net.minecraft.world.entity.LightningBolt",          "net.minecraft.class_1538");
        NAMED_TO_INTER.put("net.minecraft.world.entity.EntityType",             "net.minecraft.class_1299");
        NAMED_TO_INTER.put("net.minecraft.world.entity.Entity",                 "net.minecraft.class_1297");
        NAMED_TO_INTER.put("net.minecraft.world.entity.player.Player",          "net.minecraft.class_1657");
        NAMED_TO_INTER.put("net.minecraft.world.level.Level",                   "net.minecraft.class_1937");
        NAMED_TO_INTER.put("net.minecraft.world.level.block.Block",             "net.minecraft.class_2248");
        NAMED_TO_INTER.put("net.minecraft.world.level.block.Blocks",            "net.minecraft.class_2246");
        NAMED_TO_INTER.put("net.minecraft.world.level.block.state.BlockState",  "net.minecraft.class_2680");
        NAMED_TO_INTER.put("net.minecraft.world.level.block.LiquidBlock",       "net.minecraft.class_2404");
        NAMED_TO_INTER.put("net.minecraft.world.level.material.Fluids",         "net.minecraft.class_3612");
        NAMED_TO_INTER.put("net.minecraft.world.level.material.Fluid",          "net.minecraft.class_3611");
        NAMED_TO_INTER.put("net.minecraft.world.level.GameType",                "net.minecraft.class_1934");
        NAMED_TO_INTER.put("net.minecraft.core.BlockPos",                       "net.minecraft.class_2338");
        NAMED_TO_INTER.put("net.minecraft.core.Registry",                       "net.minecraft.class_2378");
        NAMED_TO_INTER.put("net.minecraft.core.registries.BuiltInRegistries",   "net.minecraft.class_7922");
        NAMED_TO_INTER.put("net.minecraft.core.registries.Registries",          "net.minecraft.class_7923");
        NAMED_TO_INTER.put("net.minecraft.network.chat.Component",              "net.minecraft.class_2561");
        NAMED_TO_INTER.put("net.minecraft.network.chat.ChatType",               "net.minecraft.class_2556");
        NAMED_TO_INTER.put("net.minecraft.network.chat.MutableComponent",       "net.minecraft.class_5250");
        NAMED_TO_INTER.put("net.minecraft.world.InteractionResult",             "net.minecraft.class_1269");
        NAMED_TO_INTER.put("net.minecraft.world.phys.Vec3",                     "net.minecraft.class_243");
        NAMED_TO_INTER.put("net.minecraft.server.network.ServerGamePacketListenerImpl", "net.minecraft.class_3244");
    }

    static String toIntermediaryClass(String named) { return NAMED_TO_INTER.get(named); }
}
