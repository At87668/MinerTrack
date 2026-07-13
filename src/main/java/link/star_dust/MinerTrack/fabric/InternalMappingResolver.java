package link.star_dust.MinerTrack.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Resolves Mojang's official mappings (ProGuard format) into a cached Tiny v2 file
 * and injects them into Fabric Loader's mapping system under the {@code "mojmap"}
 * namespace.
 *
 * <p>Once injected, {@link net.fabricmc.loader.api.MappingResolver#unmapClassName(String, String)} can be
 * called with the {@code "mojmap"} namespace to translate any Mojmap-deobfuscated
 * class name into its runtime (obfuscated) equivalent. This allows reflection code
 * that uses Mojmap names (e.g. {@code net.minecraft.server.network.ServerPlayerEntity})
 * to work on obfuscated production servers for Minecraft 1.18–1.21.x.
 *
 * <p>For Minecraft 26.x and later the server jar ships unobfuscated, so this resolver
 * is completely bypassed — no download or injection occurs.
 */
public final class InternalMappingResolver {
    private static final Logger LOGGER = Logger.getLogger("MinerTrack/MappingResolver");
    private static final String VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final int MAX_RETRIES = 3;

    private final Path cacheFile;
    private final String mcVersion;
    private boolean injected;

    // ── Cached resolution tables (populated lazily) ─────────────────────

    private volatile Map<String, String> mojmapToOfficialClasses;
    private volatile Map<String, String> officialToMojmapClasses;
    
    // Maps: Mojmap Class -> (Mojmap Method + Descriptor -> Official Method)
    private volatile Map<String, Map<String, String>> methodMappings;
    // Maps: Mojmap Class -> (Mojmap Field -> Official Field)
    private volatile Map<String, Map<String, String>> fieldMappings;

    /**
     * @param gameDir  the Minecraft game directory ({@code FabricLoader.getInstance().getGameDir()})
     * @param mcVersion the friendly Minecraft version string (e.g. {@code "1.20.1"})
     */
    public InternalMappingResolver(Path gameDir, String mcVersion) {
        this.cacheFile = gameDir.resolve("cache/minertrack/mojmap_" + mcVersion + ".tiny.gz");
        this.mcVersion = mcVersion;
        this.injected = false;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the cached mapping file exists on disk (regardless
     * of whether it has been injected into Fabric's mapping system yet).
     */
    public boolean isCached() {
        return Files.exists(cacheFile);
    }

    /**
     * Returns {@code true} if the mappings have been successfully injected into
     * Fabric Loader's {@link net.fabricmc.loader.api.MappingResolver}.
     */
    public boolean isInjected() {
        return injected;
    }

    /**
     * Ensures the Mojmap mappings are downloaded (if not already cached) and
     * injected into Fabric Loader's mapping system. Safe to call multiple times.
     *
     * @throws IOException if download or file I/O fails
     */
    public void loadAndInject() throws IOException {
        if (injected) return;

        if (!isCached()) {
            downloadMappings();
        }

        injectMappings();
        injected = true;
        LOGGER.info("Mojmap mappings injected for Minecraft " + mcVersion);
    }

    /**
     * Resolve a Mojmap-deobfuscated class name to its official (obfuscated) name.
     *
     * @param mojmapClassName fully qualified Mojmap class name
     * @return the official (obfuscated) class name, or the input if unknown
     */
    public String mojmapToOfficial(String mojmapClassName) {
        ensureTablesLoaded();
        return mojmapToOfficialClasses.getOrDefault(mojmapClassName, mojmapClassName);
    }

    /**
     * Resolve an official (obfuscated) class name to its Mojmap-deobfuscated name.
     *
     * @param officialClassName fully qualified official class name
     * @return the Mojmap class name, or the input if unknown
     */
    public String officialToMojmap(String officialClassName) {
        ensureTablesLoaded();
        return officialToMojmapClasses.getOrDefault(officialClassName, officialClassName);
    }

    /**
     * Resolve a Mojmap method name to its official (obfuscated) name within the
     * given Mojmap class.
     *
     * @param mojmapClass  the Mojmap class name containing the method
     * @param mojmapMethod the Mojmap method name
     * @return the official method name, or the input if unknown
     */
    public String resolveMethodName(String mojmapClass, String mojmapMethod) {
        return resolveMethodName(mojmapClass, mojmapMethod, null);
    }

    /**
     * Resolve a Mojmap method name to its official (obfuscated) name within the
     * given Mojmap class, using an optional descriptor for precise matching.
     *
     * <p><b>Note:</b> The {@code descriptor} must use Mojmap namespace types
     * (e.g. {@code Lnet/minecraft/server/MinecraftServer;}) to match the keys
     * stored in the internal tables.</p>
     *
     * @param mojmapClass  the Mojmap class name containing the method
     * @param mojmapMethod the Mojmap method name
     * @param descriptor   the JVM method descriptor in Mojmap namespace,
     *                     or {@code null} for name-only lookup
     * @return the official method name, or the input if unknown
     */
    public String resolveMethodName(String mojmapClass, String mojmapMethod, String descriptor) {
        ensureTablesLoaded();
        Map<String, String> classMethods = methodMappings.get(mojmapClass);
        if (classMethods != null) {
            // Try descriptor-qualified lookup first
            if (descriptor != null) {
                String qualified = classMethods.get(mojmapMethod + descriptor);
                if (qualified != null) return qualified;
            }
            // Fall back to name-only lookup
            String official = classMethods.get(mojmapMethod);
            if (official != null) return official;
        }
        return mojmapMethod;
    }

    /**
     * Resolve a Mojmap field name to its official (obfuscated) name within the
     * given Mojmap class.
     *
     * @param mojmapClass the Mojmap class name containing the field
     * @param mojmapField the Mojmap field name
     * @return the official field name, or the input if unknown
     */
    public String resolveFieldName(String mojmapClass, String mojmapField) {
        ensureTablesLoaded();
        Map<String, String> classFields = fieldMappings.get(mojmapClass);
        if (classFields != null) {
            String official = classFields.get(mojmapField);
            if (official != null) return official;
        }
        return mojmapField;
    }

    /**
     * Get the Mojmap superclass name for a given Mojmap class.
     *
     * <p>Note: Tiny v2 files generated from ProGuard mappings do not explicitly
     * store inheritance hierarchies. This method currently returns {@code null}
     * to signal that callers should use Java reflection ({@link Class#getSuperclass()})
     * to traverse the hierarchy at runtime.</p>
     *
     * @param mojmapClass the Mojmap class name
     * @return {@code null} (inheritance must be resolved via runtime reflection)
     */
    public String getSuperclass(String mojmapClass) {
        // Inheritance is not reliably available in ProGuard->Tiny conversion.
        // Callers should use Class.getSuperclass() and map the result back.
        return null;
    }

    // ── Download ────────────────────────────────────────────────────────

    private void downloadMappings() throws IOException {
        LOGGER.info("Downloading Mojmap mappings for Minecraft " + mcVersion);

        // 1. Fetch version manifest with retry
        JsonObject versionManifest = fetchJsonWithRetry(URI.create(VERSION_MANIFEST_URL).toURL());
        String versionUrl = null;
        for (var entry : versionManifest.get("versions").getAsJsonArray()) {
            JsonObject ver = entry.getAsJsonObject();
            if (mcVersion.equals(ver.get("id").getAsString())) {
                versionUrl = ver.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("Minecraft version " + mcVersion + " not found in version manifest");
        }

        // 2. Fetch version metadata
        JsonObject versionMeta = fetchJsonWithRetry(URI.create(versionUrl).toURL());
        JsonObject downloads = versionMeta.get("downloads").getAsJsonObject();

        // Try server mappings first, fall back to client mappings
        String mappingsUrl = null;
        if (downloads.has("server_mappings")) {
            mappingsUrl = downloads.get("server_mappings").getAsJsonObject().get("url").getAsString();
        } else if (downloads.has("client_mappings")) {
            mappingsUrl = downloads.get("client_mappings").getAsJsonObject().get("url").getAsString();
        }
        if (mappingsUrl == null) {
            throw new IOException("No mappings available for version " + mcVersion);
        }

        // 3. Download ProGuard mappings and convert to Tiny v2
        Files.createDirectories(cacheFile.getParent());
        try (Reader proguardReader = new InputStreamReader(
                URI.create(mappingsUrl).toURL().openStream(), StandardCharsets.UTF_8);
             Writer tinyWriter = new OutputStreamWriter(
                     new GZIPOutputStream(new FileOutputStream(cacheFile.toFile())), StandardCharsets.UTF_8)) {

            ProGuardFileReader.read(
                    proguardReader,
                    "mojmap",
                    "official",
                    new Tiny2FileWriter(tinyWriter, true)
            );
        }

        LOGGER.info("Mojmap mappings cached to " + cacheFile);
    }

    // ── Injection ───────────────────────────────────────────────────────

    private void injectMappings() throws IOException {
        try {
            Class<?> launcherBaseClass = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
            java.lang.reflect.Method getLauncher = launcherBaseClass.getMethod("getLauncher");
            Object launcher = getLauncher.invoke(null);
            Object mappingConfig = launcher.getClass().getMethod("getMappingConfiguration").invoke(launcher);
            Object mappings = mappingConfig.getClass().getMethod("getMappings").invoke(mappingConfig);

            try (Reader reader = new InputStreamReader(
                    new GZIPInputStream(new FileInputStream(cacheFile.toFile())), StandardCharsets.UTF_8)) {
                // Use Fabric Loader's INTERNAL Tiny2FileReader
                Class<?> internalTiny2Reader = Class.forName(
                        "net.fabricmc.loader.impl.lib.mappingio.format.tiny.Tiny2FileReader");
                Class<?> internalMappingVisitor = Class.forName(
                        "net.fabricmc.loader.impl.lib.mappingio.MappingVisitor");
                java.lang.reflect.Method readMethod = internalTiny2Reader.getMethod(
                        "read", Reader.class, internalMappingVisitor);
                readMethod.invoke(null, reader, mappings);
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("Fabric Loader internal classes not found. " +
                    "This may indicate an incompatible Fabric Loader version.", e);
        } catch (NoSuchMethodException e) {
            throw new IOException("Fabric Loader internal API mismatch.", e);
        } catch (IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            throw new IOException("Failed to inject mappings via Fabric Loader API", e);
        }
    }

    // ── Lazy table loading ──────────────────────────────────────────────

    private void ensureTablesLoaded() {
        if (mojmapToOfficialClasses != null) return;
        synchronized (this) {
            if (mojmapToOfficialClasses != null) return;
            loadTables();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadTables() {
        Map<String, String> c2o = new HashMap<>();
        Map<String, String> o2c = new HashMap<>();
        Map<String, Map<String, String>> methods = new HashMap<>();
        Map<String, Map<String, String>> fields = new HashMap<>();

        if (!isCached()) {
            this.mojmapToOfficialClasses = c2o;
            this.officialToMojmapClasses = o2c;
            this.methodMappings = methods;
            this.fieldMappings = fields;
            return;
        }

        try (Reader reader = new InputStreamReader(
                new GZIPInputStream(new FileInputStream(cacheFile.toFile())), StandardCharsets.UTF_8)) {
            
            java.io.BufferedReader br = new java.io.BufferedReader(reader);
            String header = br.readLine();
            if (header == null || !header.startsWith("tiny\t2")) {
                LOGGER.warning("Invalid Tiny v2 header in " + cacheFile);
                return;
            }
            
            String[] headerParts = header.split("\t");
            // Expected: tiny 2 mojmap official
            if (headerParts.length < 4 || !"mojmap".equals(headerParts[2]) || !"official".equals(headerParts[3])) {
                LOGGER.warning("Unexpected namespace order in Tiny file. Expected mojmap->official.");
                return;
            }

            String line;
            String currentMojmapClass = null;

            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t");
                
                if (line.startsWith("c\t")) {
                    // Class entry: c\t<mojmap>\t<official>
                    if (parts.length >= 3) {
                        currentMojmapClass = parts[1];
                        String officialClass = parts[2];
                        c2o.put(currentMojmapClass, officialClass);
                        o2c.put(officialClass, currentMojmapClass);
                    }
                } else if (line.startsWith("\tm\t") && currentMojmapClass != null) {
                    // Method entry: \tm\t<descriptor>\t<mojmapMethod>\t<officialMethod>
                    if (parts.length >= 5) {
                        String descriptor = parts[2]; // Contains Mojmap types
                        String mojmapMethod = parts[3];
                        String officialMethod = parts[4];
                        
                        // Store with descriptor-qualified key for precise lookup
                        methods.computeIfAbsent(currentMojmapClass, k -> new HashMap<>())
                                .put(mojmapMethod + descriptor, officialMethod);
                        // Also store without descriptor as fallback
                        methods.get(currentMojmapClass).putIfAbsent(mojmapMethod, officialMethod);
                    }
                } else if (line.startsWith("\tf\t") && currentMojmapClass != null) {
                    // Field entry: \tf\t<mojmapField>\t<officialField>
                    if (parts.length >= 4) {
                        String mojmapField = parts[2];
                        String officialField = parts[3];
                        fields.computeIfAbsent(currentMojmapClass, k -> new HashMap<>())
                                .put(mojmapField, officialField);
                    }
                }
            }

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load mapping tables from " + cacheFile, e);
        }

        this.mojmapToOfficialClasses = c2o;
        this.officialToMojmapClasses = o2c;
        this.methodMappings = methods;
        this.fieldMappings = fields;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

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
                try { Thread.sleep(1000 * (i + 1)); } catch (InterruptedException ignored) {}
            }
        }
        throw lastException;
    }

    /**
     * Check whether the given Minecraft version string represents a version
     * that requires Mojmap mapping resolution (1.18–1.21.x).
     */
    public static boolean isMojmapRequired(String version) {
        if (version == null || version.isEmpty()) return false;
        // Old scheme: 1.x.y
        if (version.startsWith("1.")) {
            String[] parts = version.split("\\.");
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
        // New scheme: year.minor.patch (e.g. 26.1.2)
        String[] parts = version.split("\\.");
        if (parts.length >= 1) {
            try {
                int major = Integer.parseInt(parts[0]);
                return major < 26; // 26+ ships unobfuscated
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Get the friendly Minecraft version string from Fabric Loader's mod list.
     */
    public static String getMinecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .orElseThrow(() -> new NoSuchElementException(
                        "Could not find Minecraft mod container"))
                .getMetadata()
                .getVersion()
                .getFriendlyString();
    }
}