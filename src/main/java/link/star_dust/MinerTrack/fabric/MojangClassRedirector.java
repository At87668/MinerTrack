package link.star_dust.MinerTrack.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Direct Mojang class name redirection without relying on Fabric Loader's
 * mojmap namespace injection.
 *
 * <p>Fabric Loader's mojmap namespace injection via {@link InternalMappingResolver}
 * is fragile and often fails on production (intermediary) servers. This class
 * takes a different approach:
 * <ol>
 *   <li>Download Mojang's ProGuard mappings for the current Minecraft version</li>
 *   <li>Parse them into a simple {@code mojang_name → official_name} class map</li>
 *   <li>At reflection time, resolve {@code mojang → official → intermediary}
 *       using Fabric's native {@code official → intermediary} resolver (which
 *       ALWAYS works, since it's how Fabric Loader operates internally)</li>
 * </ol>
 *
 * <p>This completely bypasses the fragile {@code mojmap} namespace injection
 * and works reliably on all Fabric servers from 1.18 to 1.21.x.</p>
 *
 * <p>For Minecraft 26.x+, the server jar ships unobfuscated so no redirection
 * is needed — all mojang class names resolve directly.</p>
 */
public final class MojangClassRedirector {
    private static final Logger LOGGER = Logger.getLogger("MinerTrack/ClassRedirector");
    private static final String VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final int MAX_RETRIES = 3;

    private final Path cacheFile;
    private final String mcVersion;

    /** Mojang FQN (dots) → Official FQN (dots) */
    private volatile Map<String, String> mojangToOfficial;

    /**
     * Cache of already-resolved class names so we don't do the two-step
     * resolution repeatedly for the same classes.
     */
    private final Map<String, String> resolutionCache = new ConcurrentHashMap<>();

    /**
     * @param gameDir   the Minecraft game directory
     * @param mcVersion the friendly Minecraft version string (e.g. "1.20.1")
     */
    public MojangClassRedirector(Path gameDir, String mcVersion) {
        this.cacheFile = gameDir.resolve("cache/minertrack/mojmap_classmap_" + mcVersion + ".txt");
        this.mcVersion = mcVersion;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the cached class map file exists on disk.
     */
    public boolean isCached() {
        return Files.exists(cacheFile);
    }

    /**
     * Ensures the class map is available. Downloads ProGuard mappings if not
     * already cached, parses them, and builds the internal redirect table.
     * Safe to call multiple times.
     *
     * @throws IOException if download or file I/O fails
     */
    public synchronized void load() throws IOException {
        if (mojangToOfficial != null) return;

        if (!isCached()) {
            downloadAndParse();
        } else {
            loadFromCache();
        }
    }

    /**
     * Redirect a Mojang class name to the runtime class name.
     *
     * <p>Resolution chain: {@code mojang → official → intermediary/named}
     *
     * <p>Steps:
     * <ol>
     *   <li>Look up the Mojang FQN in our ProGuard-derived table → official FQN</li>
     *   <li>Use Fabric's native MappingResolver to map {@code official → intermediary}
     *       (or {@code official → named} in dev)</li>
     *   <li>Return the resolved runtime class name</li>
     * </ol>
     *
     * <p>If the class is not obfuscated (maps to itself in ProGuard), returns
     * the Mojang name directly.</p>
     *
     * @param mojangClassName fully qualified Mojang class name (dots)
     * @return the runtime (intermediary/named) class name, or the original if
     *         no mapping is available
     */
    public String redirectClass(String mojangClassName) {
        if (mojangClassName == null) return null;

        // Check cache first — most classes are looked up multiple times
        String cached = resolutionCache.get(mojangClassName);
        if (cached != null) return cached;

        // Ensure tables are loaded
        ensureLoaded();

        // Step 1: mojang → official (from our ProGuard-derived table)
        String official = mojangToOfficial.get(mojangClassName);

        if (official == null) {
            // Class not in ProGuard mappings — might be a non-Minecraft class
            // or a Fabric API class. Return as-is.
            resolutionCache.put(mojangClassName, mojangClassName);
            return mojangClassName;
        }

        // If official name equals mojang name, the class is not obfuscated
        // (e.g. MinecraftServer is not obfuscated in most versions).
        // We still return it directly.
        if (official.equals(mojangClassName)) {
            resolutionCache.put(mojangClassName, mojangClassName);
            return mojangClassName;
        }

        // Step 2: official → intermediary/named (via Fabric's native resolver)
        // This ALWAYS works because Fabric Loader ships with official↔intermediary
        // mappings baked in. No injection needed.
        try {
            MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();

            // Fabric's resolver maps from the "official" namespace
            String runtime = resolver.unmapClassName("official", official);

            if (runtime != null && !runtime.equals(official)) {
                resolutionCache.put(mojangClassName, runtime);
                return runtime;
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "official→runtime resolution failed for "
                    + official + ": " + t.getMessage());
        }

        // If official→runtime resolution failed (e.g., in dev environment where
        // runtime classes use named/mojang names), try the official name directly.
        resolutionCache.put(mojangClassName, official);
        return official;
    }

    /**
     * Check whether the given Minecraft version requires class redirection.
     * Minecraft 26.x+ ships unobfuscated so no redirection is needed.
     */
    public static boolean isRedirectRequired(String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) return false;
        // Old scheme: 1.x.y — need redirect for 1.18-1.21
        if (mcVersion.startsWith("1.")) {
            String[] parts = mcVersion.split("\\.");
            if (parts.length >= 2) {
                try {
                    int minor = Integer.parseInt(parts[1]);
                    return minor >= 18 && minor <= 21;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return false;
        }
        // New scheme: year.minor.patch — 26+ ships unobfuscated
        String[] parts = mcVersion.split("\\.");
        if (parts.length >= 1) {
            try {
                int major = Integer.parseInt(parts[0]);
                return major < 26;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Get the friendly Minecraft version string.
     */
    public static String getMinecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "Could not find Minecraft mod container"))
                .getMetadata()
                .getVersion()
                .getFriendlyString();
    }

    // ── Download & Parse ────────────────────────────────────────────────

    private void downloadAndParse() throws IOException {
        LOGGER.info("Downloading Mojang ProGuard mappings for Minecraft " + mcVersion);

        // 1. Fetch version manifest
        JsonObject versionManifest = fetchJsonWithRetry(
                URI.create(VERSION_MANIFEST_URL).toURL());

        String versionUrl = null;
        for (var entry : versionManifest.get("versions").getAsJsonArray()) {
            JsonObject ver = entry.getAsJsonObject();
            if (mcVersion.equals(ver.get("id").getAsString())) {
                versionUrl = ver.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("Minecraft version " + mcVersion
                    + " not found in version manifest");
        }

        // 2. Fetch version metadata
        JsonObject versionMeta = fetchJsonWithRetry(
                URI.create(versionUrl).toURL());
        JsonObject downloads = versionMeta.get("downloads").getAsJsonObject();

        // Try server mappings first, fall back to client mappings
        String mappingsUrl = null;
        if (downloads.has("server_mappings")) {
            mappingsUrl = downloads.get("server_mappings").getAsJsonObject()
                    .get("url").getAsString();
        } else if (downloads.has("client_mappings")) {
            mappingsUrl = downloads.get("client_mappings").getAsJsonObject()
                    .get("url").getAsString();
        }
        if (mappingsUrl == null) {
            throw new IOException("No mappings available for version " + mcVersion);
        }

        // 3. Download ProGuard mappings and parse into our class map
        Map<String, String> classMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                URI.create(mappingsUrl).toURL().openStream(), StandardCharsets.UTF_8))) {

            String line;
            String currentMojangPackage = null; // e.g., "net/minecraft/server"
            String currentMojangClass = null;   // e.g., "MinecraftServer"
            String currentOfficialClass = null;  // e.g., "MinecraftServer" or "abc"

            while ((line = reader.readLine()) != null) {
                // Skip comments and empty lines
                if (line.startsWith("#") || line.trim().isEmpty()) continue;

                // Class mapping: net.minecraft.server.MinecraftServer -> net.minecraft.server.MinecraftServer:
                // or: net.minecraft.commands.CommandSourceStack -> dm:
                // Note: indented (non-class) lines start with spaces for fields/methods
                if (!line.startsWith(" ")) {
                    // This is a class mapping line
                    // Flush previous class if any
                    if (currentMojangClass != null && currentOfficialClass != null) {
                        String fqn = rebuildFqn(currentMojangPackage, currentMojangClass);
                        String officialFqn = rebuildFqn(currentMojangPackage, currentOfficialClass);
                        classMap.put(fqn, officialFqn);
                    }

                    // Parse: "pkg1.pkg2.ClassName -> officialName:"
                    int arrowIdx = line.indexOf(" -> ");
                    if (arrowIdx > 0) {
                        String mojangFull = line.substring(0, arrowIdx).trim();
                        String officialPart = line.substring(arrowIdx + 4).trim();
                        // Remove trailing colon
                        if (officialPart.endsWith(":")) {
                            officialPart = officialPart.substring(0, officialPart.length() - 1);
                        }

                        // Extract package and class name
                        int lastDot = mojangFull.lastIndexOf('.');
                        if (lastDot > 0) {
                            currentMojangPackage = mojangFull.substring(0, lastDot);
                            currentMojangClass = mojangFull.substring(lastDot + 1);
                        } else {
                            currentMojangPackage = "";
                            currentMojangClass = mojangFull;
                        }

                        // Official name may or may not include package
                        int officialLastDot = officialPart.lastIndexOf('.');
                        if (officialLastDot > 0) {
                            currentOfficialClass = officialPart.substring(officialLastDot + 1);
                            // Official package might differ — use it if present
                            // But typically ProGuard keeps the same package
                        } else {
                            currentOfficialClass = officialPart;
                        }
                    }
                }
                // Method/field lines start with spaces — skip them, we only need classes
            }

            // Flush last class
            if (currentMojangClass != null && currentOfficialClass != null) {
                String fqn = rebuildFqn(currentMojangPackage, currentMojangClass);
                String officialFqn = rebuildFqn(currentMojangPackage, currentOfficialClass);
                classMap.put(fqn, officialFqn);
            }
        }

        LOGGER.info("Parsed " + classMap.size() + " class mappings for Minecraft " + mcVersion);

        // Save to cache
        Files.createDirectories(cacheFile.getParent());
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(cacheFile.toFile()), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String> entry : classMap.entrySet()) {
                writer.write(entry.getKey());
                writer.write('\t');
                writer.write(entry.getValue());
                writer.newLine();
            }
        }

        this.mojangToOfficial = classMap;
        LOGGER.info("Class map cached to " + cacheFile);
    }

    private void loadFromCache() throws IOException {
        Map<String, String> classMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(cacheFile.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tabIdx = line.indexOf('\t');
                if (tabIdx > 0) {
                    String mojang = line.substring(0, tabIdx);
                    String official = line.substring(tabIdx + 1);
                    classMap.put(mojang, official);
                }
            }
        }

        LOGGER.info("Loaded " + classMap.size() + " class mappings from cache");
        this.mojangToOfficial = classMap;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void ensureLoaded() {
        if (mojangToOfficial != null) return;
        synchronized (this) {
            if (mojangToOfficial != null) return;
            try {
                load();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to load class mappings: "
                        + e.getMessage());
                // Set empty map so we don't retry endlessly
                mojangToOfficial = new HashMap<>();
            }
        }
    }

    private static String rebuildFqn(String pkg, String className) {
        if (pkg == null || pkg.isEmpty()) return className;
        return pkg.replace('/', '.') + "." + className;
    }

    private static JsonObject fetchJsonWithRetry(URL url) throws IOException {
        IOException lastException = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                try (var is = url.openStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    return JsonParser.parseString(content).getAsJsonObject();
                }
            } catch (IOException e) {
                lastException = e;
                if (i < MAX_RETRIES - 1) {
                    try { Thread.sleep(1000 * (i + 1)); }
                    catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastException;
    }
}
