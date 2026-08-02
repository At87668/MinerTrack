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

/**
 * Centralized runtime-name constants for all Minecraft classes, methods and
 * fields accessed via reflection on the Forge platform.
 *
 * <p>Forge 1.18+ uses <b>Mojang/named</b> names at runtime — no intermediary
 * or SRG remapping. Each constant is therefore its own runtime name.
 * The mapping resolver path from Fabric is replaced with an identity
 * return: every constant resolves to itself.
 *
 * <p>Architecture mirrors {@code FabricReflectionConstants} so the pattern
 * is consistent across all three platforms.
 */
final class ForgeReflectionConstants {

    private ForgeReflectionConstants() {}

    /* ---- empty arrays shared with ForgeReflection ---- */

    static final Class<?>[] NO_ARGS = new Class<?>[0];
    static final Object[]   NO_VALS = new Object[0];

    // ==================================================================
    // Resolution helpers
    //
    // Forge mapping evolution (see project timeline):
    //   - 1.16.5–1.20.4: runtime uses SRG/Searge names (m_XXXX_, f_XXXX_,
    //     net.minecraft.src.C_XXX_). Dev-time Mojang names are remapped
    //     back to SRG at load.
    //   - 1.20.6+: runtime uses Mojang names directly (no SRG remap).
    //
    // These helpers detect the runtime namespace once and resolve a
    // mojang/named name to its correct runtime form: Searge on 1.18.2
    // (via SEARGE_METHOD / SEARGE_FIELD), Mojang on 1.20.6+ (identity).
    // ==================================================================

    // ==================================================================
    // Lazy runtime-namespace detection. Mohist / hybrid servers may load
    // this class before Forge's classloader is fully set up, so a eager
    // static check (Class.forName("net.minecraft.world.level.Level"))
    // can erroneously report MISS even when Mojang names work later.
    // ==================================================================
    private static volatile Boolean isMojangRuntime; // null = not checked yet

    private static boolean isMojangRuntime() {
        Boolean v = isMojangRuntime;
        if (v != null) return v;
        synchronized (ForgeReflectionConstants.class) {
            v = isMojangRuntime;
            if (v != null) return v;
            boolean mojang = false;
            try {
                Class.forName("net.minecraft.world.level.Level");
                mojang = true;
            } catch (ClassNotFoundException ignored) {}
            isMojangRuntime = mojang;
            return mojang;
        }
    }

    private static String im(String namedOwner, String named,
                             String desc, String interFallback) {
        if (isMojangRuntime()) return named;
        String s = SEARGE_METHOD.get(named);
        return s != null ? s : named;
    }

    /** Same as {@link #im} but for fields. */
    private static String ifd(String namedOwner, String named,
                              String desc, String interFallback) {
        if (isMojangRuntime()) return named;
        String s = SEARGE_FIELD.get(named);
        return s != null ? s : named;
    }

    // ==================================================================
    // Searge method-name table (1.18.2)
    // mojang/named method name → Searge runtime name
    // ==================================================================

    private static final java.util.Map<String,String> SEARGE_METHOD = new java.util.HashMap<>();
    static {
        // MinecraftServer
        putSM("getPlayerList",             "m_6846_");
        putSM("getAllLevels",              "m_129785_");
        putSM("getCommands",               "m_129892_");
        putSM("createCommandSourceStack",  "m_129893_");
        putSM("getTickCount",              "m_129921_");
        putSM("getLevel",                  "m_129880_");
        putSM("sendSystemMessage",         "m_6352_");
        putSM("getProfilePermissions",     "m_129944_");
        // Entity
        putSM("getName",                   "m_7755_");
        putSM("getUUID",                   "m_142081_");
        putSM("getX",                      "m_20185_");
        putSM("getY",                      "m_20186_");
        putSM("getZ",                      "m_20189_");
        putSM("setPosRaw",                 "m_20343_");
        // Player
        putSM("getGameProfile",            "m_36316_");
        putSM("displayClientMessage",      "m_5661_");
        // Level
        putSM("getBlockState",             "m_8055_");
        putSM("getFluidState",             "m_6425_");
        putSM("dimension",                 "m_46472_");
        putSM("isClientSide",              "m_5776_");
        // PlayerList
        putSM("getPlayer",                 "m_11259_");
        putSM("getPlayerByName",           "m_11255_");
        putSM("getPlayers",                "m_11314_");
        putSM("isOp",                      "m_11303_");
        putSM("broadcastMessage",          "m_11264_");
        // BlockState
        putSM("getBlock",                  "m_60734_");
        // FluidState / Fluid
        putSM("getType",                   "m_76152_");
        putSM("getSource",                 "m_5613_");
        // ServerGamePacketListenerImpl
        putSM("disconnect",                "m_9942_");
        // Commands
        putSM("performCommand",            "m_82117_");
        // Component
        putSM("literal",                   "m_130763_");
        // CommandSourceStack
        putSM("getServer",                 "m_81377_");
        putSM("getEntity",                 "m_81373_");
        putSM("sendSuccess",               "m_81354_");
        putSM("sendFailure",               "m_81352_");
        putSM("withSuppressedOutput",      "m_81324_");
        putSM("hasPermission",             "m_6761_");
        // Block
        putSM("builtInRegistryHolder",     "m_204297_");
    }

    private static void putSM(String mojangName, String searge) { SEARGE_METHOD.put(mojangName, searge); }

    // ==================================================================
    // Searge field-name table (1.18.2)
    // mojang/named field name → Searge runtime name
    // ==================================================================

    private static final java.util.Map<String,String> SEARGE_FIELD = new java.util.HashMap<>();
    static {
        putSF("LIGHTNING_BOLT",            "f_20465_");
        putSF("BLOCK",                     "f_122824_");
        putSF("CHAT",                      "f_130601_");
        putSF("SYSTEM",                    "f_130602_");
        putSF("isClientSide",              "m_5776_");
        putSF("connection",                "f_8906_");
    }

    private static void putSF(String mojangName, String searge) { SEARGE_FIELD.put(mojangName, searge); }

    // ==================================================================
    // CLASS NAMES  (mojang/named → Searge runtime names on Forge 1.18.2)
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
    static final String CLS_FLOWABLE_FLUID            = "net.minecraft.world.level.material.FlowableFluid";
    static final String CLS_BLOCK_POS                 = "net.minecraft.core.BlockPos";
    static final String CLS_REGISTRY                  = "net.minecraft.core.Registry";
    static final String CLS_BUILT_IN_REGISTRIES       = "net.minecraft.core.registries.BuiltInRegistries";
    static final String CLS_COMPONENT                 = "net.minecraft.network.chat.Component";
    static final String CLS_CHAT_TYPE                 = "net.minecraft.network.chat.ChatType";
    static final String CLS_MUTABLE_COMPONENT         = "net.minecraft.network.chat.MutableComponent";
    static final String CLS_VEC3                      = "net.minecraft.world.phys.Vec3";
    static final String CLS_SERVER_GAME_PACKET_LISTENER = "net.minecraft.server.network.ServerGamePacketListenerImpl";
    static final String CLS_TEXT_COMPONENT            = "net.minecraft.network.chat.TextComponent";

    // ==================================================================
    // METHOD NAMES  (these ARE the runtime names on Forge)
    // ==================================================================

    // -- MinecraftServer -------------------------------------------------
    static final String M_GET_PLAYER_LIST             = im("net.minecraft.server.MinecraftServer", "getPlayerList",          "()Lnet/minecraft/server/players/PlayerList;",                  "method_3760");
    static final String M_GET_ALL_LEVELS              = im("net.minecraft.server.MinecraftServer", "getAllLevels",           "()Ljava/lang/Iterable;",                                       "method_3738");
    static final String M_GET_COMMANDS                = im("net.minecraft.server.MinecraftServer", "getCommands",           "()Lnet/minecraft/commands/Commands;",                          "method_3734");
    static final String M_CREATE_COMMAND_SOURCE_STACK = im("net.minecraft.server.MinecraftServer", "createCommandSourceStack", "()Lnet/minecraft/commands/CommandSourceStack;",       "method_3739");
    static final String M_GET_TICK_COUNT              = im("net.minecraft.server.MinecraftServer", "getTickCount",          "()I",                                                          "method_3780");
    static final String M_GET_LEVEL                   = im("net.minecraft.server.MinecraftServer", "getLevel",              "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;", "method_3847");
    static final String M_SEND_SYSTEM_MSG_SRV         = im("net.minecraft.server.MinecraftServer", "sendSystemMessage",     "(Lnet/minecraft/network/chat/Component;)V",                     "method_9203");

    // -- ServerPlayer / Entity -------------------------------------------
    static final String M_GET_NAME                    = im("net.minecraft.world.entity.Entity", "getName",             "()Lnet/minecraft/network/chat/Component;",                     "method_5477");
    static final String M_GET_UUID                    = im("net.minecraft.world.entity.Entity", "getUUID",             "()Ljava/util/UUID;",                                           "method_5667");
    static final String M_GET_X                       = im("net.minecraft.world.entity.Entity",       "getX",                "()D",                                                          "method_23317");
    static final String M_GET_Y                       = im("net.minecraft.world.entity.Entity",       "getY",                "()D",                                                          "method_23318");
    static final String M_GET_Z                       = im("net.minecraft.world.entity.Entity",       "getZ",                "()D",                                                          "method_23321");
    static final String M_GET_GAME_PROFILE            = im("net.minecraft.world.entity.player.Player", "getGameProfile",      "()Lcom/mojang/authlib/GameProfile;",                           "method_7334");
    static final String M_SEND_MSG_PLR_CMP            = im("net.minecraft.server.level.ServerPlayer", "displayClientMessage",         "(Lnet/minecraft/network/chat/Component;Z)V",                     "method_7353");
    static final String M_LEVEL                       = im("net.minecraft.server.level.ServerPlayer", "level",               "()Lnet/minecraft/world/level/Level;",                           null);

    // -- Level -----------------------------------------------------------
    static final String M_GET_BLOCK_STATE             = im("net.minecraft.world.level.Level",        "getBlockState",        "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", "method_8320");
    static final String M_DIMENSION                   = im("net.minecraft.world.level.Level",        "dimension",            "()Lnet/minecraft/resources/ResourceKey;",                                           "method_27983");

    // -- PlayerList ------------------------------------------------------
    static final String M_GET_PLAYER_UUID             = im("net.minecraft.server.players.PlayerList","getPlayer",            "(Ljava/util/UUID;)Lnet/minecraft/server/level/ServerPlayer;",   "method_14602");
    static final String M_GET_PLAYER_BY_NAME          = im("net.minecraft.server.players.PlayerList","getPlayerByName",      "(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;", "method_14566");
    static final String M_GET_PLAYERS                 = im("net.minecraft.server.players.PlayerList","getPlayers",           "()Ljava/util/List;",                                           "method_14571");
    static final String M_IS_OP                       = im("net.minecraft.server.players.PlayerList","isOp",                 "(Lcom/mojang/authlib/GameProfile;)Z",                          "method_14569");
    static final String M_BROADCAST_SYSTEM_MSG        = im("net.minecraft.server.players.PlayerList","broadcastSystemMessage","(Lnet/minecraft/network/chat/Component;Z)V",                   "method_43514");
    static final String M_BROADCAST_MSG               = im("net.minecraft.server.players.PlayerList","broadcastMessage",     "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/ChatType;Ljava/util/UUID;)V", "method_14616");
    static final String M_BROADCAST                   = im("net.minecraft.server.players.PlayerList","broadcast",            "(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",            "method_43512");

    // -- Entity ----------------------------------------------------------
    static final String M_SET_POS                     = im("net.minecraft.world.entity.Entity",       "setPosRaw",               "(DDD)V",                                                       "method_23327");

    // -- BlockState ------------------------------------------------------
    static final String M_GET_BLOCK                   = im("net.minecraft.world.level.block.state.BlockState", "getBlock",    "()Lnet/minecraft/world/level/block/Block;",                    "method_26204");
    static final String M_GET_FLUID_STATE             = im("net.minecraft.world.level.Level", "getFluidState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",           "method_8316");

    // -- FluidState / Fluid ----------------------------------------------
    static final String M_GET_FLUID                   = im("net.minecraft.world.level.material.FluidState", "getType",       "()Lnet/minecraft/world/level/material/Fluid;",                 "method_15772");
    static final String M_GET_STILL                   = im("net.minecraft.world.level.material.FlowingFluid", "getSource",     "()Lnet/minecraft/world/level/material/Fluid;",                 "method_15751");

    // -- ServerGamePacketListenerImpl ------------------------------------
    static final String M_DISCONNECT                  = im("net.minecraft.server.network.ServerGamePacketListenerImpl", "disconnect", "(Lnet/minecraft/network/chat/Component;)V",          "method_14367");

    // -- Commands --------------------------------------------------------
    static final String M_PERFORM_COMMAND             = im("net.minecraft.commands.Commands",           "performCommand",       "(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)I",  "method_9249");
    static final String M_PERFORM_PREFIXED_CMD        = im("net.minecraft.commands.Commands",           "performPrefixedCommand","(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)I",  null);

    // -- Component -------------------------------------------------------
    static final String M_COMPONENT_LITERAL           = im("net.minecraft.network.chat.Component",     "literal",              "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", null);

    // -- CommandSourceStack ----------------------------------------------
    static final String M_GET_SERVER                  = im("net.minecraft.commands.CommandSourceStack", "getServer",           "()Lnet/minecraft/server/MinecraftServer;",                                  "method_9211");
    static final String M_IS_PLAYER                   = im("net.minecraft.commands.CommandSourceStack", "isPlayer",            "()Z",                                                                      "method_43737");
    static final String M_GET_PLAYER                  = im("net.minecraft.commands.CommandSourceStack", "getPlayer",           "()Lnet/minecraft/server/level/ServerPlayer;",                               "method_44023");
    static final String M_GET_ENTITY                  = im("net.minecraft.commands.CommandSourceStack", "getEntity",           "()Lnet/minecraft/world/entity/Entity;",                                     "method_9228");
    static final String M_SEND_SUCCESS_CSS            = im("net.minecraft.commands.CommandSourceStack", "sendSuccess",         "(Lnet/minecraft/network/chat/Component;Z)V",                                "method_9226");
    static final String M_SEND_FAILURE_CSS            = im("net.minecraft.commands.CommandSourceStack", "sendFailure",         "(Lnet/minecraft/network/chat/Component;)V",                                 "method_9213");
    static final String M_WITH_SUPPRESSED_OUTPUT      = im("net.minecraft.commands.CommandSourceStack", "withSuppressedOutput","()Lnet/minecraft/commands/CommandSourceStack;",                            "method_9217");
    static final String M_HAS_PERMISSION              = im("net.minecraft.commands.CommandSourceStack", "hasPermission",       "(I)Z",                                                                     "method_9259");

    // -- BuiltInRegistryHolder -------------------------------------------
    static final String M_BUILT_IN_REGISTRY_HOLDER    = im("net.minecraft.world.level.block.Block",     "builtInRegistryHolder","()Lnet/minecraft/core/Holder;",                                          "method_20516");

    // ==================================================================
    // FIELD NAMES
    // ==================================================================

    static final String F_ENTITY_TYPE_LIGHTNING       = ifd("net.minecraft.world.entity.EntityType",   "LIGHTNING_BOLT", "Lnet/minecraft/world/entity/EntityType;", "field_6112");
    static final String F_BUILTIN_BLOCK               = ifd("net.minecraft.core.registries.BuiltInRegistries", "BLOCK", "Lnet/minecraft/core/DefaultedRegistry;",  "field_41175");
    static final String F_REGISTRY_BLOCK              = ifd("net.minecraft.core.Registry",             "BLOCK",          "Lnet/minecraft/core/DefaultedRegistry;",  null);
    static final String F_CHAT_TYPE_CHAT              = ifd("net.minecraft.network.chat.ChatType",     "CHAT",           "Lnet/minecraft/network/chat/ChatType;",   "field_11737");
    static final String F_CHAT_TYPE_SYSTEM            = ifd("net.minecraft.network.chat.ChatType",     "SYSTEM",         "Lnet/minecraft/network/chat/ChatType;",   null);
    static final String F_IS_CLIENT_SIDE              = ifd("net.minecraft.world.level.Level",        "isClientSide",    "Z",                                       "field_9236");
    static final String F_CONNECTION                  = ifd("net.minecraft.server.level.ServerPlayer", "connection",     "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;", "field_13987");

    // ==================================================================
    // Runtime method name redirector
    //
    // On Forge the runtime names ARE the mojang names, so this is an
    // identity map.  It exists to mirror the Fabric pattern so the
    // ForgeReflection lookup code is structurally identical to
    // FabricReflection — both call ForgeReflectionConstants.redirectMethod().
    // Multi-version aliases (e.g. "getTicks" → "getTickCount") are
    // handled here rather than hardcoded in ForgeReflection.
    // ==================================================================

    private static final java.util.Map<String,String> METHOD_REDIRECT = new java.util.HashMap<>();
    static {
        // Level
        putM("dimension",           M_DIMENSION);
        putM("getBlockState",       M_GET_BLOCK_STATE);
        putM("getRegistryKey",      M_DIMENSION);

        // MinecraftServer
        putM("getPlayerList",       M_GET_PLAYER_LIST);
        putM("getAllLevels",        M_GET_ALL_LEVELS);
        putM("getCommands",         M_GET_COMMANDS);
        putM("createCommandSourceStack", M_CREATE_COMMAND_SOURCE_STACK);
        putM("getTickCount",        M_GET_TICK_COUNT);
        putM("getLevel",            M_GET_LEVEL);
        putM("sendSystemMessage",   M_SEND_SYSTEM_MSG_SRV);
        putM("getTicks",            M_GET_TICK_COUNT);
        putM("getWorld",            M_GET_LEVEL);
        putM("getPlayerManager",    M_GET_PLAYER_LIST);
        putM("getCommandManager",   M_GET_COMMANDS);
        putM("getWorlds",           M_GET_ALL_LEVELS);

        // ServerPlayer / Entity
        putM("getName",             M_GET_NAME);
        putM("getUUID",             M_GET_UUID);
        putM("getUuid",             M_GET_UUID);
        putM("getX",                M_GET_X);
        putM("getY",                M_GET_Y);
        putM("getZ",                M_GET_Z);
        putM("getGameProfile",      M_GET_GAME_PROFILE);
        putM("sendMessage",         M_SEND_MSG_PLR_CMP);
        putM("level",               M_LEVEL);

        // PlayerList
        putM("getPlayerByUUID",     M_GET_PLAYER_UUID);
        putM("getPlayerByName",     M_GET_PLAYER_BY_NAME);
        putM("getPlayers",          M_GET_PLAYERS);
        putM("isOp",                M_IS_OP);
        putM("broadcastSystemMessage", M_BROADCAST_SYSTEM_MSG);
        putM("broadcastMessage",    M_BROADCAST_MSG);
        putM("broadcast",           M_BROADCAST);

        // Entity
        putM("setPos",              M_SET_POS);
        putM("refreshPositionAfterTeleport", M_SET_POS);

        // BlockState
        putM("getBlock",            M_GET_BLOCK);
        putM("getFluidState",       M_GET_FLUID_STATE);

        // FluidState / Fluid
        putM("getFluid",            M_GET_FLUID);
        putM("getStill",            M_GET_STILL);

        // ServerGamePacketListenerImpl
        putM("disconnect",          M_DISCONNECT);

        // Commands
        putM("performCommand",      M_PERFORM_COMMAND);
        putM("performPrefixedCommand", M_PERFORM_PREFIXED_CMD);
        putM("executeWithPrefix",   M_PERFORM_PREFIXED_CMD);

        // CommandSourceStack
        putM("getServer",           M_GET_SERVER);
        putM("isPlayer",            M_IS_PLAYER);
        putM("getPlayer",           M_GET_PLAYER);
        putM("getEntity",           M_GET_ENTITY);
        putM("withSuppressedOutput", M_WITH_SUPPRESSED_OUTPUT);
        putM("hasPermission",       M_HAS_PERMISSION);
        putM("isExecutedByPlayer",  M_IS_PLAYER);
        putM("withSilent",          M_WITH_SUPPRESSED_OUTPUT);
        putM("hasPermissionLevel",  M_HAS_PERMISSION);
        putM("sendSuccess",         M_SEND_SUCCESS_CSS);
        putM("sendFailure",         M_SEND_FAILURE_CSS);

        // Misc
        putM("literal",             M_COMPONENT_LITERAL);
        putM("builtInRegistryHolder", M_BUILT_IN_REGISTRY_HOLDER);
        putM("getString",           "getString");
        putM("getValue",            "getValue");
        putM("getSource",           M_GET_STILL);
        putM("getType",             M_GET_FLUID);
        putM("getPhase",            "getPhase");
    }

    private static void putM(String mojangName, String resolved) {
        METHOD_REDIRECT.put(mojangName, resolved);
    }

    /** Redirect a bare mojang method name to its resolved runtime form. */
    static String redirectMethod(String bareName) {
        String r = METHOD_REDIRECT.get(bareName);
        return r != null ? r : bareName;
    }

    // ==================================================================
    // Runtime field name redirector
    // ==================================================================

    private static final java.util.Map<String,String> FIELD_REDIRECT = new java.util.HashMap<>();
    static {
        putF("isClientSide",        F_IS_CLIENT_SIDE);
        putF("connection",          F_CONNECTION);
        putF("networkHandler",      F_CONNECTION);
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
    // Class-name fallback  (mojang/named → runtime name)
    //
    // Mirrors FabricReflectionConstants.NAMED_TO_INTER. On Mojang-mapped
    // Forge (1.19.3+) the mojang name IS the runtime name, so this map is
    // identity. On MCP-mapped Forge (1.18.2, e.g. Mohist/CatServer) the
    // runtime class may be under net.minecraft.src.C_* — populate the
    // alternative names there. ForgeReflection.forName() consults this
    // when the mojang name fails to load.
    // ==================================================================

    private static final java.util.Map<String,String> NAMED_TO_RUNTIME = new java.util.HashMap<>();
    static {
        // Forge 1.18.2 Searge class names (net.minecraft.src.C_*)
        NAMED_TO_RUNTIME.put("net.minecraft.server.MinecraftServer",              "net.minecraft.src.C_4977_");
        NAMED_TO_RUNTIME.put("net.minecraft.server.level.ServerPlayer",           "net.minecraft.src.C_13_");
        NAMED_TO_RUNTIME.put("net.minecraft.server.level.ServerLevel",            "net.minecraft.src.C_12_");
        NAMED_TO_RUNTIME.put("net.minecraft.server.players.PlayerList",           "net.minecraft.src.C_14_");
        NAMED_TO_RUNTIME.put("net.minecraft.commands.CommandSourceStack",         "net.minecraft.src.C_2969_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.entity.LightningBolt",          "net.minecraft.src.C_1538_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.entity.EntityType",             "net.minecraft.src.C_1299_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.entity.Entity",                 "net.minecraft.src.C_507_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.entity.player.Player",          "net.minecraft.src.C_1141_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.Level",                   "net.minecraft.src.C_318_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.block.Block",             "net.minecraft.src.C_2063_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.block.Blocks",            "net.minecraft.src.C_2062_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.block.state.BlockState",  "net.minecraft.src.C_2064_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.block.LiquidBlock",       "net.minecraft.src.C_2404_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.material.Fluids",         "net.minecraft.src.C_3612_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.material.Fluid",          "net.minecraft.src.C_3611_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.material.FlowableFluid",  "net.minecraft.src.C_3609_");
        NAMED_TO_RUNTIME.put("net.minecraft.core.BlockPos",                       "net.minecraft.src.C_4675_");
        NAMED_TO_RUNTIME.put("net.minecraft.core.Registry",                       "net.minecraft.src.C_2378_");
        NAMED_TO_RUNTIME.put("net.minecraft.core.registries.BuiltInRegistries",   "net.minecraft.src.C_7922_");
        NAMED_TO_RUNTIME.put("net.minecraft.network.chat.Component",              "net.minecraft.src.C_4996_");
        NAMED_TO_RUNTIME.put("net.minecraft.network.chat.ChatType",               "net.minecraft.src.C_4992_");
        NAMED_TO_RUNTIME.put("net.minecraft.network.chat.MutableComponent",       "net.minecraft.src.C_5250_");
        NAMED_TO_RUNTIME.put("net.minecraft.world.phys.Vec3",                     "net.minecraft.src.C_243_");
        NAMED_TO_RUNTIME.put("net.minecraft.server.network.ServerGamePacketListenerImpl", "net.minecraft.src.C_3244_");
        NAMED_TO_RUNTIME.put("net.minecraft.network.chat.TextComponent",          "net.minecraft.src.C_2585_");
    }

    /**
     * Return the runtime class name for a mojang/named class name.
     * On SRG-mapped Forge (1.18.2) this returns the Searge name
     * (net.minecraft.src.C_*); on Mojang-mapped Forge (1.20.6+) it
     * returns the mojang name unchanged.
     */
    static String toRuntimeClass(String named) {
        if (isMojangRuntime()) return named;
        return NAMED_TO_RUNTIME.get(named);
    }
}
