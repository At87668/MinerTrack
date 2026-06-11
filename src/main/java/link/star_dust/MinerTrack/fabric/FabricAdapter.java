package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;
import link.star_dust.MinerTrack.core.config.ConfigMerger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Fabric implementation of the platform-agnostic {@link PluginAdapter}.
 *
 * <p>Mirrors {@code BukkitAdapter} structurally: holds a
 * platform-specific data folder, a YAML loader, an optional cached
 * debug flag, and a logger. The Fabric server's console is the only
 * built-in log sink we have here; we route the same
 * {@code [MinerTrack:DEBUG]} / {@code [MinerTrack]} prefix through
 * the Fabric server's logger via the {@link #info(String)} and
 * {@link #warning(String)} methods so the v2 {@code CoreLogger} debug
 * lines still appear in {@code logs/latest.log}.
 *
 * <p>All {@code net.minecraft.*} access goes through
 * {@link FabricReflection} because the Minecraft server classes are
 * NOT on the project's compile classpath (the project compiles
 * against the Bukkit API, with the Minecraft server jar supplied at
 * runtime by the Fabric loader). Reflection lets the same compiled
 * class work in a Bukkit build (where the lookup simply fails) and
 * in a Fabric build (where the lookup succeeds against the server's
 * runtime classpath).
 */
public class FabricAdapter implements PluginAdapter {
    private final File dataFolder;
    private final YamlLoader yamlLoader = new FabricYamlLoader();
    private final String version;
    private volatile Boolean debugCached = null;

    public FabricAdapter() {
        // Fabric's "data folder" lives inside the world's
        // config/minertrack directory on dedicated servers. We pick
        // the dedicated-server config root so reload / save-resource
        // paths match the rest of the mod (config.yml, language.yml,
        // Configuration/...). The modid is lowercase and matches
        // fabric.mod.json.
        Path root = FabricLoader.getInstance().getConfigDir();
        this.dataFolder = root.resolve("minertrack").toFile();
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }
        // Read the running mod version from the mod metadata. This
        // mirrors what Bukkit's JavaPlugin#getDescription().getVersion()
        // returns, so the version string the update checker compares
        // against Modrinth is consistent across platforms.
        String v;
        try {
            v = FabricLoader.getInstance()
                    .getModContainer("minertrack")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Throwable t) {
            v = "unknown";
        }
        this.version = v;
    }

    /**
     * Fabric-specific debug-flag reader used by the {@code core/}
     * layer's {@code CoreLogger} initialisation. Reads the
     * {@code debug} key directly from the user's {@code config.yml},
     * defaulting to {@code false} when the key is missing or
     * unreadable. Mirrors {@code BukkitAdapter.isDebugEnabled} —
     * reads the file directly (never goes through the merger) so
     * startup is not slowed down by a write-back pass.
     */
    public boolean isDebugEnabled() {
        Boolean cached = debugCached;
        if (cached != null) return cached;
        try {
            File cfg = new File(dataFolder, "config.yml");
            CommonYaml userConfig = yamlLoader.loadFile(cfg);
            boolean value = userConfig.getBoolean("debug", false);
            debugCached = value;
            return value;
        } catch (Exception e) {
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
        return dataFolder;
    }

    @Override
    public void saveResource(String resourcePath, boolean replace) {
        // Mirror the Bukkit saveResource contract: copy the named
        // resource from the classpath to the data folder, replacing
        // the file when {@code replace == true} (or when the file
        // doesn't exist on disk). The mod jar's resources live at
        // the classpath root alongside the {@code fabric.mod.json}
        // we just created; using the classloader of
        // {@link FabricAdapter} picks them up because every class
        // in the mod jar shares the same class loader.
        File target = new File(dataFolder, resourcePath);
        if (target.exists() && !replace) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                // Resource not packaged with the mod — fall through
                // silently so the config merger can create a
                // default-empties config without throwing. This is
                // consistent with Bukkit's "skip silently" behaviour
                // when a resource is missing.
                return;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            // Don't throw — the merger falls back to an empty
            // config in this case, which is safer than aborting
            // startup because of a malformed resource on disk.
            warning("Failed to save resource " + resourcePath + ": " + e.getMessage());
        }
    }

    @Override
    public InputStream getResource(String resourcePath) {
        return getClass().getClassLoader().getResourceAsStream(resourcePath);
    }

    @Override
    public void reloadConfig() {
        try {
            File cfg = new File(dataFolder, "config.yml");
            ConfigMerger.loadAndMerge(cfg, "config.yml", this, yamlLoader);
        } catch (Exception e) {
            info("Failed to reload config.yml: " + e.getMessage());
        }
        clearDebugCache();
        // Group configs are reloaded by the platform's reload path
        // (FabricPlatform.reload), not the adapter.
    }

    @Override
    public String getVersion() {
        return version;
    }

    /**
     * Resolve {@code net.minecraft.server.MinecraftServer} at
     * runtime. Returns null on a non-Fabric classpath (e.g. when
     * the mod jar is accidentally loaded on a Bukkit server).
     */
    private Object minecraftServer() {
        try {
            Class<?> cls = Class.forName("net.minecraft.server.MinecraftServer");
            // 1.20+ exposes MinecraftServer.getServer() as a
            // static method that returns the running server
            // instance. Earlier 1.18 builds have an instance
            // field called "instance" or a static method of
            // the same name. Try getServer() first, then fall
            // back to a field lookup.
            try {
                java.lang.reflect.Method m = cls.getMethod("getServer");
                return m.invoke(null);
            } catch (NoSuchMethodException nsme) {
                java.lang.reflect.Field f = cls.getDeclaredField("instance");
                f.setAccessible(true);
                return f.get(null);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void info(String msg) {
        // Fabric's logger is reachable through the dedicated server
        // logger; use the SLF4J-style API. The platform's logger
        // is configured to send "INFO" to the standard server log
        // file. We do not prefix the message here — the Fabric
        // server adds its own prefix to the line.
        Object server = minecraftServer();
        if (server != null) {
            // net.minecraft.text.Text.literal(msg).sendMessage(...)
            // We avoid holding a Text reference; build via
            // reflection so this class compiles on a non-Fabric
            // classpath.
            try {
                Class<?> textCls = Class.forName("net.minecraft.text.Text");
                java.lang.reflect.Method literal = textCls.getMethod("literal", String.class);
                Object text = literal.invoke(null, msg);
                // ServerPlayerEntity isn't on the classpath; we
                // use the Server interface's sendMessage. The
                // MinecraftServer instance implements
                // {@code CommandOutput} and has a sendMessage
                // overload that takes a Text.
                java.lang.reflect.Method sendMessage = server.getClass().getMethod("sendMessage", textCls);
                sendMessage.invoke(server, text);
                return;
            } catch (Throwable t) {
                // Fall through to System.out.
            }
        }
        // Fallback: System.out for very early startup or for
        // running outside a Fabric server. The Minecraft server
        // log handler is configured by the server at boot and
        // routes System.out lines through its own formatting, so
        // this is acceptable.
        System.out.println("[MinerTrack] " + msg);
    }

    @Override
    public void warning(String msg) {
        Object server = minecraftServer();
        if (server != null) {
            try {
                Class<?> textCls = Class.forName("net.minecraft.text.Text");
                java.lang.reflect.Method literal = textCls.getMethod("literal", String.class);
                Object text = literal.invoke(null, "[WARN] " + msg);
                java.lang.reflect.Method sendMessage = server.getClass().getMethod("sendMessage", textCls);
                sendMessage.invoke(server, text);
                return;
            } catch (Throwable t) {
                // Fall through.
            }
        }
        System.out.println("[MinerTrack] [WARN] " + msg);
    }

    @Override
    public String applyColors(String message) {
        // Fabric chat colour codes use the section sign (§) by
        // default; '&' → '§' translation is the same as the Bukkit
        // adapter. We do NOT call Minecraft's Text Serialization
        // because the call site is the log renderer (printed
        // straight to the console) and we want the legacy section
        // sign, not the JSON form.
        if (message == null) return "";
        char[] chars = message.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(chars[i + 1]) > -1) {
                chars[i] = '§';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    @Override
    public void sendConsoleMessage(String message) {
        // Same as info() — Fabric only has one console target.
        info(message);
    }

    @Override
    public Object getPlayer(UUID uuid) {
        // The core layer's PathAnalyzer etc. never need a live
        // Player reference on Fabric (the path state is keyed by
        // UUID via MiningState). Return null if the player is
        // offline; the core layer treats null as "skip this tick".
        try {
            Object server = minecraftServer();
            if (server == null) return null;
            Class<?> serverCls = server.getClass();
            // ServerNetworkIo and similar internals vary across
            // 1.18-1.21 versions; the public PlayerManager is
            // reached via getPlayerManager() in every version.
            java.lang.reflect.Method getPm = serverCls.getMethod("getPlayerManager");
            Object pm = getPm.invoke(server);
            java.lang.reflect.Method getPlayer = pm.getClass().getMethod("getPlayer", UUID.class);
            return getPlayer.invoke(pm, uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Object getPlugin() {
        // Fabric doesn't have a "plugin instance" in the Bukkit
        // sense; return the dedicated server so callers that need
        // the MinecraftServer (e.g. the async scheduler) get it.
        return minecraftServer();
    }

    /** Platform-specific YAML loader exposed to the core config layer. */
    public YamlLoader getYamlLoader() {
        return yamlLoader;
    }
}
