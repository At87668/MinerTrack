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

package link.star_dust.MinerTrack.forge;

import link.star_dust.MinerTrack.common.ModResourceLoader;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.YamlLoader;
import link.star_dust.MinerTrack.core.config.ConfigMerger;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Forge PluginAdapter. Mirrors FabricAdapter structurally.
 */
public class ForgeAdapter implements PluginAdapter {
    private final File dataFolder;
    private final YamlLoader yamlLoader = new ForgeYamlLoader();
    private final String version;
    private volatile Boolean debugCached = null;

    public ForgeAdapter() {
        // Forge config directory: FMLPaths.CONFIGDIR / MinerTrack
        Path root = ForgeReflection.getConfigDir();
        this.dataFolder = root.resolve("MinerTrack").toFile();
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }
        // Read the mod version from Forge's ModList
        String v;
        try {
            v = ForgeReflection.getModVersion("minertrack");
        } catch (Throwable t) {
            v = "unknown";
        }
        this.version = v;
    }

    public YamlLoader getYamlLoader() {
        return yamlLoader;
    }

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

    public void clearDebugCache() {
        debugCached = null;
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    @Override
    public void saveResource(String resourcePath, boolean replace) {
        // IMPORTANT: do NOT use {@code getClass().getClassLoader()
        // .getResourceAsStream(resourcePath)} here. On Forge every
        // mod is loaded into a single shared ClassLoader (the
        // game's LaunchClassLoader), so the classloader lookup
        // walks the entire game classpath and returns whichever
        // mod's resource happens to appear first. If another mod
        // on the classpath ships its own {@code config.yml}
        // (very common), MinerTrack would copy / parse that
        // other mod's config.yml into its own data folder on
        // first startup. {@link ModResourceLoader} bypasses the
        // shared classloader entirely by going through this
        // class's protection domain, so the lookup is anchored
        // to MinerTrack's own JAR regardless of what other mods
        // are installed.
        File target = new File(dataFolder, resourcePath);
        if (target.exists() && !replace) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = ModResourceLoader.open(getClass(), resourcePath)) {
            if (in == null) return;
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            warning("Failed to save resource " + resourcePath + ": " + e.getMessage());
        }
    }

    @Override
    public InputStream getResource(String resourcePath) {
        // See {@link #saveResource} for why we go through
        // {@link ModResourceLoader} instead of the classloader.
        return ModResourceLoader.open(getClass(), resourcePath);
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
    }

    @Override
    public String getVersion() {
        return version;
    }

    private Object minecraftServer() {
        return ForgeReflection.getServer();
    }

    @Override
    public void info(String msg) {
        Object server = minecraftServer();
        if (server != null) {
            try {
                Object text = ForgeReflection.createText(msg);
                if (text == null) return;
                Class<?> textCls = ForgeReflection.resolveTextComponentClass();
                if (textCls == null) return;
                try {
                    java.lang.reflect.Method m = ForgeReflection.findMethod(
                        server.getClass(), "sendSystemMessage", new Class<?>[]{textCls});
                    if (m != null) {
                        m.invoke(server, text);
                        return;
                    }
                } catch (Throwable t1) { /* fall through */ }
                try {
                    java.lang.reflect.Method m = ForgeReflection.findMethod(
                        server.getClass(), "sendSystemMessage", new Class<?>[]{textCls, UUID.class});
                    if (m != null) {
                        m.invoke(server, text, UUID.randomUUID());
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
            } catch (Throwable t) { }
        }
        System.out.println("[MinerTrack] " + toAnsi(msg));
    }

    @Override
    public void warning(String msg) {
        Object server = minecraftServer();
        if (server != null) {
            try {
                Object text = ForgeReflection.createText("[WARN] " + msg);
                if (text == null) return;
                Class<?> textCls = ForgeReflection.resolveTextComponentClass();
                if (textCls == null) return;
                java.lang.reflect.Method sendMessage = server.getClass().getMethod("sendMessage", textCls);
                sendMessage.invoke(server, text);
                return;
            } catch (Throwable t) { }
        }
        System.out.println("[MinerTrack] [WARN] " + toAnsi(msg));
    }

    @Override
    public String applyColors(String message) {
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

    static String toAnsi(String input) {
        if (input == null) return "";
        if (input.indexOf('§') < 0) return input;
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
                    i++;
                    translated = true;
                    continue;
                }
            }
            out.append(c);
        }
        if (translated) out.append("\u001B[0m");
        return out.toString();
    }

    private static String ansiForCode(char code) {
        switch (code) {
            case '0': return "\u001B[30m";
            case '1': return "\u001B[34m";
            case '2': return "\u001B[32m";
            case '3': return "\u001B[36m";
            case '4': return "\u001B[31m";
            case '5': return "\u001B[35m";
            case '6': return "\u001B[33m";
            case '7': return "\u001B[37m";
            case '8': return "\u001B[90m";
            case '9': return "\u001B[94m";
            case 'a': return "\u001B[92m";
            case 'b': return "\u001B[96m";
            case 'c': return "\u001B[91m";
            case 'd': return "\u001B[95m";
            case 'e': return "\u001B[93m";
            case 'f': return "\u001B[97m";
            case 'l': return "\u001B[1m";
            case 'o': return "\u001B[3m";
            case 'n': return "\u001B[4m";
            case 'm': return "\u001B[9m";
            case 'r': return "\u001B[0m";
            default:  return null;
        }
    }

    @Override
    public void sendConsoleMessage(String message) {
        info(message);
    }

    @Override
    public Object getPlayer(UUID uuid) {
        Object server = ForgeReflection.getServer();
        if (server == null) return null;
        Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
            new Class<?>[0], new Object[0]);
        if (pm == null) return null;
        return ForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{uuid});
    }

    @Override
    public Object getPlugin() {
        // Forge has no plugin concept; return the mod container reference
        try {
            Class<?> modListCls = ForgeReflection.forgeClass("net.minecraftforge.fml.ModList");
            if (modListCls == null) return null;
            java.lang.reflect.Method getMethod = modListCls.getMethod("get");
            Object modList = getMethod.invoke(null);
            java.lang.reflect.Method getContainer = modListCls.getMethod("getModContainerById", String.class);
            return getContainer.invoke(modList, "minertrack");
        } catch (Throwable t) {
            return null;
        }
    }
}
