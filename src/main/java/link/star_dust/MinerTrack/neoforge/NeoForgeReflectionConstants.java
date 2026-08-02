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

package link.star_dust.MinerTrack.neoforge;

/**
 * Centralized runtime-name constants for all Minecraft classes, methods and
 * fields accessed via reflection on the NeoForge platform.
 *
 * <p>NeoForge 20.2+ uses <b>Mojang/named</b> names at runtime — no
 * intermediary or SRG remapping. Each constant is therefore its own
 * runtime name. The mapping resolver path from Fabric is replaced with
 * an identity return: every constant resolves to itself.
 *
 * <p>Architecture mirrors {@code FabricReflectionConstants} so the pattern
 * is consistent across all three platforms.
 */
final class NeoForgeReflectionConstants {

    private NeoForgeReflectionConstants() {}

    static final Class<?>[] NO_ARGS = new Class<?>[0];
    static final Object[]   NO_VALS = new Object[0];

    // ==================================================================
    // Resolution helpers
    // Mirrors FabricReflectionConstants.im()/ifd(). NeoForge uses Mojang
    // names at runtime, so these return the named name unchanged. The
    // descriptor and fallback params preserve the exact Fabric signature.
    // ==================================================================

    private static String im(String namedOwner, String named,
                             String desc, String interFallback) {
        return named;
    }

    private static String ifd(String namedOwner, String named,
                              String desc, String interFallback) {
        return named;
    }

    // ==================================================================
    // CLASS NAMES
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
    // METHOD NAMES
    // ==================================================================

    static final String M_GET_PLAYER_LIST             = im("net.minecraft.server.MinecraftServer", "getPlayerList",          "()Lnet/minecraft/server/players/PlayerList;",                  "method_3760");
    static final String M_GET_ALL_LEVELS              = im("net.minecraft.server.MinecraftServer", "getAllLevels",           "()Ljava/lang/Iterable;",                                       "m_129785_");
    static final String M_GET_COMMANDS                = im("net.minecraft.server.MinecraftServer", "getCommands",           "()Lnet/minecraft/commands/Commands;",                          "m_129892_");
    static final String M_CREATE_COMMAND_SOURCE_STACK = im("net.minecraft.server.MinecraftServer", "createCommandSourceStack", "()Lnet/minecraft/commands/CommandSourceStack;",       "m_129893_");
    static final String M_GET_TICK_COUNT              = im("net.minecraft.server.MinecraftServer", "getTickCount",          "()I",                                                          "m_129921_");
    static final String M_GET_LEVEL                   = im("net.minecraft.server.MinecraftServer", "getLevel",              "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;", "m_129880_");
    static final String M_SEND_SYSTEM_MSG_SRV         = im("net.minecraft.server.MinecraftServer", "sendSystemMessage",     "(Lnet/minecraft/network/chat/Component;)V",                     "m_6352_");

    static final String M_GET_NAME                    = im("net.minecraft.world.entity.Entity", "getName",             "()Lnet/minecraft/network/chat/Component;",                     "m_7755_");
    static final String M_GET_UUID                    = im("net.minecraft.world.entity.Entity", "getUUID",             "()Ljava/util/UUID;",                                           "m_142081_");
    static final String M_GET_X                       = im("net.minecraft.world.entity.Entity",       "getX",                "()D",                                                          "m_20185_");
    static final String M_GET_Y                       = im("net.minecraft.world.entity.Entity",       "getY",                "()D",                                                          "m_20186_");
    static final String M_GET_Z                       = im("net.minecraft.world.entity.Entity",       "getZ",                "()D",                                                          "m_20189_");
    static final String M_GET_GAME_PROFILE            = im("net.minecraft.world.entity.player.Player", "getGameProfile",      "()Lcom/mojang/authlib/GameProfile;",                           "m_36316_");
    static final String M_SEND_MSG_PLR_CMP            = im("net.minecraft.server.level.ServerPlayer", "displayClientMessage",         "(Lnet/minecraft/network/chat/Component;Z)V",                     "m_5661_");
    static final String M_LEVEL                       = im("net.minecraft.server.level.ServerPlayer", "level",               "()Lnet/minecraft/world/level/Level;",                           null);

    static final String M_GET_BLOCK_STATE             = im("net.minecraft.world.level.Level",        "getBlockState",        "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", "m_8055_");
    static final String M_DIMENSION                   = im("net.minecraft.world.level.Level",        "dimension",            "()Lnet/minecraft/resources/ResourceKey;",                                           "m_46472_");

    static final String M_GET_PLAYER_UUID             = im("net.minecraft.server.players.PlayerList","getPlayer",            "(Ljava/util/UUID;)Lnet/minecraft/server/level/ServerPlayer;",   "m_11259_");
    static final String M_GET_PLAYER_BY_NAME          = im("net.minecraft.server.players.PlayerList","getPlayerByName",      "(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;", "m_11255_");
    static final String M_GET_PLAYERS                 = im("net.minecraft.server.players.PlayerList","getPlayers",           "()Ljava/util/List;",                                           "m_11314_");
    static final String M_IS_OP                       = im("net.minecraft.server.players.PlayerList","isOp",                 "(Lcom/mojang/authlib/GameProfile;)Z",                          "m_11303_");
    static final String M_BROADCAST_SYSTEM_MSG        = im("net.minecraft.server.players.PlayerList","broadcastSystemMessage","(Lnet/minecraft/network/chat/Component;Z)V",                   "m_43514_");
    static final String M_BROADCAST_MSG               = im("net.minecraft.server.players.PlayerList","broadcastMessage",     "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/ChatType;Ljava/util/UUID;)V", "m_11264_");
    static final String M_BROADCAST                   = im("net.minecraft.server.players.PlayerList","broadcast",            "(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",            "m_43512_");

    static final String M_SET_POS                     = im("net.minecraft.world.entity.Entity",       "setPosRaw",               "(DDD)V",                                                       "m_20343_");
    static final String M_GET_BLOCK                   = im("net.minecraft.world.level.block.state.BlockState", "getBlock",    "()Lnet/minecraft/world/level/block/Block;",                    "m_60734_");
    static final String M_GET_FLUID_STATE             = im("net.minecraft.world.level.Level", "getFluidState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",           "m_6425_");
    static final String M_GET_FLUID                   = im("net.minecraft.world.level.material.FluidState", "getType",       "()Lnet/minecraft/world/level/material/Fluid;",                 "m_76152_");
    static final String M_GET_STILL                   = im("net.minecraft.world.level.material.FlowingFluid", "getSource",     "()Lnet/minecraft/world/level/material/Fluid;",                 "m_5613_");
    static final String M_DISCONNECT                  = im("net.minecraft.server.network.ServerGamePacketListenerImpl", "disconnect", "(Lnet/minecraft/network/chat/Component;)V",          "m_9942_");
    static final String M_PERFORM_COMMAND             = im("net.minecraft.commands.Commands",           "performCommand",       "(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)I",  "m_82117_");
    static final String M_PERFORM_PREFIXED_CMD        = im("net.minecraft.commands.Commands",           "performPrefixedCommand","(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)I",  null);
    static final String M_COMPONENT_LITERAL           = im("net.minecraft.network.chat.Component",     "literal",              "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", "m_130763_");

    static final String M_GET_SERVER                  = im("net.minecraft.commands.CommandSourceStack", "getServer",           "()Lnet/minecraft/server/MinecraftServer;",                                  "m_81377_");
    static final String M_IS_PLAYER                   = im("net.minecraft.commands.CommandSourceStack", "isPlayer",            "()Z",                                                                      "m_43737_");
    static final String M_GET_PLAYER                  = im("net.minecraft.commands.CommandSourceStack", "getPlayer",           "()Lnet/minecraft/server/level/ServerPlayer;",                               "m_44023_");
    static final String M_GET_ENTITY                  = im("net.minecraft.commands.CommandSourceStack", "getEntity",           "()Lnet/minecraft/world/entity/Entity;",                                     "m_9228_");
    static final String M_SEND_SUCCESS_CSS            = im("net.minecraft.commands.CommandSourceStack", "sendSuccess",         "(Lnet/minecraft/network/chat/Component;Z)V",                                "m_9226_");
    static final String M_SEND_FAILURE_CSS            = im("net.minecraft.commands.CommandSourceStack", "sendFailure",         "(Lnet/minecraft/network/chat/Component;)V",                                 "m_9213_");
    static final String M_WITH_SUPPRESSED_OUTPUT      = im("net.minecraft.commands.CommandSourceStack", "withSuppressedOutput","()Lnet/minecraft/commands/CommandSourceStack;",                            "m_9217_");
    static final String M_HAS_PERMISSION              = im("net.minecraft.commands.CommandSourceStack", "hasPermission",       "(I)Z",                                                                     "m_9259_");
    static final String M_BUILT_IN_REGISTRY_HOLDER    = im("net.minecraft.world.level.block.Block",     "builtInRegistryHolder","()Lnet/minecraft/core/Holder;",                                          "m_204297_");

    // ==================================================================
    // FIELD NAMES
    // ==================================================================

    static final String F_ENTITY_TYPE_LIGHTNING       = ifd("net.minecraft.world.entity.EntityType",   "LIGHTNING_BOLT", "Lnet/minecraft/world/entity/EntityType;", "f_20465_");
    static final String F_BUILTIN_BLOCK               = ifd("net.minecraft.core.registries.BuiltInRegistries", "BLOCK", "Lnet/minecraft/core/DefaultedRegistry;",  "f_122824_");
    static final String F_REGISTRY_BLOCK              = ifd("net.minecraft.core.Registry",             "BLOCK",          "Lnet/minecraft/core/DefaultedRegistry;",  null);
    static final String F_CHAT_TYPE_CHAT              = ifd("net.minecraft.network.chat.ChatType",     "CHAT",           "Lnet/minecraft/network/chat/ChatType;",   "f_130601_");
    static final String F_CHAT_TYPE_SYSTEM            = ifd("net.minecraft.network.chat.ChatType",     "SYSTEM",         "Lnet/minecraft/network/chat/ChatType;",   "f_130602_");
    static final String F_IS_CLIENT_SIDE              = ifd("net.minecraft.world.level.Level",        "isClientSide",    "Z",                                       "f_9236_");
    static final String F_CONNECTION                  = ifd("net.minecraft.server.level.ServerPlayer", "connection",     "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;", "f_13987_");

    // ==================================================================
    // Runtime method name redirector
    // ==================================================================

    private static final java.util.Map<String,String> METHOD_REDIRECT = new java.util.HashMap<>();
    static {
        putM("dimension",           M_DIMENSION);
        putM("getBlockState",       M_GET_BLOCK_STATE);
        putM("getRegistryKey",      M_DIMENSION);
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
        putM("getName",             M_GET_NAME);
        putM("getUUID",             M_GET_UUID);
        putM("getUuid",             M_GET_UUID);
        putM("getX",                M_GET_X);
        putM("getY",                M_GET_Y);
        putM("getZ",                M_GET_Z);
        putM("getGameProfile",      M_GET_GAME_PROFILE);
        putM("sendMessage",         M_SEND_MSG_PLR_CMP);
        putM("level",               M_LEVEL);
        putM("getPlayerByUUID",     M_GET_PLAYER_UUID);
        putM("getPlayerByName",     M_GET_PLAYER_BY_NAME);
        putM("getPlayers",          M_GET_PLAYERS);
        putM("isOp",                M_IS_OP);
        putM("broadcastSystemMessage", M_BROADCAST_SYSTEM_MSG);
        putM("broadcastMessage",    M_BROADCAST_MSG);
        putM("broadcast",           M_BROADCAST);
        putM("setPos",              M_SET_POS);
        putM("refreshPositionAfterTeleport", M_SET_POS);
        putM("getBlock",            M_GET_BLOCK);
        putM("getFluidState",       M_GET_FLUID_STATE);
        putM("getFluid",            M_GET_FLUID);
        putM("getStill",            M_GET_STILL);
        putM("disconnect",          M_DISCONNECT);
        putM("performCommand",      M_PERFORM_COMMAND);
        putM("performPrefixedCommand", M_PERFORM_PREFIXED_CMD);
        putM("executeWithPrefix",   M_PERFORM_PREFIXED_CMD);
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
        putM("literal",             M_COMPONENT_LITERAL);
        putM("builtInRegistryHolder", M_BUILT_IN_REGISTRY_HOLDER);
        putM("getString",           "getString");
        putM("getValue",            "getValue");
        putM("getSource",           M_GET_STILL);
        putM("getType",             M_GET_FLUID);
        putM("getPhase",            "getPhase");
    }

    private static void putM(String mojangName, String resolved) { METHOD_REDIRECT.put(mojangName, resolved); }

    static String redirectMethod(String bareName) { String r = METHOD_REDIRECT.get(bareName); return r != null ? r : bareName; }

    // ==================================================================
    // Runtime field name redirector
    // ==================================================================

    private static final java.util.Map<String,String> FIELD_REDIRECT = new java.util.HashMap<>();
    static { putF("isClientSide", F_IS_CLIENT_SIDE); putF("connection", F_CONNECTION); putF("networkHandler", F_CONNECTION); }

    private static void putF(String mojangName, String resolved) { FIELD_REDIRECT.put(mojangName, resolved); }

    static String redirectField(String bareName) { String r = FIELD_REDIRECT.get(bareName); return r != null ? r : bareName; }

    // ==================================================================
    // Class-name fallback (mojang/named → runtime name)
    // Mirrors FabricReflectionConstants.NAMED_TO_INTER.
    // ==================================================================

    private static final java.util.Map<String,String> NAMED_TO_RUNTIME = new java.util.HashMap<>();
    static {
        NAMED_TO_RUNTIME.put("net.minecraft.server.MinecraftServer",              "net.minecraft.server.MinecraftServer");
        NAMED_TO_RUNTIME.put("net.minecraft.server.level.ServerPlayer",           "net.minecraft.server.level.ServerPlayer");
        NAMED_TO_RUNTIME.put("net.minecraft.server.level.ServerLevel",            "net.minecraft.server.level.ServerLevel");
        NAMED_TO_RUNTIME.put("net.minecraft.server.players.PlayerList",           "net.minecraft.server.players.PlayerList");
        NAMED_TO_RUNTIME.put("net.minecraft.commands.CommandSourceStack",         "net.minecraft.commands.CommandSourceStack");
        NAMED_TO_RUNTIME.put("net.minecraft.world.entity.LightningBolt",          "net.minecraft.world.entity.LightningBolt");
        NAMED_TO_RUNTIME.put("net.minecraft.world.entity.EntityType",             "net.minecraft.world.entity.EntityType");
        NAMED_TO_RUNTIME.put("net.minecraft.world.entity.Entity",                 "net.minecraft.world.entity.Entity");
        NAMED_TO_RUNTIME.put("net.minecraft.world.entity.player.Player",          "net.minecraft.world.entity.player.Player");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.Level",                   "net.minecraft.world.level.Level");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.block.Block",             "net.minecraft.world.level.block.Block");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.block.Blocks",            "net.minecraft.world.level.block.Blocks");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.block.state.BlockState",  "net.minecraft.world.level.block.state.BlockState");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.block.LiquidBlock",       "net.minecraft.world.level.block.LiquidBlock");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.material.Fluids",         "net.minecraft.world.level.material.Fluids");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.material.Fluid",          "net.minecraft.world.level.material.Fluid");
        NAMED_TO_RUNTIME.put("net.minecraft.world.level.material.FlowableFluid",  "net.minecraft.world.level.material.FlowableFluid");
        NAMED_TO_RUNTIME.put("net.minecraft.core.BlockPos",                       "net.minecraft.core.BlockPos");
        NAMED_TO_RUNTIME.put("net.minecraft.core.Registry",                       "net.minecraft.core.Registry");
        NAMED_TO_RUNTIME.put("net.minecraft.core.registries.BuiltInRegistries",   "net.minecraft.core.registries.BuiltInRegistries");
        NAMED_TO_RUNTIME.put("net.minecraft.network.chat.Component",              "net.minecraft.network.chat.Component");
        NAMED_TO_RUNTIME.put("net.minecraft.network.chat.ChatType",               "net.minecraft.network.chat.ChatType");
        NAMED_TO_RUNTIME.put("net.minecraft.network.chat.MutableComponent",       "net.minecraft.network.chat.MutableComponent");
        NAMED_TO_RUNTIME.put("net.minecraft.world.phys.Vec3",                     "net.minecraft.world.phys.Vec3");
        NAMED_TO_RUNTIME.put("net.minecraft.server.network.ServerGamePacketListenerImpl", "net.minecraft.server.network.ServerGamePacketListenerImpl");
        NAMED_TO_RUNTIME.put("net.minecraft.network.chat.TextComponent",          "net.minecraft.network.chat.TextComponent");
    }

    static String toRuntimeClass(String named) { return NAMED_TO_RUNTIME.get(named); }
}
