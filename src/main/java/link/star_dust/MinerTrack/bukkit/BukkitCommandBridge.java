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
    public void toggleVerbose() {
        if (sender instanceof Player player) {
            UUID playerId = player.getUniqueId();
            if (verbosePlayers.contains(playerId)) {
                verbosePlayers.remove(playerId);
                sender.sendMessage("[MinerTrack] Verbose mode disabled.");
            } else {
                verbosePlayers.add(playerId);
                sender.sendMessage("[MinerTrack] Verbose mode enabled.");
            }
        } else if (sender instanceof ConsoleCommandSender) {
            verboseConsole = !verboseConsole;
            sender.sendMessage("[MinerTrack] Console verbose " + (verboseConsole ? "enabled" : "disabled") + ".");
        }
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