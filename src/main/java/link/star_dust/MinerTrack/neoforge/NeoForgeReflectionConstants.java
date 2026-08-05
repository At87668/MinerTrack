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
    static final String CLS_SERVER_COMMON_PACKET_LISTENER = "net.minecraft.server.network.ServerCommonPacketListenerImpl";
    static final String CLS_TEXT_COMPONENT            = "net.minecraft.network.chat.TextComponent";

    // ==================================================================
    // Resolution helpers
    //
    // NeoForge 20.2+ (1.20.2+) uses Mojang/named names at runtime, so a mojang
    // name is normally the runtime name. However NeoForge 1.20.4 (and hybrid
    // servers like Arclight-neoforge) still run with Searge names, so we detect
    // the runtime namespace once and fall back to the Searge name when needed —
    // exactly like ForgeReflectionConstants. Descriptors are kept for
    // documentation / potential future mapping needs (im = method, ifd = field).
    // ==================================================================

    private static volatile Boolean isMojangRuntime; // null = not checked yet

    private static boolean isMojangRuntime() {
        Boolean v = isMojangRuntime;
        if (v != null) return v;
        synchronized (NeoForgeReflectionConstants.class) {
            v = isMojangRuntime;
            if (v != null) return v;
            boolean mojang = false;
            try {
                Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
                // On SRG runtimes getUUID is m_142081_ so this throws
                // NoSuchMethodException → Searge runtime.
                entity.getDeclaredMethod("getUUID");
                mojang = true;
            } catch (ClassNotFoundException ignored) {
            } catch (NoSuchMethodException ignored) {
                // Class loaded but method NOT found → SRG/Searge runtime
            }
            isMojangRuntime = mojang;
            return mojang;
        }
    }

    private static String im(String namedOwner, String named, String desc, String interFallback) {
        if (isMojangRuntime()) return named;
        return interFallback != null ? interFallback : named;
    }

    /** Same as {@link #im} but for fields. */
    private static String ifd(String namedOwner, String named, String desc, String interFallback) {
        if (isMojangRuntime()) return named;
        return interFallback != null ? interFallback : named;
    }

    // ==================================================================
    // METHOD NAMES
    // ==================================================================

    // -- Component -------------------------------------------------------
    static final String M_COMPONENT_LITERAL           = im("net.minecraft.network.chat.Component",             "literal",    "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", null);

    // -- ServerGamePacketListenerImpl / ServerCommonPacketListenerImpl ---
    static final String M_DISCONNECT                  = im("net.minecraft.server.network.ServerGamePacketListenerImpl",   "disconnect", "(Lnet/minecraft/network/chat/Component;)V", "m_9942_");
    static final String M_DISCONNECT_NEW              = im("net.minecraft.server.network.ServerCommonPacketListenerImpl", "disconnect", "(Lnet/minecraft/network/chat/Component;)V", "m_294716_");

    // -- MinecraftServer -------------------------------------------------
    static final String M_GET_PLAYER_LIST             = im("net.minecraft.server.MinecraftServer", "getPlayerList",          "()Lnet/minecraft/server/players/PlayerList;",                  "m_6846_");
    static final String M_GET_ALL_LEVELS              = im("net.minecraft.server.MinecraftServer", "getAllLevels",           "()Ljava/lang/Iterable;",                                       "m_129785_");
    static final String M_GET_COMMANDS                = im("net.minecraft.server.MinecraftServer", "getCommands",           "()Lnet/minecraft/commands/Commands;",                          "m_129892_");
    static final String M_CREATE_COMMAND_SOURCE_STACK = im("net.minecraft.server.MinecraftServer", "createCommandSourceStack", "()Lnet/minecraft/commands/CommandSourceStack;",       "m_129893_");
    static final String M_GET_TICK_COUNT              = im("net.minecraft.server.MinecraftServer", "getTickCount",          "()I",                                                          "m_129921_");
    static final String M_GET_LEVEL                   = im("net.minecraft.server.MinecraftServer", "getLevel",              "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;", "m_129880_");
    static final String M_SEND_SYSTEM_MSG_SRV         = im("net.minecraft.server.MinecraftServer", "sendSystemMessage",     "(Lnet/minecraft/network/chat/Component;)V",                     "m_6352_");

    // -- Entity / Player ------------------------------------------------
    static final String M_GET_NAME                    = im("net.minecraft.world.entity.Entity", "getName",             "()Lnet/minecraft/network/chat/Component;",                     "m_7755_");
    static final String M_GET_UUID                    = im("net.minecraft.world.entity.Entity", "getUUID",             "()Ljava/util/UUID;",                                           "m_142081_");
    static final String M_GET_GAME_PROFILE            = im("net.minecraft.world.entity.player.Player", "getGameProfile",      "()Lcom/mojang/authlib/GameProfile;",                           "m_36316_");

    // -- PlayerList -----------------------------------------------------
    static final String M_GET_PLAYER_UUID             = im("net.minecraft.server.players.PlayerList","getPlayer",            "(Ljava/util/UUID;)Lnet/minecraft/server/level/ServerPlayer;",   "m_11259_");
    static final String M_GET_PLAYER_BY_NAME          = im("net.minecraft.server.players.PlayerList","getPlayerByName",      "(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;", "m_11255_");
    static final String M_GET_PLAYERS                 = im("net.minecraft.server.players.PlayerList","getPlayers",           "()Ljava/util/List;",                                           "m_11314_");
    static final String M_IS_OP                       = im("net.minecraft.server.players.PlayerList","isOp",                 "(Lcom/mojang/authlib/GameProfile;)Z",                          "m_11303_");
    static final String M_BROADCAST_SYSTEM_MSG        = im("net.minecraft.server.players.PlayerList","broadcastSystemMessage","(Lnet/minecraft/network/chat/Component;Z)V",                   "m_43514_");
    static final String M_BROADCAST_MSG               = im("net.minecraft.server.players.PlayerList","broadcastMessage",     "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/ChatType;Ljava/util/UUID;)V", "m_11264_");
    static final String M_BROADCAST                   = im("net.minecraft.server.players.PlayerList","broadcast",            "(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",            "m_43512_");

    // -- CommandSourceStack ----------------------------------------------
    static final String M_GET_SERVER                  = im("net.minecraft.commands.CommandSourceStack", "getServer",           "()Lnet/minecraft/server/MinecraftServer;",                                  "m_81377_");
    static final String M_GET_ENTITY                  = im("net.minecraft.commands.CommandSourceStack", "getEntity",           "()Lnet/minecraft/world/entity/Entity;",                                     "m_81373_");
    static final String M_HAS_PERMISSION              = im("net.minecraft.commands.CommandSourceStack", "hasPermission",       "(I)Z",                                                                     "m_6761_");

    // ==================================================================
    // FIELD NAMES
    // ==================================================================

    static final String F_BUILTIN_BLOCK               = ifd("net.minecraft.core.registries.BuiltInRegistries", "BLOCK", "Lnet/minecraft/core/DefaultedRegistry;", null);
    static final String F_REGISTRY_BLOCK              = ifd("net.minecraft.core.Registry",             "BLOCK",          "Lnet/minecraft/core/DefaultedRegistry;",  null);

    // ==================================================================
    // Runtime method name redirector — mirrors Forge. Maps bare mojang names
    // (and aliases) to their resolved runtime form (Mojang or Searge), so calls
    // like getPlayerByUUID work on both NeoForge 26.2 (Mojang) and NeoForge
    // 1.20.4 / Arclight-neoforge (Searge).
    // ==================================================================

    private static final java.util.Map<String,String> METHOD_REDIRECT = new java.util.HashMap<>();
    static {
        // MinecraftServer
        putM("getPlayerList",       M_GET_PLAYER_LIST);
        putM("getAllLevels",        M_GET_ALL_LEVELS);
        putM("getCommands",         M_GET_COMMANDS);
        putM("createCommandSourceStack", M_CREATE_COMMAND_SOURCE_STACK);
        putM("getTickCount",        M_GET_TICK_COUNT);
        putM("getLevel",            M_GET_LEVEL);
        putM("sendSystemMessage",   M_SEND_SYSTEM_MSG_SRV);
        // Aliases
        putM("getTicks",            M_GET_TICK_COUNT);
        putM("getWorld",            M_GET_LEVEL);
        putM("getPlayerManager",    M_GET_PLAYER_LIST);
        putM("getCommandManager",   M_GET_COMMANDS);
        putM("getWorlds",           M_GET_ALL_LEVELS);

        // Entity / Player
        putM("getName",             M_GET_NAME);
        putM("getUUID",             M_GET_UUID);
        putM("getUuid",             M_GET_UUID);
        putM("getGameProfile",      M_GET_GAME_PROFILE);

        // PlayerList
        putM("getPlayer",           M_GET_PLAYER_UUID); // PlayerList.getPlayer(UUID) — see getPlayerByUUID
        putM("getPlayerByUUID",     M_GET_PLAYER_UUID);
        putM("getPlayerByName",     M_GET_PLAYER_BY_NAME);
        putM("getPlayers",          M_GET_PLAYERS);
        putM("isOp",                M_IS_OP);
        putM("broadcastSystemMessage", M_BROADCAST_SYSTEM_MSG);
        putM("broadcastMessage",    M_BROADCAST_MSG);
        putM("broadcast",           M_BROADCAST);

        // CommandSourceStack
        putM("getServer",           M_GET_SERVER);
        putM("getEntity",           M_GET_ENTITY);
        putM("hasPermission",       M_HAS_PERMISSION);

        // Misc
        putM("getString",           "getString");
        putM("getValue",            "getValue");
    }

    private static void putM(String mojangName, String resolved) {
        METHOD_REDIRECT.put(mojangName, resolved);
    }

    /** Redirect a bare mojang method name to its resolved runtime form. */
    static String redirectMethod(String bareName) {
        String r = METHOD_REDIRECT.get(bareName);
        return r != null ? r : bareName;
    }

    static String redirectField(String bareName) { return bareName; }

    static String toRuntimeClass(String named) { return named; }
}
