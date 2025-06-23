/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/MinerTrack.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.json.JSONObject;

import link.star_dust.MinerTrack.managers.ConfigManager;
import link.star_dust.MinerTrack.managers.LanguageManager;
import link.star_dust.MinerTrack.managers.UpdateManager;
import link.star_dust.MinerTrack.managers.ViolationManager;
import link.star_dust.MinerTrack.listeners.MiningDetectionExtension;
import link.star_dust.MinerTrack.listeners.MiningListener;
import link.star_dust.MinerTrack.commands.*;

public class MinerTrack extends JavaPlugin implements Listener {
    private ConfigManager configManager;
    private LanguageManager languageManager;
    private ViolationManager violationManager;
    private Notifier notifier;
    private final Set<UUID> verbosePlayers = new HashSet<>();
    private boolean verboseConsole = false;
    private UpdateManager updateManager;
    private String ColoredVersion;
    public MiningDetectionExtension miningDetectionExtension;

    @Override
    public void onEnable() {
    	saveDefaultConfig();
        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this);
        violationManager = new ViolationManager(this);
        notifier = new Notifier(this);
        updateManager = new UpdateManager(this);
        //miningDetectionExtension = new MiningDetectionExtension(this);
        //miningDetectionExtension.register();
        
        int pluginId = 23790;
        new Metrics(this, pluginId);
        
        registerCommands();
        registerListeners();
        getCommand("minertrack").setExecutor(new MinerTrackCommand(this));
        
        if (getConfigManager().updateCheck()) {
        	boolean isHasNewerVersion = updateManager.isHasNewerVersion();
            
            if(isHasNewerVersion) {
            	ColoredVersion = "&cv" + getDescription().getVersion();
            } else {
            	ColoredVersion = "&av" + getDescription().getVersion();
            }
        } else {
        	ColoredVersion = "&av" + getDescription().getVersion();
        }
        
		getServer().getConsoleSender().sendMessage(applyColors("&8----[&9&lMiner&c&lTrack " + ColoredVersion + " &8]-----------"));
        getServer().getConsoleSender().sendMessage(applyColors("&9&lMiner&c&lTrack &4&oAnti-XRay &aEnabled!"));
        getServer().getConsoleSender().sendMessage(applyColors(""));
        getServer().getConsoleSender().sendMessage(applyColors("&7Authors: Author87668"));
        getServer().getConsoleSender().sendMessage(applyColors("&7Original Author: Author87668"));
        getServer().getConsoleSender().sendMessage(applyColors("&7Contributors: Author87668, Zhang12334"));
        getServer().getConsoleSender().sendMessage(applyColors(""));
        getServer().getConsoleSender().sendMessage(applyColors("&a&oThanks for your use!"));
        getServer().getConsoleSender().sendMessage(applyColors("&8-----------------------------------------"));
        
        /*if (getConfigManager().updateCheck()) {
            checkForUpdates(null);
      	} else {
      		return;
      	}*/
    }
    
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
    	if (getConfigManager().updateCheck()) {
          getLogger().info("Server has finished loading. Checking for updates...");
          checkForUpdates(null);
    	} else {
    		return;
    	}
    }
    
    public String applyColors(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void registerCommands() {
        MinerTrackCommand commandExecutor = new MinerTrackCommand(this);
        getCommand("mtrack").setExecutor(commandExecutor);
        getCommand("mtrack").setTabCompleter(commandExecutor);
    }

    private void registerListeners() {
		Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new MiningListener(this), this);
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }
    
    public Notifier getNotifier() {
        return notifier;
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }
    
    public Set<UUID> getVerbosePlayers() {
        return verbosePlayers;
    }
    
    public boolean isVerboseConsoleEnabled() {
        return verboseConsole;
    }

    public void toggleVerboseMode(CommandSender sender) {
        String enableMessage = getLanguageManager().getPrefixedMessage("verbose-enable");
        String disableMessage = getLanguageManager().getPrefixedMessage("verbose-disable");

        if (sender instanceof Player) {
            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();
            
            if (verbosePlayers.contains(playerId)) {
                verbosePlayers.remove(playerId);
                player.sendMessage(disableMessage);
            } else {
                verbosePlayers.add(playerId);
                player.sendMessage(enableMessage);
            }
        } else if (sender instanceof ConsoleCommandSender) {
            verboseConsole = !verboseConsole;
            
            if (verboseConsole) {
                sender.sendMessage(enableMessage);
            } else {
                sender.sendMessage(disableMessage);
            }
        }
    }

    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
    	if (!configManager.updateCheck()) {
    		return;
    	}
    	
        Player player = event.getPlayer();

        if (player.hasPermission("minertrack.checkupdate") && updateManager.isHasNewerVersion()) {
        	player.sendMessage(updateManager.updateMessageOnPlayerJoin());  // Call UpdateManager for player-specific check
            //updateManager.checkForUpdates(player);
        }
    }

    public void checkForUpdates(CommandSender sender) {
        updateManager.checkForUpdates(sender);
    }

    @Override
    public void onDisable() {
    	getServer().getConsoleSender().sendMessage(applyColors("&8----[&9&lMiner&c&lTrack " + ColoredVersion + " &8]-----------"));
    	getServer().getConsoleSender().sendMessage(applyColors("&9&lMiner&c&lTrack &4&oAnti-XRay &cDisabled!"));
    	getServer().getConsoleSender().sendMessage(applyColors(""));
        getServer().getConsoleSender().sendMessage(applyColors("&7Authors: Author87668"));
        getServer().getConsoleSender().sendMessage(applyColors("&7Original Author: Author87668"));
        getServer().getConsoleSender().sendMessage(applyColors("&7Contributors: Author87668, Zhang12334"));
        getServer().getConsoleSender().sendMessage(applyColors(""));
    	getServer().getConsoleSender().sendMessage(applyColors("&a&oGood bye!"));
    	getServer().getConsoleSender().sendMessage(applyColors("&8-----------------------------------------"));
    }
}