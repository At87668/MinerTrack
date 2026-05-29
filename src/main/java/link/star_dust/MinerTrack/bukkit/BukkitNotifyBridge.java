package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.NotifyBridge;
import link.star_dust.MinerTrack.common.PluginAdapter;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BukkitNotifyBridge implements NotifyBridge {
    private final PluginAdapter plugin;
    private final Set<UUID> verbosePlayers = ConcurrentHashMap.newKeySet();
    private boolean verboseConsole = false;

    public BukkitNotifyBridge(PluginAdapter plugin) {
        this.plugin = plugin;
    }

    @Override
    public void notify(String message) {
        String colored = plugin.applyColors(message);
        plugin.sendConsoleMessage(colored);
        for (UUID uuid : verbosePlayers) {
            Object obj = plugin.getPlayer(uuid);
            Player p = obj instanceof Player ? (Player) obj : null;
            if (p != null) {
                p.sendMessage(colored);
            }
        }
    }

    @Override
    public void notifyRaw(String message) {
        plugin.sendConsoleMessage(message);
        for (UUID uuid : verbosePlayers) {
            Object obj = plugin.getPlayer(uuid);
            Player p = obj instanceof Player ? (Player) obj : null;
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    @Override
    public boolean isVerboseEnabled(Object player) {
        if (player instanceof Player) {
            return verbosePlayers.contains(((Player) player).getUniqueId());
        }
        return false;
    }

    @Override
    public void setVerboseEnabled(Object player, boolean enabled) {
        if (player instanceof Player) {
            UUID uuid = ((Player) player).getUniqueId();
            if (enabled) {
                verbosePlayers.add(uuid);
            } else {
                verbosePlayers.remove(uuid);
            }
        }
    }

    @Override
    public boolean isVerboseConsole() {
        return verboseConsole;
    }

    @Override
    public void setVerboseConsole(boolean enabled) {
        this.verboseConsole = enabled;
    }
}