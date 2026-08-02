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
    static final String CLS_TEXT_COMPONENT            = "net.minecraft.network.chat.TextComponent";

    // ==================================================================
    // METHOD NAMES (only those referenced elsewhere)
    // ==================================================================

    static final String M_COMPONENT_LITERAL           = "literal";

    // ==================================================================
    // FIELD NAMES (only those referenced elsewhere)
    // ==================================================================

    static final String F_BUILTIN_BLOCK               = "BLOCK";
    static final String F_REGISTRY_BLOCK              = "BLOCK";

    // ==================================================================
    // Runtime name redirectors
    // NeoForge uses Mojang/named names at runtime, so every name resolves
    // to itself — these are identity functions.
    // ==================================================================

    static String redirectMethod(String bareName) { return bareName; }

    static String redirectField(String bareName) { return bareName; }

    static String toRuntimeClass(String named) { return named; }
}
