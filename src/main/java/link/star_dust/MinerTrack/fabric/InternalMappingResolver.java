package link.star_dust.MinerTrack.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
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
 * <p>Once injected, {@link MappingResolver#unmapClassName(String, String)} can be
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

    private final Path cacheFile;
    private final String mcVersion;
    private boolean injected;

    // ── Cached resolution tables (populated lazily) ─────────────────────

    private volatile Map<String, String> mojmapToOfficial;
    private volatile Map<String, String> officialToMojmap;
    private volatile Map<String, Map<String, String>> methodMappings; // className -> (mojmapMethod -> officialMethod)
    private volatile Map<String, Map<String, String>> fieldMappings;   // className -> (mojmapField -> officialField)
    private volatile Map<String, String> superclassMappings;           // mojmapClass -> mojmapSuperclass

    /**
     * @param gameDir  the Minecraft game directory ({@code FabricLoader.getInstance().getGameDir()})
     * @param mcVersion the friendly Minecraft version string (e.g. {@code "1.20.1"})
     */
    public InternalMappingResolver(Path gameDir, String mcVersion) {
        this.cacheFile = gameDir.resolve("cache/minertrack/mojmap_" + mcVersion + ".tiny");
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
     * Fabric Loader's {@link MappingResolver}.
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
        return mojmapToOfficial.getOrDefault(mojmapClassName, mojmapClassName);
    }

    /**
     * Resolve an official (obfuscated) class name to its Mojmap-deobfuscated name.
     *
     * @param officialClassName fully qualified official class name
     * @return the Mojmap class name, or the input if unknown
     */
    public String officialToMojmap(String officialClassName) {
        ensureTablesLoaded();
        return officialToMojmap.getOrDefault(officialClassName, officialClassName);
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
        ensureTablesLoaded();
        Map<String, String> classMethods = methodMappings.get(mojmapClass);
        if (classMethods != null) {
            String official = classMethods.get(mojmapMethod);
            if (official != null) return official;
        }
        // Try superclass chain
        String superclass = superclassMappings.get(mojmapClass);
        if (superclass != null && !"java.lang.Object".equals(superclass)) {
            return resolveMethodName(superclass, mojmapMethod);
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
        // Try superclass chain
        String superclass = superclassMappings.get(mojmapClass);
        if (superclass != null && !"java.lang.Object".equals(superclass)) {
            return resolveFieldName(superclass, mojmapField);
        }
        return mojmapField;
    }

    /**
     * Get the Mojmap superclass name for a given Mojmap class.
     *
     * @param mojmapClass the Mojmap class name
     * @return the Mojmap superclass name, or {@code "java.lang.Object"} if unknown
     */
    public String getSuperclass(String mojmapClass) {
        ensureTablesLoaded();
        return superclassMappings.getOrDefault(mojmapClass, "java.lang.Object");
    }

    // ── Download ────────────────────────────────────────────────────────

    private void downloadMappings() throws IOException {
        LOGGER.info("Downloading Mojmap mappings for Minecraft " + mcVersion);

        // 1. Fetch version manifest
        JsonObject versionManifest = fetchJson(URI.create(VERSION_MANIFEST_URL).toURL());
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
        JsonObject versionMeta = fetchJson(URI.create(versionUrl).toURL());
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
                    new MappingSourceNsSwitch(
                            new Tiny2FileWriter(tinyWriter, true),
                            "official"
                    )
            );
        }

        LOGGER.info("Mojmap mappings cached to " + cacheFile);
    }

    // ── Injection ───────────────────────────────────────────────────────

    private void injectMappings() throws IOException {
        // Use reflection to access Fabric Loader's internal mapping system.
        // FabricLauncherBase.getLauncher().getMappingConfiguration().getMappings()
        // returns a net.fabricmc.loader.impl.lib.mappingio.MappingVisitor which is
        // a DIFFERENT type from the standalone mapping-io library's
        // net.fabricmc.mappingio.MappingVisitor — they share the same interface
        // methods but are loaded by different class loaders / packages.
        //
        // Therefore we must also use the Loader's INTERNAL Tiny2FileReader
        // (net.fabricmc.loader.impl.lib.mappingio.format.tiny.Tiny2FileReader)
        // whose read(Reader, MappingVisitor) signature uses the same internal
        // MappingVisitor type as the mappings object we pass.
        try {
            Class<?> launcherBaseClass = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
            java.lang.reflect.Method getLauncher = launcherBaseClass.getMethod("getLauncher");
            Object launcher = getLauncher.invoke(null);
            Object mappingConfig = launcher.getClass().getMethod("getMappingConfiguration").invoke(launcher);
            Object mappings = mappingConfig.getClass().getMethod("getMappings").invoke(mappingConfig);

            try (Reader reader = new InputStreamReader(
                    new GZIPInputStream(new FileInputStream(cacheFile.toFile())), StandardCharsets.UTF_8)) {
                // Use Fabric Loader's INTERNAL Tiny2FileReader whose
                // read(Reader, MappingVisitor) accepts the Loader's own
                // MappingVisitor type (the same type as 'mappings').
                Class<?> internalTiny2Reader = Class.forName(
                        "net.fabricmc.loader.impl.lib.mappingio.format.tiny.Tiny2FileReader");
                Class<?> internalMappingVisitor = Class.forName(
                        "net.fabricmc.loader.impl.lib.mappingio.MappingVisitor");
                java.lang.reflect.Method readMethod = internalTiny2Reader.getMethod(
                        "read", Reader.class, internalMappingVisitor);
                readMethod.invoke(null, reader, mappings);
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException e) {
            throw new IOException("Failed to inject mappings via Fabric Loader API", e);
        }
    }

    // ── Lazy table loading ──────────────────────────────────────────────

    private void ensureTablesLoaded() {
        if (mojmapToOfficial != null) return;
        synchronized (this) {
            if (mojmapToOfficial != null) return;
            loadTables();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadTables() {
        Map<String, String> c2o = new HashMap<>();
        Map<String, String> o2c = new HashMap<>();
        Map<String, Map<String, String>> methods = new HashMap<>();
        Map<String, Map<String, String>> fields = new HashMap<>();
        Map<String, String> supers = new HashMap<>();

        if (!isCached()) {
            this.mojmapToOfficial = c2o;
            this.officialToMojmap = o2c;
            this.methodMappings = methods;
            this.fieldMappings = fields;
            this.superclassMappings = supers;
            return;
        }

        try (Reader reader = new InputStreamReader(
                new GZIPInputStream(new FileInputStream(cacheFile.toFile())), StandardCharsets.UTF_8)) {
            // Parse the Tiny v2 file manually to build lookup tables.
            // Tiny v2 format:
            //   tiny\t2\t<sourceNs>\t<targetNs>\n
            //   c\t<sourceClass>\t<targetClass>\n
            //   \tm\t<sourceMethod>\t<targetMethod>\n
            //   \tf\t<sourceField>\t<targetField>\n
            java.io.BufferedReader br = new java.io.BufferedReader(reader);
            String header = br.readLine();
            if (header == null || !header.startsWith("tiny\t2")) {
                LOGGER.warning("Invalid Tiny v2 header in " + cacheFile);
                return;
            }
            String[] headerParts = header.split("\t");
            String sourceNs = headerParts[2]; // "official"
            String targetNs = headerParts[3]; // "mojmap"

            String line;
            String currentOfficialClass = null;
            String currentMojmapClass = null;

            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;

                if (line.startsWith("c\t")) {
                    // Class entry: c\t<official>\t<mojmap>
                    String[] parts = line.split("\t");
                    if (parts.length >= 3) {
                        currentOfficialClass = parts[1];
                        currentMojmapClass = parts[2];
                        c2o.put(currentMojmapClass, currentOfficialClass);
                        o2c.put(currentOfficialClass, currentMojmapClass);
                    }
                } else if (line.startsWith("\tm\t") && currentMojmapClass != null) {
                    // Method entry: \tm\t<descriptor>\t<officialMethod>\t<mojmapMethod>
                    // The line starts with a tab, so parts[0]="", parts[1]="m",
                    // parts[2]=descriptor (e.g. "(Lnet/minecraft/...;)V"),
                    // parts[3]=officialMethod, parts[4]=mojmapMethod.
                    String[] parts = line.split("\t");
                    if (parts.length >= 5) {
                        String officialMethod = parts[3];
                        String mojmapMethod = parts[4];
                        methods.computeIfAbsent(currentMojmapClass, k -> new HashMap<>())
                                .put(mojmapMethod, officialMethod);
                    }
                } else if (line.startsWith("\tf\t") && currentMojmapClass != null) {
                    // Field entry: \tf\t<officialField>\t<mojmapField>
                    // The line starts with a tab, so parts[0]="", parts[1]="f",
                    // parts[2]=officialField, parts[3]=mojmapField.
                    String[] parts = line.split("\t");
                    if (parts.length >= 4) {
                        String officialField = parts[2];
                        String mojmapField = parts[3];
                        fields.computeIfAbsent(currentMojmapClass, k -> new HashMap<>())
                                .put(mojmapField, officialField);
                    }
                }
            }

            // Build superclass map from the class hierarchy.
            // In ProGuard/Tiny, superclass info is not directly encoded.
            // We infer it from the class name patterns (inner classes, etc.)
            // and from the official→mojmap mapping.
            for (Map.Entry<String, String> entry : c2o.entrySet()) {
                String mojmapCls = entry.getKey();
                String officialCls = entry.getValue();
                // Derive superclass from the package hierarchy
                String superMojmap = deriveSuperclass(mojmapCls, c2o);
                if (superMojmap != null) {
                    supers.put(mojmapCls, superMojmap);
                }
            }

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load mapping tables from " + cacheFile, e);
        }

        this.mojmapToOfficial = c2o;
        this.officialToMojmap = o2c;
        this.methodMappings = methods;
        this.fieldMappings = fields;
        this.superclassMappings = supers;
    }

    /**
     * Derive the Mojmap superclass name for a given Mojmap class.
     * Uses the convention that {@code net.minecraft.class_*} names map to
     * {@code net.minecraft.} prefixed Mojmap names, and inner classes are
     * separated by {@code $}.
     */
    private static String deriveSuperclass(String mojmapClass, Map<String, String> c2o) {
        // For net.minecraft.server.network.ServerPlayerEntity, the superclass
        // chain would be something like:
        //   ServerPlayerEntity → PlayerEntity → LivingEntity → Entity → ...
        // We can't know this from the mapping file alone, so we return null
        // and let the caller fall back to Java reflection for superclass traversal.
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static JsonObject fetchJson(URL url) throws IOException {
        try (var is = url.openStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(content).getAsJsonObject();
        }
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