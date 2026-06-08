package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import org.bukkit.ChatColor;
import java.util.UUID;

public class BukkitAdapter implements PluginAdapter {
    private final JavaPlugin plugin;
    private final YamlLoader yamlLoader = new BukkitYamlLoader();
    // Cached value of the `debug` config flag. Populated lazily by
    // isDebugEnabled() on first call; refreshed by reloadConfig() so a
    // /minertrack reload picks up an operator's debug toggle without a
    // server restart. Default false (most restrictive option).
    private volatile Boolean debugCached = null;

    public BukkitAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Platform-specific debug-flag reader used by the {@code core/}
     * layer's {@code CoreLogger} initialisation. Reads the {@code
     * debug} key directly from the user's {@code config.yml},
     * defaulting to {@code false} when the key is missing or
     * unreadable.
     *
     * <p>Implementation note: this deliberately bypasses
     * {@link link.star_dust.MinerTrack.core.config.ConfigMerger}.
     * The merger is a write-back pass (it normalises the file, fills
     * missing keys, and saves the result), which is the wrong tool
     * for a per-event hot-path boolean read. A previous
     * implementation routed through the merger and was prone to
     * stale-cache issues after a {@code /minertrack reload} (the
     * cache invalidation worked, but the next read would re-run the
     * whole merge+save cycle, which is slow and could swallow the
     * operator's edit if the save side-effect collided with a
     * concurrent reload). Reading the file directly via
     * {@link YamlLoader#loadFile} gives the same answer without any
     * side effects.
     *
     * <p>This is intentionally not on the {@code PluginAdapter}
     * interface: debug logging is a developer-only feature and we
     * don't want to force every platform (Fabric, …) to implement
     * it. The platform-specific init code in {@code BukkitPlatform}
     * reads this method directly and adapts it into a
     * {@code DebugConfig} for the core.
     */
    public boolean isDebugEnabled() {
        Boolean cached = debugCached;
        if (cached != null) return cached;
        try {
            java.io.File cfg = new java.io.File(plugin.getDataFolder(), "config.yml");
            // loadFile (not loadStream) is the right call here: it
            // reads the user's disk file directly and never touches
            // the JAR-shipped default. We do NOT want to merge with
            // defaults — the operator's edit has authority, and the
            // merger would clobber the disk with the default's
            // `debug: false` if the merge step ever tried to
            // re-write the key.
            link.star_dust.MinerTrack.common.CommonYaml userConfig = yamlLoader.loadFile(cfg);
            boolean value = userConfig.getBoolean("debug", false);
            debugCached = value;
            return value;
        } catch (Exception e) {
            // If we can't read the config, stay debug-disabled. Don't
            // bubble the error — the operator has bigger problems.
            plugin.getLogger().warning("Could not read debug flag from config.yml: " + e.getMessage());
            debugCached = Boolean.FALSE;
            return false;
        }
    }

    /** Drop the cached debug flag so the next isDebugEnabled() call
     *  re-reads from disk. Called by reloadConfig(). */
    public void clearDebugCache() {
        debugCached = null;
    }

    @Override
    public File getDataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public void saveResource(String resourcePath, boolean replace) {
        plugin.saveResource(resourcePath, replace);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getVersion() {
        try {
            return plugin.getDescription().getVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public void info(String msg) {
        plugin.getLogger().info(msg);
    }

    @Override
    public void warning(String msg) {
        plugin.getLogger().warning(msg);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String applyColors(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public void sendConsoleMessage(String message) {
        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    @Override
    public Object getPlayer(UUID uuid) {
        return plugin.getServer().getPlayer(uuid);
    }

    @Override
    public java.io.InputStream getResource(String resourcePath) {
        return plugin.getResource(resourcePath);
    }

    @Override
    public void reloadConfig() {
        // v2 doesn't use Bukkit's plugin.getConfig() (the platform-agnostic
        // ConfigMerger reads the file directly), so we deliberately skip
        // plugin.reloadConfig() here. The /minertrack reload command only
        // needs the ConfigMerger pass and the group-config refresh below.
        //
        // After the merge pass, ensure missing keys are filled from
        // defaults. We log (not swallow) any I/O / parse failure so admins
        // can diagnose a broken config instead of silently running with
        // the previous in-memory copy.
        try {
            java.io.File cfg = new java.io.File(plugin.getDataFolder(), "config.yml");
            link.star_dust.MinerTrack.core.config.ConfigMerger.loadAndMerge(cfg, "config.yml", this, yamlLoader);
        } catch (Exception e) {
            info("Failed to reload config.yml: " + e.getMessage());
        }
        // Invalidate the cached debug flag so a /minertrack reload
        // immediately picks up the operator's debug toggle change
        // instead of waiting for the boolean to be re-read on the
        // next isDebugEnabled() call from a different code path.
        clearDebugCache();
        // Also reload group configs via the active DetectionBridge so they
        // are refreshed on every reload. Clear the per-bridge config
        // cache first so the merge step reads the just-saved file from
        // disk instead of serving the previous 5-second-cached copy.
        try {
            BukkitDetectionBridge active = BukkitDetectionBridge.getActive();
            if (active != null) {
                active.clearConfigCache();
                active.loadGroupConfigs();
            }
        } catch (Exception e) {
            info("Failed to reload group configs: " + e.getMessage());
        }
    }

    @Override
    public Object getPlugin() {
        return plugin;
    }

    /** Platform-specific YAML loader exposed to the core config layer. */
    public YamlLoader getYamlLoader() {
        return yamlLoader;
    }
}
