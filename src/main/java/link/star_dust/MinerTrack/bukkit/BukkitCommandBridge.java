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

package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommandBridge;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public class BukkitCommandBridge implements CommandBridge {
    private final CommandSender sender;
    // Shared verbose players set (also used by ViolationManager)
    private final Set<UUID> verbosePlayers;
    private boolean verboseConsole = false;

    public BukkitCommandBridge(CommandSender sender, Set<UUID> verbosePlayers) {
        this.sender = sender;
        this.verbosePlayers = verbosePlayers;
    }

    @Override
    public void dispatchCommand(String command) {
        Bukkit.dispatchCommand(sender, command);
    }

    @Override
    public boolean isPlayer() {
        return sender instanceof Player;
    }

    @Override
    public boolean isConsole() {
        return sender instanceof ConsoleCommandSender;
    }

    @Override
    public Object getSender() {
        return sender;
    }

    @Override
    public void sendMessage(String message) {
        sender.sendMessage(message);
    }

    @Override
    public void sendMessageToPlayer(UUID playerId, String message) {
        Player p = Bukkit.getPlayer(playerId);
        if (p != null) p.sendMessage(message);
    }

    @Override
    public void sendMessageToConsole(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
    }

    @Override
    public boolean toggleVerbose() {
        if (sender instanceof Player player) {
            UUID playerId = player.getUniqueId();
            if (verbosePlayers.contains(playerId)) {
                verbosePlayers.remove(playerId);
                return false;
            } else {
                verbosePlayers.add(playerId);
                return true;
            }
        } else if (sender instanceof ConsoleCommandSender) {
            verboseConsole = !verboseConsole;
            return verboseConsole;
        }
        // Unknown sender type — leave state untouched, report
        // "disabled" as a safe default so the core layer can still
        // print the (also safe) `verbose-disable` message.
        return false;
    }

    @Override
    public boolean hasPermission(String node) {
        return sender.hasPermission(node);
    }

    @Override
    public boolean hasPermissionForPlayer(UUID playerId, String node) {
        Player p = Bukkit.getPlayer(playerId);
        return p != null && p.hasPermission(node);
    }

    public boolean isVerboseConsoleEnabled() {
        return verboseConsole;
    }
}