package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommandBridge;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

public class BukkitCommandBridge implements CommandBridge {
    private final CommandSender sender;

    public BukkitCommandBridge(CommandSender sender) {
        this.sender = sender;
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
}