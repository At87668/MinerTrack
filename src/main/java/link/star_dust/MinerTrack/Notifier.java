/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/Notifier.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack;

import link.star_dust.MinerTrack.managers.LanguageManager;

import java.util.Arrays;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Notifier {
    private final MinerTrack plugin;
    private final LanguageManager lang;

    public Notifier(MinerTrack plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }
    
    public void kickPlayer(Player player, String reason) {
        player.kickPlayer(reason);
    }


    public void sendNotifyMessage(String messageContent) {
        // Define prefixes and add color codes
        String prefix = ChatColor.translateAlternateColorCodes('&', "&8[&9&lMiner&c&lTrack&8]&r ");
        String formattedMessage = prefix + ChatColor.translateAlternateColorCodes('&', messageContent);
        
        // Send message to players with permissions
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("minertrack.notify")) {
                player.sendMessage(formattedMessage);
            }
        }
        
        // Send message to console
        Bukkit.getConsoleSender().sendMessage(formattedMessage);
    }
}

