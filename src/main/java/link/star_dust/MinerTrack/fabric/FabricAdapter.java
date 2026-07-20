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
 * Fabric PluginAdapter. Mirrors BukkitAdapter structurally.
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
        this.dataFolder = root.resolve("MinerTrack").toFile();
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
     * runtime. Uses FabricReflection.getServer() which handles
     * MC 26.1+ (cached instance) and 1.18-1.21 (static getServer()).
     */
    private Object minecraftServer() {
        return FabricReflection.getServer();
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
            try {
                // Build a text component: try Component.literal() (MC 26.1+),
                // Text.literal() (MC 1.19.3+), then LiteralText (MC 1.18-1.19.2).
                Object text = createTextComponent(msg);
                if (text == null) return;
                Class<?> textCls = resolveTextComponentClass();
                if (textCls == null) return;
                // Send via the server. MC 26.1+: sendSystemMessage(Component)
                // 1.18-1.21: sendMessage(Text) or sendMessage(Text, boolean)
                // Mojang: MinecraftServer.sendSystemMessage(Component)
                try {
                    java.lang.reflect.Method m = FabricReflection.findMethod(
                        server.getClass(), FabricReflectionConstants.M_SEND_SYSTEM_MSG_SRV, new Class<?>[]{textCls});
                    if (m != null) {
                        m.invoke(server, text);
                        return;
                    }
                } catch (Throwable t1) { /* fall through */ }
                try {
                    java.lang.reflect.Method sendMessage = server.getClass().getMethod("sendMessage", textCls);
                    sendMessage.invoke(server, text);
                } catch (NoSuchMethodException nsme) {
                    try {
                        java.lang.reflect.Method sendMessage = server.getClass().getMethod("sendMessage", textCls, boolean.class);
                        sendMessage.invoke(server, text, false);
                    } catch (Throwable ignored) {}
                }
                return;
            } catch (Throwable t) {
                // Fall through to System.out.
            }
        }
        // Fallback: System.out for very early startup or for
        // running outside a Fabric server. The Minecraft server
        // log handler is configured by the server at boot and
        // routes System.out lines through its own formatting, so
        // this is acceptable. We translate the {@code §}
        // section-sign colour codes to ANSI escapes via
        // {@link #toAnsi(String)}, which is a no-op for plain
        // text (so the terminal's default state is preserved
        // for uncoloured log lines) and appends a {@code
        // \u001B[0m} reset at the end of every translated line
        // so the formatting doesn't bleed into the next server
        // log entry the server writes after ours.
        System.out.println("[MinerTrack] " + toAnsi(msg));
    }

    @Override
    public void warning(String msg) {
        Object server = minecraftServer();
        if (server != null) {
            try {
                Object text = createTextComponent("[WARN] " + msg);
                if (text == null) return;
                Class<?> textCls = resolveTextComponentClass();
                if (textCls == null) return;
                java.lang.reflect.Method sendMessage = server.getClass().getMethod("sendMessage", textCls);
                sendMessage.invoke(server, text);
                return;
            } catch (Throwable t) {
                // Fall through.
            }
        }
        System.out.println("[MinerTrack] [WARN] " + toAnsi(msg));
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

    /**
     * Translate Minecraft colour codes (the {@code §} section
     * sign variant produced by {@link #applyColors(String)})
     * into ANSI escape sequences for stdout. The standard
     * Minecraft log handler doesn't render {@code §} codes
     * (it's a private-use Unicode codepoint), so without this
     * translation the banner lines would print with literal
     * {@code §} characters visible in the terminal. The
     * translation covers the same colour/formatting codes the
     * Bukkit adapter's {@code ChatColor.translateAlternateColorCodes}
     * recognises, plus the {@code §r} reset / {@code §l}/{@code §o}
     * / {@code §n}/{@code §m} formatting codes; {@code §k}
     * (obfuscated) has no ANSI equivalent and is silently
     * dropped.
     *
     * <p>This strategy is applied only when the input actually
     * contains a {@code §} colour code; plain text passes
     * through untouched so the terminal's default formatting
     * isn't disturbed. When a translation does happen, a
     * {@code \u001B[0m} reset escape is appended at the end
     * of the returned string so the formatting doesn't bleed
     * into the next server log line — i.e. the plugin
     * "restores" the terminal's default state after sending
     * the coloured text. The reset is skipped when the input
     * is null / empty so we don't pollute the log with stray
     * escape codes.
     *
     * <p>Implementation note: we walk the string in one pass
     * building a {@link StringBuilder}; the {@link String#replace}
     * chained calls would be a touch slower on long banners
     * and the inline approach makes the per-code mapping
     * explicit in one place.
     */
    static String toAnsi(String input) {
        if (input == null) return "";
        // Fast path: no colour code at all → return the
        // input unchanged. Skipping the {@link StringBuilder}
        // for plain text avoids the per-call allocation
        // overhead for the (much more frequent) plain
        // logging case.
        if (input.indexOf('§') < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length() + 32);
        int len = input.length();
        boolean translated = false;
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (c == '§' && i + 1 < len) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                String ansi = ansiForCode(code);
                if (ansi != null) {
                    out.append(ansi);
                    i++; // consume the code letter
                    translated = true;
                    continue;
                }
            }
            out.append(c);
        }
        if (translated) {
            // Restore: append the ANSI reset so the
            // formatting doesn't bleed into the next
            // log line that the server writes after
            // ours. Without this, e.g. an italic
            // banner line would italicise every
            // subsequent server log entry.
            out.append("\u001B[0m");
        }
        return out.toString();
    }

    private static String ansiForCode(char code) {
        switch (code) {
            case '0': return "\u001B[30m"; // black
            case '1': return "\u001B[34m"; // dark blue
            case '2': return "\u001B[32m"; // dark green
            case '3': return "\u001B[36m"; // dark cyan
            case '4': return "\u001B[31m"; // dark red
            case '5': return "\u001B[35m"; // dark magenta
            case '6': return "\u001B[33m"; // dark yellow
            case '7': return "\u001B[37m"; // gray
            case '8': return "\u001B[90m"; // dark gray
            case '9': return "\u001B[94m"; // blue
            case 'a': return "\u001B[92m"; // green
            case 'b': return "\u001B[96m"; // cyan
            case 'c': return "\u001B[91m"; // red
            case 'd': return "\u001B[95m"; // magenta
            case 'e': return "\u001B[93m"; // yellow
            case 'f': return "\u001B[97m"; // white
            case 'l': return "\u001B[1m";  // bold
            case 'o': return "\u001B[3m";  // italic
            case 'n': return "\u001B[4m";  // underline
            case 'm': return "\u001B[9m";  // strikethrough
            case 'r': return "\u001B[0m";  // reset
            case 'k': return null;         // obfuscated, no ANSI equivalent
            default:  return null;
        }
    }

    @Override
    public void sendConsoleMessage(String message) {
        // Same as info() — Fabric only has one console target.
        info(message);
    }

    @Override
    public Object getPlayer(UUID uuid) {
        try {
            Object server = minecraftServer();
            if (server == null) return null;
            // Mojang: MinecraftServer.getPlayerList(); 1.18-1.21: getPlayerManager()
            // Mojang: PlayerList.getPlayer(UUID) → ServerPlayer
            Object pm = FabricReflection.callMigrated(server,
                FabricReflectionConstants.M_GET_PLAYER_LIST, "getPlayerManager",
                FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (pm == null) return null;
            return FabricReflection.call(pm, FabricReflectionConstants.M_GET_PLAYER_UUID,
                new Class<?>[]{UUID.class}, new Object[]{uuid});
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

    // ── Text/Component helpers ─────────────────────────────────────

    /**
     * Create a Minecraft text/component from a plain string, trying
     * all known MC version paths.
     */
    private static Object createTextComponent(String message) {
        // 1. MC 26.1+: Component.literal(String)
        try {
            Class<?> compCls = Class.forName("net.minecraft.network.chat.Component");
            java.lang.reflect.Method literal = compCls.getMethod("literal", String.class);
            return literal.invoke(null, message);
        } catch (Throwable t) { /* fall through */ }

        // 2. MC 1.19.3+: Component.literal(String) via FabricReflection
        try {
            // Mojang: net.minecraft.network.chat.Component
            Class<?> textCls = FabricReflection.forName(FabricReflectionConstants.CLS_COMPONENT);
            if (textCls != null) {
                java.lang.reflect.Method literal = textCls.getMethod("literal", String.class);
                return literal.invoke(null, message);
            }
        } catch (Throwable t) { /* fall through */ }

        // 3. MC 1.18-1.19.2: new TextComponent(String)
        try {
            // Mojang: net.minecraft.network.chat.TextComponent → Component
            Class<?> ltCls = FabricReflection.forName(FabricReflectionConstants.CLS_COMPONENT);
            return ltCls.getDeclaredConstructor(String.class).newInstance(message);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve the Minecraft text component class at runtime.
     */
    private static Class<?> resolveTextComponentClass() {
        try {
            return Class.forName("net.minecraft.network.chat.Component");
        } catch (ClassNotFoundException e) {
            try {
                // Mojang: net.minecraft.network.chat.Component
                return FabricReflection.forName(FabricReflectionConstants.CLS_COMPONENT);
            } catch (Throwable ex) {
                return null;
            }
        }
    }
}
