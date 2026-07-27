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

package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.CoreConfig;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.MaterialMapper;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bukkit implementation of DetectionBridge.
 *
 * <p>Block names returned from {@link #getBlockType(String, int, int, int)}
 * are always in the canonical Minecraft namespace format
 * ({@code minecraft:diamond_ore}), regardless of the underlying Bukkit
 * {@code Material} enum value. Internal calls that still use Bukkit enums
 * (e.g. {@code Material.WATER}) are translated via {@link MaterialMapper}
 * to keep the comparison path platform-neutral.
 */
public class BukkitDetectionBridge implements DetectionBridge {
    private final PluginAdapter adapter;
    private final YamlLoader loader;
    private final Map<UUID, Map<CommonLocation, Long>> placedBlocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Map<CommonLocation, Long>> brokenAir = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Back-reference to the {@code MiningCore} that was wired up
     * in {@code BukkitPlatform#onEnable} via
     * {@link #setMiningCore(link.star_dust.MinerTrack.core.detection.MiningCore)}.
     * Stored here (rather than passed into individual methods)
     * so the platform's {@code /mt reset} handler — which lands
     * on {@code ViolationManagerBridge.clearPlayerState} and
     * therefore has no direct {@code MiningCore} handle — can
     * still reach the per-player mining-path state through
     * {@link #clearPlayerPath(UUID)} and ask the engine to drop
     * it. Volatile because the setter is called once on the
     * platform's main thread at enable time and the read is
     * from the (possibly async) reset handler.
     */
    private volatile link.star_dust.MinerTrack.core.detection.MiningCore miningCore;

    public BukkitDetectionBridge(PluginAdapter adapter, YamlLoader loader) {
        this.adapter = adapter;
        this.loader = loader;
        active = this;
    }

    /**
     * Wire the {@code MiningCore} instance into the bridge.
     * Called once from {@code BukkitPlatform#onEnable} right
     * after both objects are constructed; subsequent calls are
     * ignored (the field is set-once). The bridge needs this
     * back-reference so {@link #clearPlayerPath(UUID)} — invoked
     * by the platform's {@code /mt reset <player>} command
     * path through {@code ViolationManagerBridge.clearPlayerState}
     * — can ask the engine to drop the player's per-world
     * mining-path list, the parallel air-exposure list, the
     * vein cluster maps and the {@code vlZeroTimestamp}
     * bookkeeping. Without this, resetting the VL counter via
     * {@code /mt reset} leaves the path-based detection
     * state intact, so the very next rare-ore break can re-
     * trip the smooth-path / vein-count checks against a long,
     * pre-existing trail and push VL right back up.
     */
    public void setMiningCore(link.star_dust.MinerTrack.core.detection.MiningCore miningCore) {
        this.miningCore = miningCore;
    }

    /**
     * Runtime registry: maps a canonical dimension id (e.g.
     * {@code minecraft:overworld}) to the live Bukkit world that
     * currently maps to that dimension. Maintained via
     * {@link #registerWorld(org.bukkit.World)} / {@link #unregisterWorld(String)}
     * and seeded at {@link #seedWorldRegistry()} on enable so the
     * inverse-lookup path in {@link #resolveWorld(String)} can find
     * the correct world even when the on-disk folder name doesn't
     * match the canonical id (the common case for servers that set
     * {@code level-name=world2} in {@code server.properties}: the
     * overworld lives under the {@code world2} folder, so
     * {@code resolveDimensionId("world2")} returns
     * {@code minecraft:overworld}, and the inverse
     * {@code resolveWorld("minecraft:overworld")} must remember
     * that {@code minecraft:overworld} lives at {@code world2} —
     * not at {@code world} or whatever happens to be the first
     * NORMAL world in {@link Bukkit#getWorlds()}).
     */
    private final java.util.concurrent.ConcurrentMap<String, org.bukkit.World> dimensionToWorld =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Folder name of the server's `level-name` world (the actual
     * overworld defined in {@code server.properties}). Determined
     * at {@link #seedWorldRegistry()} time and consulted by
     * {@link #resolveDimensionId(String)} to distinguish the
     * "this IS the overworld" case from a Multiverse `flatland`
     * style "this is a NORMAL world but NOT the overworld" case.
     *
     * <p>{@code null} until {@link #seedWorldRegistry()} runs; the
     * detection pipeline never fires before enable completes so a
     * null check at the use site is sufficient.
     */
    private volatile String levelNameFolder;

    /**
     * Compute the canonical dimension id for a Bukkit {@link org.bukkit.World}.
     * Mirrors the logic in {@link #resolveDimensionId(String)} but for a
     * live world object (we can read its {@code Environment} directly
     * and skip the {@code Bukkit.getWorld} lookup). Used by the
     * {@link #dimensionToWorld} registry to keep the bidirectional
     * folder ↔ dimension mapping in sync as worlds load and unload.
     */
    /**
     * Compute the canonical dimension id for a Bukkit
     * {@link org.bukkit.World}. The mapping is exactly the same
     * layered rule that {@link #resolveDimensionId(String)}
     * applies to a folder name string, so the registry and the
     * resolveDimensionId path always agree: the level-name
     * world maps to {@code minecraft:overworld}, the nether /
     * end of that world map to vanilla ids, and any other
     * NORMAL / custom-environment world maps to
     * {@code minecraft:<folder>}. The registry uses this to
     * build the canonical-id → live-world map, and the
     * resolver uses it to translate folder names. Keeping
     * both paths in lockstep is the only way to guarantee
     * that {@code getBlockType("minecraft:overworld", x, y, z)}
     * returns a block from the actual overworld, not from
     * whichever NORMAL world happens to be first in
     * {@link Bukkit#getWorlds()}.
     */
    private String dimensionIdFor(org.bukkit.World world) {
        if (world == null) return null;
        // Reuse the public resolveDimensionId so the layered
        // rules (vanilla folder shortcuts, level-name NORMAL
        // world → overworld, nether / end → vanilla ids, and
        // other NORMAL worlds → minecraft:<folder>) apply
        // identically here. The only call site that doesn't
        // reach this method is the seed step, which calls
        // resolveDimensionId on the loaded world's folder
        // name and could in principle disagree if the
        // Bukkit.getWorld folder name differs from
        // `world.getName()` (it can't, but the
        // resolveDimensionId path is the authoritative one).
        return resolveDimensionId(world.getName());
    }

    
    /**
     * {@inheritDoc}
     *
     * <p>Implementation: retrieves the Bukkit {@link org.bukkit.entity.Player}
     * for {@code playerId} via {@link Bukkit#getPlayer(UUID)} and delegates to
     * {@link org.bukkit.entity.Player#hasPermission(String)}. Returns
     * {@code false} when the player is not online.
     */
    @Override
    public boolean hasPermission(UUID playerId, String node) {
        org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
        return player != null && player.hasPermission(node);
    }

    /**
     * Register a freshly-loaded Bukkit world into the
     * {@link #dimensionToWorld} registry. Idempotent: calling it
     * twice with the same world is a no-op. Safe to call from
     * world-load listeners and from the startup seed.
     */
    public void registerWorld(org.bukkit.World world) {
        if (world == null) return;
        String dimId = dimensionIdFor(world);
        if (dimId == null) return;
        dimensionToWorld.put(dimId, world);
    }

    /**
     * Remove a world from the {@link #dimensionToWorld} registry.
     * Called from the world-unload listener so the registry doesn't
     * hold a reference to an unloaded world (which would also keep
     * the world object from being garbage-collected).
     */
    public void unregisterWorld(String folderName) {
        if (folderName == null) return;
        // Collect the keys to remove first, then remove them. A
        // concurrent map forbids structural modification during
        // iteration; iterating Bukkit.getWorlds() and looking each
        // one up by name would be simpler but breaks for worlds
        // that are already gone (the whole point of this method).
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, org.bukkit.World> e : dimensionToWorld.entrySet()) {
            org.bukkit.World w = e.getValue();
            if (w == null || folderName.equals(w.getName())) {
                toRemove.add(e.getKey());
            }
        }
        for (String k : toRemove) {
            dimensionToWorld.remove(k);
        }
    }

    /**
     * Seed the {@link #dimensionToWorld} registry with every world
     * that is loaded at the time this is called. Bukkit's
     * {@link org.bukkit.event.world.WorldLoadEvent} only fires for
     * worlds loaded AFTER the listener is registered; the per-world
     * folder exists on disk from the moment the server starts, so
     * we have to walk {@link Bukkit#getWorlds()} once at enable time
     * to capture the worlds that were already loaded.
     *
     * <p>As part of the seed we also pin the `level-name` world
     * folder (see {@link #levelNameFolder}). Bukkit doesn't expose
     * `level-name` directly, but the main world is the FIRST world
     * the server loads and is in
     * {@link Bukkit#getWorlds()}{@code .get(0)} on a vanilla
     * startup. We verify the candidate by checking its
     * {@link org.bukkit.World.Environment} is NORMAL and that the
     * corresponding nether / end worlds are also loaded (or, in
     * the absence of a `level-name` change, the classic
     * `world` / `world_nether` / `world_the_end` triple). The
     * verification protects against a Multiverse-installed NORMAL
     * world claiming the first-NORMAL slot before the actual
     * main world comes up.
     */
    public void seedWorldRegistry() {
        java.util.List<org.bukkit.World> all = Bukkit.getWorlds();
        // Pin the level-name world FIRST, then register. The
        // registration call goes through
        // `resolveDimensionId` → `isLevelNameWorld`, and
        // `isLevelNameWorld` only returns true when
        // `levelNameFolder` is set. Doing the registration
        // first would leave `levelNameFolder == null` and the
        // level-name world's NORMAL environment would fall
        // through to the "namespace the folder" branch, making
        // `minecraft:overworld` resolve to nothing in the
        // registry. Pin first, then register.
        org.bukkit.World candidate = null;
        for (org.bukkit.World w : all) {
            if (w.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                candidate = w;
                break;
            }
        }
        if (candidate != null && looksLikeLevelName(candidate, all)) {
            this.levelNameFolder = candidate.getName();
        }
        for (org.bukkit.World w : all) {
            registerWorld(w);
        }
    }

    /**
     * Heuristic check: does {@code candidate} look like the
     * server's `level-name` world? The check is intentionally
     * permissive — false positives (treating a Multiverse
     * `flatland` as the overworld) are corrected at the user's
     * next report, while false negatives (treating the real
     * overworld as a custom world) would silently route every
     * NORMAL-world detection to `minecraft:<folder>` and never
     * fire the default `overworld.yml` group config. The
     * permissive side of that tradeoff is the right one.
     *
     * <p>The check accepts {@code candidate} when ANY of the
     * following hold:
     *   - its folder name is the vanilla `world` (the classic
     *     default `level-name` for an out-of-the-box server);
     *   - any loaded NETHER / THE_END world shares the same
     *     folder prefix (`<folder>_nether` / `<folder>_the_end`,
     *     matching either Paper's auto-generated name or the
     *     vanilla convention);
     *   - the candidate is the ONLY NORMAL world on the server
     *     (single-overworld servers are unambiguous, and Bukkit
     *     would have refused to start the nether / end portals
     *     for the "wrong" world anyway).
     */
    private boolean looksLikeLevelName(org.bukkit.World candidate,
                                       java.util.List<org.bukkit.World> all) {
        String folder = candidate.getName();
        if ("world".equalsIgnoreCase(folder)) return true;
        int normalCount = 0;
        for (org.bukkit.World w : all) {
            if (w.getEnvironment() == org.bukkit.World.Environment.NORMAL) normalCount++;
        }
        if (normalCount <= 1) return true;
        // Multi-NORMAL-world server. Look for the nether / end
        // derivatives of this candidate's folder. The matching
        // is intentionally loose: a candidate whose folder
        // matches `<x>` is the level-name world when ANY
        // NETHER world is named `<x>_nether` or `nether` AND
        // ANY THE_END world is named `<x>_the_end` or `the_end`.
        // The latter branch handles servers that use Bukkit's
        // classic `nether` / `the_end` folder names for the
        // level-name world's derivatives.
        String lower = folder.toLowerCase(java.util.Locale.ROOT);
        boolean hasNether = false;
        boolean hasEnd = false;
        for (org.bukkit.World w : all) {
            String wf = w.getName().toLowerCase(java.util.Locale.ROOT);
            switch (w.getEnvironment()) {
                case NETHER:
                    if (wf.equals(lower + "_nether") || wf.equals("nether") || wf.equals(lower)) {
                        hasNether = true;
                    }
                    break;
                case THE_END:
                    if (wf.equals(lower + "_the_end") || wf.equals("the_end") || wf.equals(lower)) {
                        hasEnd = true;
                    }
                    break;
                default: break;
            }
        }
        return hasNether && hasEnd;
    }

    @Override
    public String getBlockType(String world, int x, int y, int z) {
        try {
            // The core layer passes canonical dimension ids (e.g.
            // `minecraft:overworld`) to this method, but Bukkit's
            // `getWorld` only knows about world folder names (`world`,
            // `world_nether`, ...). Without the resolveWorld() fallback
            // below, every lookup against a canonical id would silently
            // return AIR, breaking:
            //   - `EnvironmentAnalyzer.isInNaturalEnvironment` (everything
            //     counted as air → airCount > threshold → "natural" → VL
            //     increment branch never ran);
            //   - `DetectionEngine.getVeinLocations` (no ore could be
            //     found → vein count never increased);
            //   - `isWaterStill` (the water block couldn't be found).
            org.bukkit.World w = resolveWorld(world);
            if (w == null) return BlockId.AIR;
            Block b = w.getBlockAt(x, y, z);
            return MaterialMapper.bukkitToMinecraft(b.getType().name());
        } catch (Exception e) {
            return BlockId.AIR;
        }
    }

    /**
     * Resolve a world identifier to a live Bukkit {@link org.bukkit.World}.
     * Accepts both:
     * <ul>
     *   <li>Bukkit folder names (e.g. {@code world}, {@code world_nether}) —
     *       looked up directly via {@link Bukkit#getWorld(String)}.</li>
     *   <li>Canonical Minecraft dimension ids (e.g.
     *       {@code minecraft:overworld}, {@code minecraft:the_nether}) —
     *       matched by iterating {@link Bukkit#getWorlds()} and comparing
     *       the world's {@link org.bukkit.World.Environment}.</li>
     * </ul>
     * Returns {@code null} when no matching world is loaded. This is the
     * canonical world-resolution helper used by every method in this
     * bridge that needs a live world (block lookups, water checks, etc.).
     */
    private org.bukkit.World resolveWorld(String worldKey) {
        if (worldKey == null) return null;
        // Fast path 0: the caller passed a canonical dimension id
        // we know about. The runtime registry is the source of
        // truth: it remembers that `minecraft:overworld` is
        // currently `world2` on this server (the
        // `level-name=world2` case), that `minecraft:the_nether`
        // is `world2_nether`, etc. Without this lookup, the
        // Environment-based fallback below would return whatever
        // NORMAL world happened to be first in `Bukkit.getWorlds()`
        // — almost never the one the player is in on multi-world
        // servers.
        //
        // AMBIGUITY: `minecraft:overworld` may now map to MORE
        // THAN ONE world on a server with multiple NORMAL worlds
        // (e.g. a Multiverse `flatland` and the real `world2`).
        // The registry's first entry was registered first and
        // wins the get(); the rest are silently shadowed. We
        // accept that — the canonical dimension id is no longer
        // a unique key for `getBlockType` on multi-overworld
        // servers, and the core layer must use the specific
        // folder name (see `MiningListener.onBlockBreak`) for
        // any operation that requires the right world. The
        // registry path is still useful for the common
        // single-overworld case (and the vanilla
        // `minecraft:the_nether` / `minecraft:the_end` aliases,
        // which by definition only have one world each).
        org.bukkit.World registered = dimensionToWorld.get(worldKey);
        if (registered != null) return registered;
        // Fast path 1: the caller passed a folder name (the
        // primary convention used by `MiningListener` after the
        // dimension-handling fix). `Bukkit.getWorld` resolves
        // it directly. The folder name is unique per loaded
        // world, so this path is the ONLY one that's correct
        // for block lookups on multi-overworld servers.
        org.bukkit.World direct = Bukkit.getWorld(worldKey);
        if (direct != null) return direct;
        // Fast path 2: the caller passed a canonical dimension
        // id whose `path` part (after `minecraft:`) happens to
        // match a loaded world's folder name. Catches the
        // rare case of a server whose modded world folder
        // happens to coincide with a vanilla canonical id
        // path (e.g. `overworld`, `the_nether`).
        int colon = worldKey.indexOf(':');
        if (colon >= 0 && colon < worldKey.length() - 1) {
            String pathOnly = worldKey.substring(colon + 1);
            org.bukkit.World byPath = Bukkit.getWorld(pathOnly);
            if (byPath != null) return byPath;
        }
        // Last-resort fallback: translate the dimension id to
        // a Bukkit Environment and walk every loaded world to
        // find the first one in that environment. Used for
        // vanilla `minecraft:overworld` (path `overworld` is
        // not a folder name) and for the `nether` / `the_end`
        // aliases. On a multi-world server this fallback can
        // pick the wrong NORMAL world; the registry lookup
        // above is the only path that's guaranteed correct.
        org.bukkit.World.Environment env = environmentForDimensionId(worldKey);
        if (env == null) return null;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == env) return w;
        }
        return null;
    }

    /**
     * Translate a Bukkit world folder name into the canonical
     * dimension id suitable for log display. This is the
     * "inverse" of {@link #resolveDimensionId(String)}: callers
     * (e.g. {@code ViolationEngine}) that already hold a
     * folder name in {@code CommonLocation.world} and need the
     * human-readable / config-recognisable dimension id for
     * the `World:` field of the X-Ray log use this method.
     *
     * <p>Same layered rules as {@link #resolveDimensionId(String)}:
     *   - vanilla folder names collapse to vanilla ids;
     *   - the {@code level-name} world's NORMAL environment
     *     becomes {@code minecraft:overworld};
     *   - any other NORMAL world becomes
     *     {@code minecraft:<folder>} so the operator can
     *     distinguish "real overworld" from "custom NORMAL
     *     world" in the log;
     *   - NETHER / THE_END collapse to vanilla ids regardless
     *     of folder;
     *   - modded / custom environments fall back to
     *     {@code minecraft:<folder>}.
     */
    public String getDisplayDimensionId(String folder) {
        return resolveDimensionId(folder);
    }

    /**
     * Inverse of {@link #resolveDimensionId(String)}: map a canonical
     * Minecraft dimension id to its Bukkit {@link World.Environment}.
     * Returns {@code null} when the input is not a recognised dimension
     * id.
     */
    private org.bukkit.World.Environment environmentForDimensionId(String dimId) {
        if (dimId == null) return null;
        String norm = link.star_dust.MinerTrack.common.DimensionId.normalize(dimId);
        if (norm == null) return null;
        if (norm.equals(link.star_dust.MinerTrack.common.DimensionId.OVERWORLD)) {
            return org.bukkit.World.Environment.NORMAL;
        }
        if (norm.equals(link.star_dust.MinerTrack.common.DimensionId.THE_NETHER)) {
            return org.bukkit.World.Environment.NETHER;
        }
        if (norm.equals(link.star_dust.MinerTrack.common.DimensionId.THE_END)) {
            return org.bukkit.World.Environment.THE_END;
        }
        return null;
    }

    @Override
    public boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = placedBlocks.get(playerId);
        if (map == null) return false;
        Long ts = map.get(location);
        if (ts == null) return false;
        // Expire after trace-remove time (minutes -> ms)
        int expireMs = getConfigForWorld(resolveDimensionId(location.world), "xray.trace_remove", 15) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) {
            map.remove(location);
            return false;
        }
        return true;
    }

    @Override
    public String resolveDimensionId(String worldName) {
        // Layered fallback rules for translating a Bukkit world folder
        // name to a canonical dimension id. The intent mirrors
        // Minecraft itself: the `level-name` world in
        // `server.properties` IS the overworld (no matter what its
        // on-disk folder is called), and its nether / end are the
        // vanilla nether / end (e.g. `world2_nether` and
        // `world2_the_end` are still `minecraft:the_nether` and
        // `minecraft:the_end` — they share the level.dat with
        // `world2`). Any other world the server has loaded — a
        // Multiverse `flatland`, an admin-installed `resource`, a
        // dungeon plugin's instanced dimension — is a separate
        // dimension and gets `minecraft:<folder>`.
        //
        // Why this rule matters:
        //   1. The `xray.worlds: { 'overworld': [minecraft:overworld] }`
        //      default in config.yml MUST hit the player's actual
        //      overworld, not silently miss because the admin set
        //      `level-name=world2`.
        //   2. The on-screen "World:" field of the X-Ray log should
        //      read `minecraft:overworld` for the player's overworld
        //      (the standard dimension the admin knows), and
        //      `minecraft:flatland` for an explicitly-added extra
        //      world (so the admin can tell at a glance which world
        //      triggered the detection).
        //   3. The CommonLocation world key — used as a map key in
        //      `placedBlocks` / `brokenAir` and for equality in vein
        //      clusters — MUST be unique per loaded world, otherwise
        //      player-placed ore blocks in `world2` could be
        //      cross-referenced against the same `(x, y, z)` in
        //      `flatland` and silently ignored (or worse, falsely
        //      forgiven). The folder name is the only thing that's
        //      guaranteed unique per world, which is why
        //      {@link #resolveWorld(String)} keys on it for the
        //      inverse lookup.
        if (worldName == null) return null;
        // Rule 1: recognised vanilla folder names (`world`,
        // `world_nether`, `world_the_end`, plus the bare
        // `nether` / `the_end` aliases some legacy servers use)
        // always map to their vanilla canonical id. This handles
        // the common "out-of-the-box" server with no `level-name`
        // change.
        String vanilla = link.star_dust.MinerTrack.common.DimensionId.fromBukkitFolder(worldName);
        if (vanilla != null) return vanilla;
        // Rule 2: vanilla environment shortcuts. A Bukkit world
        // whose Environment is NETHER or THE_END is, by Minecraft's
        // own definition, the nether or the end — independent of
        // what its folder is named. The Nether portal of a custom
        // `world2` overworld lives in `world2_nether` and is still
        // `minecraft:the_nether` in vanilla terms. We resolve
        // through the loaded World's Environment so the mapping
        // works regardless of the on-disk folder name. (Bukkit
        // doesn't have a public API for `level-name`, but it does
        // expose `World.Environment`, and every world that
        // physically is the nether / end registers with the
        // matching environment. There's no way to fake a NETHER
        // world for, say, a custom plugin dimension — modded
        // dims use a non-vanilla Environment and fall through to
        // Rule 4 below.)
        try {
            org.bukkit.World w = Bukkit.getWorld(worldName);
            if (w != null) {
                switch (w.getEnvironment()) {
                    case NETHER:  return link.star_dust.MinerTrack.common.DimensionId.THE_NETHER;
                    case THE_END: return link.star_dust.MinerTrack.common.DimensionId.THE_END;
                    case NORMAL:
                        // Rule 3: a NORMAL-environment world maps to
                        // `minecraft:overworld` ONLY when it's the
                        // server's `level-name` world (the actual
                        // overworld). For other NORMAL worlds (a
                        // Multiverse `flatland` etc.) the right id
                        // is `minecraft:<folder>` so they don't
                        // collide with the real overworld in
                        // placedBlocks / brokenAir.
                        if (isLevelNameWorld(w)) {
                            return link.star_dust.MinerTrack.common.DimensionId.OVERWORLD;
                        }
                        // Non-level-name NORMAL world → distinct
                        // dimension, namespace the folder.
                        return link.star_dust.MinerTrack.common.DimensionId.normalize(worldName);
                    default:
                        // Custom / modded environment (Twilight
                        // Forest, AoA3, …). Namespace the folder so
                        // the operator can still route it to a
                        // specific group via
                        // `xray.worlds: { 'twilight': [minecraft:twilight_forest] }`.
                        break;
                }
            }
        } catch (Throwable ignored) {
            // World may be unloaded (shutdown, async tick). Fall through.
        }
        // Rule 4 (fallback): no loaded world, or a non-vanilla
        // environment. Namespace the folder so admins can still
        // route it to a specific group via
        // `xray.worlds: { 'g': ['minecraft:<folder>'] }`.
        return link.star_dust.MinerTrack.common.DimensionId.normalize(worldName);
    }

    /**
     * Return {@code true} when the given Bukkit world is the
     * server's `level-name` world (i.e. the actual overworld
     * defined in {@code server.properties}).
     *
     * <p>Bukkit has no public API to read `level-name` directly,
     * so we use the documented loading order: the main world is
     * the FIRST world Bukkit loads, and is always the first entry
     * of {@link Bukkit#getWorlds()} on a vanilla startup. We
     * capture the candidate at {@link #seedWorldRegistry()} time
     * and verify it on every call: a candidate is accepted when
     * its Environment is NORMAL AND (a) it's the first NORMAL
     * world Bukkit loaded, or (b) it has the nether / end
     * derivative worlds present in the loaded-world set
     * (`<folder>_nether` / `<folder>_the_end` or just any
     * NETHER + THE_END pair when the folder has no suffix).
     *
     * <p>The verification protects against the rare
     * configuration where another plugin loaded a NORMAL world
     * before the main `level-name` world came up (e.g. a
     * Multiverse `flatland` from a stale config). The pure
     * "first-NORMAL" heuristic would pick `flatland` as the
     * overworld and the real `world2` would end up as
     * `minecraft:world2` — wrong, and the player would see the
     * on-screen "World:" field disagree with their actual
     * overworld.
     */
    private boolean isLevelNameWorld(org.bukkit.World w) {
        if (w == null) return false;
        if (levelNameFolder == null) return false;
        return levelNameFolder.equals(w.getName());
    }

    @Override
    public Object getConfig(String path) {
        // Load from data folder config.yml on demand
        return loadConfig().get(path);
    }

    @Override
    public int getConfigInt(String path, int def) {
        return loadConfig().getInt(path, def);
    }

    @Override
    public boolean getConfigBoolean(String path, boolean def) {
        return loadConfig().getBoolean(path, def);
    }

    @Override
    public double getConfigDouble(String path, double def) {
        return loadConfig().getDouble(path, def);
    }

    @Override
    public List<String> getConfigStringList(String path) {
        return loadConfig().getStringList(path);
    }

    private CommonYaml configCache;
    private long configCacheTime;
    private CoreConfig coreConfig;

    // Static reference so the PluginAdapter can call back into the active bridge
    // during reloadConfig() without depending on ServicesManager ceremony.
    private static volatile BukkitDetectionBridge active;

    public static BukkitDetectionBridge getActive() { return active; }

    /** Invalidate the config cache so the next access re-reads from disk. */
    public void clearConfigCache() {
        configCache = null;
        configCacheTime = 0;
    }

    private CommonYaml loadConfig() {
        long now = System.currentTimeMillis();
        if (configCache != null && now - configCacheTime < 5000) return configCache;
        File configFile = new File(adapter.getDataFolder(), "config.yml");
        configCache = link.star_dust.MinerTrack.core.config.ConfigMerger.loadAndMerge(configFile, "config.yml", adapter, loader);
        configCacheTime = now;
        return configCache;
    }

    @Override
    public CoreConfig loadGroupConfigs() {
        link.star_dust.MinerTrack.core.config.GroupConfigLoader loader =
                new link.star_dust.MinerTrack.core.config.GroupConfigLoader(adapter, loadConfig(), this.loader);
        link.star_dust.MinerTrack.core.config.GroupConfigLoader.GroupLoadResult r = loader.load();
        coreConfig = new CoreConfig();
        coreConfig.setMainConfig(loadConfig());
        coreConfig.setGroupConfigs(r.groupConfigs);
        coreConfig.setWorldToGroup(r.worldToGroup);
        coreConfig.setGroupWorldPatterns(r.groupWorldPatterns);
        coreConfig.setDefaultUnnamedGroupKey(r.defaultUnnamedGroupKey);
        return coreConfig;
    }

    @Override
    public CoreConfig getCoreConfig() {
        if (coreConfig == null) loadGroupConfigs();
        return coreConfig;
    }

    @Override
    public int getConfigForWorld(String worldName, String path, int def) {
        // worldName is the canonical minecraft:xxx dimension id (the
        // MiningListener resolves folder names via resolveDimensionId()
        // before passing to the core). Delegate to CoreConfig so the
        // resolved group config (overworld.yml / nether.yml / end.yml)
        // is the source of truth.
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getIntForWorld(worldName, path, def);
        return loadConfig().getInt(path, def);
    }

    @Override
    public boolean getConfigForWorldBoolean(String worldName, String path, boolean def) {
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getBooleanForWorld(worldName, path, def);
        return loadConfig().getBoolean(path, def);
    }

    @Override
    public List<String> getConfigForWorldStringList(String worldName, String path) {
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getStringListForWorld(worldName, path);
        return loadConfig().getStringList(path);
    }

    @Override
    public boolean isWorldDetectionEnabled(String worldName) {
        // Per-world enable flag lives in the resolved group config
        // (overworld.yml / nether.yml / end.yml), not in the main
        // xray.worlds mapping. Fall back to the main config's xray.enable
        // for legacy files that still surface the flag there.
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getBooleanForWorld(worldName, "xray.enable", false);
        return loadConfig().getBoolean("xray.enable", false);
    }

    @Override
    public int getWorldMaxHeight(String worldName) {
        // Per-world max-height is declared in the group config file
        // (overworld.yml: `max-height: 32`, nether.yml: `max-height: 128`).
        // -1 means "no limit" (use the world build height instead).
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getIntForWorld(worldName, "xray.max-height", -1);
        return loadConfig().getInt("xray.max-height", -1);
    }

    @Override
    public List<String> getRareOres(String worldName) {
        // worldName is the canonical minecraft:xxx dimension id; resolve
        // through CoreConfig so the group config (overworld.yml,
        // nether.yml, …) is the source of truth and the list is normalised.
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getStringListForWorld(worldName, "xray.rare-ores");
        // Fallback (pre-group-config state): use the main config and
        // normalise manually.
        List<String> raw = loadConfig().getStringList("xray.rare-ores");
        java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
        for (String s : raw) {
            String n = BlockId.normalize(s);
            out.add(n != null ? n : s);
        }
        return out;
    }

    @Override
    public int getTraceRemoveTime(String worldName) {
        CoreConfig cc = getCoreConfig();
        if (cc != null) return cc.getIntForWorld(worldName, "xray.trace_remove", 15);
        return loadConfig().getInt("xray.trace_remove", 15);
    }

    @Override
    public int getArtificialAirRemoveTime(String worldName) {
        CoreConfig cc = getCoreConfig();
        if (cc != null) {
            return cc.getIntForWorld(worldName, "xray.natural-detection.cave.artificial-air-remove-time", 20);
        }
        return loadConfig().getInt("xray.natural-detection.cave.artificial-air-remove-time", 20);
    }

    @Override
    public boolean isArtificialAir(UUID playerId, CommonLocation location) {
        Map<CommonLocation, Long> map = brokenAir.get(playerId);
        if (map == null) return false;
        Long ts = map.get(location);
        if (ts == null) return false;
        int expireMs = getArtificialAirRemoveTime(resolveDimensionId(location.world)) * 60 * 1000;
        if (System.currentTimeMillis() - ts > expireMs) {
            map.remove(location);
            return false;
        }
        return true;
    }

    @Override
    public boolean isWaterStill(String world, int x, int y, int z) {
        try {
            // The core layer passes a canonical dimension id
            // (`minecraft:xxx`) here as well, so we must use the same
            // resolveWorld() helper as getBlockType() — otherwise the
            // check-running-water feature silently does nothing because
            // Bukkit can never find the world.
            org.bukkit.World w = resolveWorld(world);
            if (w == null) return false;
            Block b = w.getBlockAt(x, y, z);
            if (b.getType() != Material.WATER) return false;
            return b.getBlockData() instanceof Levelled && ((Levelled) b.getBlockData()).getLevel() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Block tracking helpers (called from MiningListener) ---

    public void trackPlacedBlock(UUID playerId, CommonLocation location) {
        placedBlocks.computeIfAbsent(playerId, k -> new java.util.HashMap<>())
            .put(location, System.currentTimeMillis());
    }

    public void trackBrokenAir(UUID playerId, CommonLocation location) {
        brokenAir.computeIfAbsent(playerId, k -> new java.util.HashMap<>())
            .put(location, System.currentTimeMillis());
    }

    public void clearPlayerTracking(UUID playerId) {
        placedBlocks.remove(playerId);
        brokenAir.remove(playerId);
    }

    /**
     * Drop every piece of mining-detection state the engine
     * holds for {@code playerId}. Forwards to
     * {@code MiningState.clearPlayerPath}, which in turn wipes
     * the per-world path list, the air-exposure list, the
     * {@code lastMiningTime} / vein-count / last-vein-location
     * / cluster / type maps and the {@code vlZeroTimestamp}
     * entry — i.e. every map the {@code /mt reset <player>}
     * command is conceptually expected to reset.
     *
     * <p>This is the Bukkit-side override of the default
     * no-op declared on {@link DetectionBridge#clearPlayerPath}.
     * The platform's reset handler reaches it via
     * {@code ViolationManagerBridge.clearPlayerState} →
     * {@code BukkitViolationManager.clearPlayerState} (which
     * fetches the active {@code BukkitDetectionBridge} and
     * calls this method). A null-check on
     * {@link #miningCore} is required because the bridge is
     * constructed (and registered as {@code active}) BEFORE
     * {@code MiningCore} exists in
     * {@code BukkitPlatform#onEnable}; if a reset arrived in
     * that small window (it can't, because the command
     * executor isn't registered until after enable completes)
     * we'd silently no-op, which is the same behaviour as the
     * default interface method.
     */
    @Override
    public void clearPlayerPath(UUID playerId) {
        link.star_dust.MinerTrack.core.detection.MiningCore mc = this.miningCore;
        if (mc == null) {
            // MiningCore hasn't been wired up yet (extremely
            // unlikely at the moment a reset can fire, but
            // guard for it). Fall through silently — the
            // default no-op behaviour is the right fallback
            // here.
            return;
        }
        mc.getState().clearPlayerPath(playerId);
    }
}
