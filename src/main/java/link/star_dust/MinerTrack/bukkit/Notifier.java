package link.star_dust.MinerTrack.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

@SuppressWarnings("unused")
public class Notifier {

    public Notifier() {
    }

    @SuppressWarnings("deprecation")
    public void kickPlayer(Player player, String reason) {
        player.kickPlayer(reason);
    }

    @SuppressWarnings("deprecation")
    public void sendNotifyMessage(String messageContent) {
        String prefix = ChatColor.translateAlternateColorCodes('&', "&8[&9&lMiner&c&lTrack&8]&r ");
        String formattedMessage = prefix + ChatColor.translateAlternateColorCodes('&', messageContent);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("minertrack.notify")) {
                player.sendMessage(formattedMessage);
            }
        }

        Bukkit.getConsoleSender().sendMessage(formattedMessage);
    }
}
