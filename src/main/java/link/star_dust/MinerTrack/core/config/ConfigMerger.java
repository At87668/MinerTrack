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

package link.star_dust.MinerTrack.core.config;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors v1 ConfigManager.mergeConfigurations() behavior:
 * - Whitelisted keys are recursively merged (nested sections)
 * - All other missing keys are filled from JAR defaults
 * - Result is saved back to the user's file
 *
 * This class is platform-agnostic; YAML I/O is delegated to the
 * supplied {@link YamlLoader}.
 */
public class ConfigMerger {

    /**
     * Keys whose nested structure should be recursively merged rather than replaced.
     * Matches v1's whitelist in ConfigManager.mergeConfigurations().
     */
    private static final Set<String> WHITELIST_KEYS = Set.of(
        "check_update",
        "check_update_channel",
        "kick_strike_lightning",
        "log_file",
        "delete_time",
        "disable_bypass_permission",
        "DiscordWebHook",
        "DiscordWebHook.enable",
        "DiscordWebHook.WebHookURL",
        "DiscordWebHook.vl-required",
        "DiscordWebHook.vl-add-message",
        "DiscordWebHook.vl-add-message.color",
        "DiscordWebHook.vl-add-message.title",
        "DiscordWebHook.vl-add-message.text",
        "xray",
        "xray.enable",
        "xray.worlds",
        // Note: per-world settings (enable, max-height, rare-ores, …) live
        // in the per-group Configuration/<group>.yml files, not in the
        // main config. The xray.worlds section only maps dimension ids
        // (e.g. "minecraft:overworld", "minecraft:the_nether") to group
        // file names.
        "xray.rare-ores",
        "xray.max_path_length",
        "xray.trace_remove",
        "xray.max_vein_distance",
        "xray.veinCountThreshold",
        "xray.path-detection",
        "xray.path-detection.turn-count-threshold",
        "xray.path-detection.branch-count-threshold",
        "xray.path-detection.y-change-threshold",
        "xray.path-detection.y-change-threshold-add-required",
        "xray.natural-detection",
        "xray.natural-detection.enable",
        "xray.natural-detection.cave",
        "xray.natural-detection.cave.air-threshold",
        "xray.natural-detection.cave.CaveAirMultiplier",
        "xray.natural-detection.cave.detection-range",
        "xray.natural-detection.cave.check_skip_vl",
        "xray.natural-detection.cave.artificial-air-remove-time",
        "xray.natural-detection.cave.ignore-artificial-air",
        "xray.natural-detection.sea",
        "xray.natural-detection.sea.check-running-water",
        "xray.natural-detection.sea.water-threshold",
        "xray.natural-detection.sea.detection-range",
        "xray.natural-detection.sea.check_skip_vl",
        "xray.natural-detection.lava-sea",
        "xray.natural-detection.lava-sea.lava-threshold",
        "xray.natural-detection.lava-sea.detection-range",
        "xray.natural-detection.lava-sea.check_skip_vl",
        "xray.small_vein_detection_size",
        "xray.decay",
        "xray.decay.interval",
        "xray.decay.amount",
        "xray.decay.use_factor",
        "xray.decay.factor"
    );

    /**
     * Load user's config file, merge with JAR defaults (whitelist-based recursive merge),
     * then save the merged result back to disk.
     *
     * @param userFile     the user's config file on disk
     * @param resourcePath the path inside the JAR (e.g. "config.yml")
     * @param adapter      the plugin adapter to access getResource()
     * @param loader       the platform-specific YAML loader
     * @return the merged CommonYaml
     */
    public static CommonYaml loadAndMerge(File userFile, String resourcePath, PluginAdapter adapter, YamlLoader loader) {
        // Create from JAR if the file doesn't exist yet
        if (!userFile.exists()) {
            adapter.saveResource(resourcePath, false);
            adapter.info("[Merger] Created " + userFile.getName() + " from default");
        }
        CommonYaml defaultsConfig;
        try (InputStream defaultStream = adapter.getResource(resourcePath)) {
            if (defaultStream != null) {
                defaultsConfig = loader.loadStream(defaultStream);
            } else {
                defaultsConfig = new link.star_dust.MinerTrack.common.MapBackedYaml(new java.util.LinkedHashMap<>());
            }
        } catch (Exception e) {
            defaultsConfig = new link.star_dust.MinerTrack.common.MapBackedYaml(new java.util.LinkedHashMap<>());
        }

        CommonYaml userConfig = loader.loadFile(userFile);

        // Version check: if the user's file is missing _config-version or
        // has an older version than the JAR default, bump the user's
        // _config-version up to the latest. We do NOT replace the entire
        // file with the JAR default (that would destroy any custom keys
        // the user added, e.g. an extra entry in `xray.worlds` like
        // `dim7`). The subsequent merge step is responsible for filling
        // in any keys that are missing relative to the JAR default, and
        // it preserves the user's existing customisations.
        //
        // A timestamped backup is still produced before the bump, so
        // admins can recover the previous layout if they ever need to.
        int newConfigVersion = -1;
        if (needsUpgrade(userConfig, defaultsConfig)) {
            int currentVersion = userConfig.getInt("_config-version", 0);
            int defaultVersion = defaultsConfig.getInt("_config-version", 0);
            adapter.info("Config " + userFile.getName() + " version " + currentVersion
                    + " is outdated (latest: " + defaultVersion + "). Bumping in place; "
                    + "the merge step will add any keys added since the user's version.");
            String stamp = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            File backupFile = new File(userFile.getParentFile(),
                    stripExtension(userFile.getName()) + "-" + stamp + ".yml.bak");
            try {
                java.nio.file.Files.copy(userFile.toPath(), backupFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                adapter.info("Backed up " + userFile.getName() + " to " + backupFile.getName());
            } catch (Exception e) {
                adapter.info("Failed to backup " + userFile.getName() + ": " + e.getMessage());
            }
            // Bump the version in the in-memory copy. The version is
            // persisted to disk by appendMissingDefaults below, which
            // updates the _config-version line in place and preserves
            // every comment, so the bump survives a restart.
            userConfig.set("_config-version", defaultVersion);
            newConfigVersion = defaultVersion;
        }

        // Persist missing keys (and any version bump) to disk WITHOUT
        // rewriting the file. This must run BEFORE mergeConfigurations
        // mutates userConfig in memory, because appendMissingDefaults
        // uses userConfig.contains() to decide what is missing.
        int appended = 0;
        try {
            appended = appendMissingDefaults(userFile, userConfig, defaultsConfig, "", newConfigVersion);
        } catch (Exception e) {
            adapter.info("Could not append missing keys to " + userFile.getName() + ": " + e.getMessage());
        }
        if (appended > 0) {
            adapter.info("[Merger] " + userFile.getName() + ": appended " + appended
                    + " missing key(s) from defaults (comments preserved).");
        }

        int added = mergeConfigurations(userConfig, defaultsConfig, "");
        if (added > 0) {
            adapter.info("[Merger] " + userFile.getName() + ": filled " + added
                    + " missing key(s) in memory.");
        }

        // Reload from disk so the returned config reflects the
        // just-saved state. SnakeYAML's normaliser may shuffle
        // some scalar types (e.g. a `Boolean` flag stored via
        // a parent `set` path round-tripping through the
        // serializer comes back as the same `Boolean`), but
        // with the in-place merger the in-memory tree is
        // already correct, so this reload is a no-op in
        // practice; we keep it for parity with the v1
        // round-trip and so any future v2 caller that wanted
        // to inspect the on-disk state after the save sees a
        // fresh, normalised view.
        try {
            userConfig = loader.loadFile(userFile);
        } catch (Exception e) {
            adapter.info("Failed to reload " + userFile.getName()
                    + " after merge; using the in-memory copy instead: " + e.getMessage());
        }

        return userConfig;
    }

    /**
     * Recursive whitelist-based merge. Mirrors the v1 legacy
     * {@code ConfigManager.mergeConfigurations} pattern.
     *
     * <p>For each whitelisted key in the default config:
     *   - if the user config is missing the key, {@code set} the
     *     default leaf value (Bukkit / Map mutations both work
     *     for primitive leaves);
     *   - if both sides have a section for the key, recurse via
     *     {@link CommonYaml#getChild(String)} — which on the
     *     Bukkit path returns a wrapper around the LIVE nested
     *     {@code ConfigurationSection} (so the recursion
     *     mutates the section in place), and on platforms that
     *     don't override {@code getChild} falls back to a Map
     *     view that works for read-only inspection.
     *
     * <p>No {@code set(key, Map)} is ever called to replace an
     * existing section. The recursion mutates the live section
     * (Bukkit) or the live Map (other platforms) in place, and
     * the resulting in-memory state is what {@code save(f)}
     * later serialises. This matches the v1 design exactly, and
     * avoids the v2 bug where {@code set(path, Map)} round-trip
     * through SnakeYAML silently flipped scalar values into Maps
     * (e.g. {@code xray.enable: true} re-parsed as a one-entry
     * Map and {@code getBoolean} returned {@code false}).
     *
     * @return the number of leaf keys that were filled in from
     *         defaults during this merge (zero means the user
     *         file is already complete). Sections recursively
     *         merged through a whitelisted path are recursed
     *         into without contributing to the count
     *         themselves; only their missing leaf keys are
     *         counted.
     */
    private static int mergeConfigurations(CommonYaml currentConfig,
                                            CommonYaml defaultConfig,
                                            String currentPath) {
        if (currentConfig == null || defaultConfig == null) return 0;

        int added = 0;
        for (String key : defaultConfig.getKeys(false)) {
            String fullKeyPath = (currentPath.isEmpty() ? "" : currentPath + ".") + key;

            if (currentConfig.contains(key)) {
                Object currentValue = currentConfig.get(key);
                Object defaultValue = defaultConfig.get(key);

                if (isSection(currentValue) && isSection(defaultValue) && WHITELIST_KEYS.contains(fullKeyPath)) {
                    // Both sides have a section for this key and
                    // the section is whitelisted: recurse. The
                    // recursion target is obtained via
                    // `getChild`, which on the Bukkit path
                    // returns a wrapper around the LIVE nested
                    // `ConfigurationSection` so the recursion
                    // mutates the parent in place. The default
                    // Map-based implementation in
                    // `CommonYaml.getChild` works for read-only
                    // inspection but loses the in-place
                    // mutation guarantee — that is fine for
                    // platforms (Fabric) that don't need it.
                    CommonYaml curChild = currentConfig.getChild(key);
                    CommonYaml defChild = defaultConfig.getChild(key);
                    if (curChild != null && defChild != null) {
                        added += mergeConfigurations(curChild, defChild, fullKeyPath);
                    } else {
                        // Fallback for platforms / sections that
                        // don't expose `getChild`: descend
                        // through the Map view and rely on the
                        // v1-style `set(key, leafValue)` for
                        // missing keys. We do NOT call
                        // `set(key, Map)` here — that would
                        // re-introduce the v2 in-memory
                        // corruption bug on the Bukkit path.
                        if (curChild == null) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> curMap = (java.util.Map<String, Object>) currentValue;
                            curChild = new link.star_dust.MinerTrack.common.MapBackedYaml(curMap);
                        }
                        if (defChild == null) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> defMap = (java.util.Map<String, Object>) defaultValue;
                            defChild = new link.star_dust.MinerTrack.common.MapBackedYaml(defMap);
                        }
                        added += mergeLeafOnly(curChild, defChild, fullKeyPath);
                    }
                }
                // else: keep user's value
            } else {
                // Add missing key from defaults
                currentConfig.set(key, defaultConfig.get(key));
                added++;
            }
        }
        return added;
    }

    /**
     * Map-only fallback recursion for sections that
     * {@link CommonYaml#getChild(String)} couldn't return a live
     * wrapper for. Mutates {@code curChild} (a Map-backed view)
     * in place, then writes the resulting Map back to the parent
     * via {@code parent.set(key, curMap)} so the parent's in-
     * memory state stays in sync. Note that {@code parent.set}
     * may store the Map as a value rather than a section on
     * some platforms; this is acceptable here because the
     * caller falls back to this path only when a live
     * section-wrapping {@code getChild} isn't available.
     */
    private static int mergeLeafOnly(CommonYaml curChild, CommonYaml defChild, String currentPath) {
        int added = 0;
        for (String key : defChild.getKeys(false)) {
            if (curChild.contains(key)) {
                Object cv = curChild.get(key);
                Object dv = defChild.get(key);
                if (cv instanceof java.util.Map && dv instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> curMap = (java.util.Map<String, Object>) cv;
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> defMap = (java.util.Map<String, Object>) dv;
                    link.star_dust.MinerTrack.common.MapBackedYaml nestedCur =
                            new link.star_dust.MinerTrack.common.MapBackedYaml(curMap);
                    link.star_dust.MinerTrack.common.MapBackedYaml nestedDef =
                            new link.star_dust.MinerTrack.common.MapBackedYaml(defMap);
                    added += mergeLeafOnly(nestedCur, nestedDef, currentPath + "." + key);
                }
            } else {
                curChild.set(key, defChild.get(key));
                added++;
            }
        }
        return added;
    }

    private static boolean isSection(Object v) {
        // A "section" is anything that can hold nested keys:
        // either a `Map` (Map-backed default configs) or a
        // Bukkit `ConfigurationSection` (live delegate). The
        // Bukkit check is delegated to
        // {@link link.star_dust.MinerTrack.common.PlatformTypes
        // #isConfigurationSection(Object)} so we never reference
        // the Bukkit class literal directly (the {@code
        // org/bukkit/} package is excluded from the shadow
        // JAR, and a class literal would force the JVM to
        // verify the class is loadable even on Fabric where
        // it isn't).
        if (v instanceof java.util.Map) return true;
        return link.star_dust.MinerTrack.common.PlatformTypes.isConfigurationSection(v);
    }

    /**
     * Return {@code true} if {@code userConfig} is missing
     * {@code _config-version} or its version is older than the version
     * embedded in {@code defaultConfig}. Returns {@code false} (no upgrade
     * needed) if the default has no version key (e.g. an unversioned
     * resource).
     */
    private static boolean needsUpgrade(CommonYaml userConfig, CommonYaml defaultConfig) {
        if (defaultConfig == null) return false;
        int defaultVersion = defaultConfig.getInt("_config-version", 0);
        if (defaultVersion <= 0) return false;
        if (userConfig == null) return true;
        int currentVersion = userConfig.getInt("_config-version", 0);
        return currentVersion < defaultVersion;
    }

    private static String stripExtension(String name) {
        return name.toLowerCase().endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * Fill in missing keys from defaults by inserting them into the
     * existing file at the correct positions, WITHOUT rewriting the
     * file. This preserves every comment (the header banner AND the
     * inline comments) the admin has in their config — a full YAML
     * round-trip via {@link CommonYaml#save(File)} would strip all
     * inline comments because SnakeYAML has no comment-preservation
     * mode.
     *
     * <p>Each missing key is inserted right after its parent section's
     * last line, with the correct indentation, so the resulting file
     * stays valid YAML and the new keys land in the right place even
     * when the parent section is not the last block in the file.
     *
     * <p>If {@code newVersion > 0}, the {@code _config-version} line is
     * updated in place (or appended if absent) so the version bump is
     * persisted without touching any comments.
     *
     * @param file the user's config file on disk
     * @param userConfig the user's config (BEFORE the in-memory merge,
     *                   so contains() still reports what is missing)
     * @param defaultConfig the JAR defaults
     * @param prefix current path prefix ("" at the root)
     * @param newVersion the version to write into _config-version, or
     *                   {@code <= 0} to leave the version line alone
     * @return the number of keys appended
     */
    private static int appendMissingDefaults(
            File file, CommonYaml userConfig,
            CommonYaml defaultConfig, String prefix,
            int newVersion) throws java.io.IOException {

        // Collect missing keys as (fullPath, value) pairs.
        List<Map.Entry<String, Object>> missing = new ArrayList<>();
        collectMissing(missing, userConfig, defaultConfig, prefix);

        if (missing.isEmpty() && newVersion <= 0) return 0;

        List<String> lines = new ArrayList<>(
                Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));

        // Persist the version bump in place (preserves comments).
        if (newVersion > 0) {
            updateVersionLine(lines, newVersion);
        }

        if (!missing.isEmpty()) {
            // Parse the existing file structure: key-path -> line index,
            // key-path -> indentation, key-path -> block end line (inclusive).
            Map<String, Integer> keyLine = new HashMap<>();
            Map<String, Integer> keyIndent = new HashMap<>();
            Map<String, Integer> blockEnd = new HashMap<>();
            parseStructure(lines, keyLine, keyIndent, blockEnd);

            // Compute insertion points, then apply them bottom-up so
            // earlier (higher-line) inserts do not shift later ones.
            List<Insertion> insertions = new ArrayList<>(missing.size());
            for (Map.Entry<String, Object> e : missing) {
                insertions.add(computeInsertion(lines, keyLine, keyIndent, blockEnd, e.getKey(), e.getValue()));
            }
            // Apply bottom-up (highest line first) so earlier inserts do not
            // shift later ones. For ties (same insertion line), shallower
            // keys are processed first so they end up AFTER the deeper keys
            // (addAll at the same index puts the last-processed item first),
            // keeping an outer sibling after its inner children.
            insertions.sort((a, b) -> {
                int c = Integer.compare(b.insertAfterLine, a.insertAfterLine);
                if (c != 0) return c;
                return Integer.compare(a.depth, b.depth);
            });
            for (Insertion ins : insertions) {
                lines.addAll(ins.insertAfterLine + 1, ins.lines);
            }
        }

        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        return missing.size();
    }

    /** A pending line insertion: insert {@code lines} after line {@code insertAfterLine}. */
    private static final class Insertion {
        final int insertAfterLine;
        final int depth; // number of path segments in the missing key
        final List<String> lines;
        Insertion(int insertAfterLine, int depth, List<String> lines) {
            this.insertAfterLine = insertAfterLine;
            this.depth = depth;
            this.lines = lines;
        }
    }

    /**
     * Recursively collect missing keys. Mirrors {@link #mergeConfigurations}
     * traversal: a missing key is collected regardless of the whitelist, but
     * recursion into an existing section only happens for whitelisted paths.
     */
    private static void collectMissing(
            List<Map.Entry<String, Object>> out,
            CommonYaml userConfig, CommonYaml defaultConfig, String prefix) {

        for (String key : defaultConfig.getKeys(false)) {
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;
            if (userConfig.contains(key)) {
                Object dv = defaultConfig.get(key);
                if (dv instanceof java.util.Map && WHITELIST_KEYS.contains(fullPath)) {
                    CommonYaml child = userConfig.getChild(key);
                    CommonYaml defChild = defaultConfig.getChild(key);
                    if (child != null && defChild != null) {
                        collectMissing(out, child, defChild, fullPath);
                    }
                }
            } else {
                out.add(new java.util.AbstractMap.SimpleEntry<>(fullPath, defaultConfig.get(key)));
            }
        }
    }

    /**
     * Parse the YAML file's line structure. Populates:
     * <ul>
     *   <li>{@code keyLine}: key-path -> 0-based line index of the key's line</li>
     *   <li>{@code keyIndent}: key-path -> leading-space count of the key's line</li>
     *   <li>{@code blockEnd}: key-path -> last line index (inclusive) of the
     *       key's block (its own line for a leaf, or the last child line for a
     *       section)</li>
     * </ul>
     * Comment lines, blank lines and list items are ignored.
     */
    private static void parseStructure(
            List<String> lines,
            Map<String, Integer> keyLine,
            Map<String, Integer> keyIndent,
            Map<String, Integer> blockEnd) {

        java.util.Deque<String> pathStack = new java.util.ArrayDeque<>();
        java.util.Deque<Integer> indentStack = new java.util.ArrayDeque<>();
        List<String> orderedPaths = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("-")) continue; // list item

            int indent = leadingSpaces(line);
            String key = parseKey(trimmed);
            if (key == null) continue;

            while (!indentStack.isEmpty() && indentStack.peek() >= indent) {
                indentStack.pop();
                pathStack.pop();
            }
            String path = pathStack.isEmpty() ? key : pathStack.peek() + "." + key;
            keyLine.put(path, i);
            keyIndent.put(path, indent);
            orderedPaths.add(path);
            indentStack.push(indent);
            pathStack.push(path);
        }

        // Compute blockEnd: a key's block ends at the last CONTENT line before
        // the next key at the same or a lower indentation. Trailing comment and
        // blank lines that belong to the next section are excluded, so a new
        // child is inserted before (not after) the next section's comment block.
        for (int idx = 0; idx < orderedPaths.size(); idx++) {
            String path = orderedPaths.get(idx);
            int indent = keyIndent.get(path);
            int keyLineIdx = keyLine.get(path);
            int end = lastContentLineBefore(lines, lines.size());
            for (int j = idx + 1; j < orderedPaths.size(); j++) {
                if (keyIndent.get(orderedPaths.get(j)) <= indent) {
                    end = lastContentLineBefore(lines, keyLine.get(orderedPaths.get(j)));
                    break;
                }
            }
            if (end < keyLineIdx) end = keyLineIdx;
            blockEnd.put(path, end);
        }
    }

    /**
     * Find the last line index (inclusive) before {@code beforeIndex} that is
     * a content line (not a comment and not blank). Returns {@code beforeIndex-1}
     * (or 0) if no content line exists before it.
     */
    private static int lastContentLineBefore(List<String> lines, int beforeIndex) {
        for (int i = beforeIndex - 1; i >= 0; i--) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) return i;
        }
        return Math.max(0, beforeIndex - 1);
    }

    /**
     * Compute where and how to insert a missing key. Finds the longest
     * existing ancestor path, then renders the missing subtree with the
     * correct indentation and returns an {@link Insertion} targeting the
     * ancestor's block end (or the end of the file if no ancestor exists).
     */
    private static Insertion computeInsertion(
            List<String> lines,
            Map<String, Integer> keyLine,
            Map<String, Integer> keyIndent,
            Map<String, Integer> blockEnd,
            String fullPath, Object value) {

        String[] parts = fullPath.split("\\.");
        String existingAncestor = null;
        int existingDepth = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
            String candidate = sb.toString();
            if (keyLine.containsKey(candidate)) {
                existingAncestor = candidate;
                existingDepth = i + 1;
            }
        }

        int insertAfterLine;
        int indent;
        if (existingAncestor != null) {
            insertAfterLine = blockEnd.get(existingAncestor);
            indent = keyIndent.get(existingAncestor) + 2;
        } else {
            // No ancestor exists (top-level missing key): insert after the
            // last content line so the new key does not land after a trailing
            // comment block that belongs to the previous section.
            insertAfterLine = lastContentLineBefore(lines, lines.size());
            indent = 0;
        }

        List<String> toInsert = new ArrayList<>();
        renderMissingKey(toInsert, parts, existingDepth, indent, value);
        return new Insertion(insertAfterLine, parts.length, toInsert);
    }

    /**
     * Render a missing key (and its value) as YAML lines. {@code startPart}
     * is the index into {@code parts} of the first key to render; keys before
     * it already exist in the file. The leaf key is rendered with its value
     * (scalar, list, or nested map); intermediate keys are rendered as bare
     * section headers.
     */
    private static void renderMissingKey(
            List<String> toInsert,
            String[] parts, int startPart, int indent,
            Object value) {

        for (int i = startPart; i < parts.length; i++) {
            String key = parts[i];
            String pad = nSpaces(indent + (i - startPart) * 2);
            if (i == parts.length - 1) {
                if (value instanceof java.util.Map) {
                    toInsert.add(pad + key + ":");
                    writeMapLines(toInsert, (java.util.Map<?, ?>) value, indent + (i - startPart) * 2 + 2);
                } else if (value instanceof java.util.List) {
                    toInsert.add(pad + key + ":");
                    for (Object item : (java.util.List<?>) value)
                        toInsert.add(pad + "  - " + toYaml(item));
                } else {
                    toInsert.add(pad + key + ": " + toYaml(value));
                }
            } else {
                toInsert.add(pad + key + ":");
            }
        }
    }

    /** Write a nested map as YAML lines at the given indentation. */
    private static void writeMapLines(List<String> out, java.util.Map<?, ?> map, int indent) {
        String pad = nSpaces(indent);
        for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof java.util.Map) {
                out.add(pad + e.getKey() + ":");
                writeMapLines(out, (java.util.Map<?, ?>) v, indent + 2);
            } else if (v instanceof java.util.List) {
                out.add(pad + e.getKey() + ":");
                for (Object item : (java.util.List<?>) v)
                    out.add(pad + "  - " + toYaml(item));
            } else {
                out.add(pad + e.getKey() + ": " + toYaml(v));
            }
        }
    }

    /** Update the {@code _config-version} line in place, or append it if absent. */
    private static void updateVersionLine(List<String> lines, int newVersion) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("_config-version:")) {
                lines.set(i, "_config-version: " + newVersion);
                return;
            }
        }
        lines.add("_config-version: " + newVersion);
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    /** Extract the key name from a trimmed YAML line (before the colon). */
    private static String parseKey(String trimmed) {
        int colon = trimmed.indexOf(':');
        if (colon < 0) return null;
        String key = trimmed.substring(0, colon).trim();
        if (key.length() >= 2
                && ((key.startsWith("'") && key.endsWith("'"))
                    || (key.startsWith("\"") && key.endsWith("\"")))) {
            key = key.substring(1, key.length() - 1);
        }
        return key.isEmpty() ? null : key;
    }

    private static String nSpaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    private static String toYaml(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        String s = v.toString();
        if (s.contains(":") || s.contains("#") || s.contains("\"") || s.contains("'"))
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return s;
    }
}
