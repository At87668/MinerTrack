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

package link.star_dust.MinerTrack.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Open a resource packaged inside this mod's own JAR / classes
 * directory, bypassing any shared classloader.
 *
 * <p>Why this exists:
 *
 * <p>On Bukkit each plugin has its own {@code PluginClassLoader}, and on
 * Fabric each mod is loaded into a dedicated {@code ClassLoader}, so the
 * standard {@code Class#getResourceAsStream("config.yml")} returns the
 * correct resource for that plugin / mod.
 *
 * <p>On Forge and NeoForge, however, every mod is loaded into a single
 * shared {@code ClassLoader} (the game's {@code LaunchClassLoader}). A
 * call to {@code getResourceAsStream("config.yml")} on that shared
 * classloader walks the entire game classpath and returns whichever
 * mod's {@code config.yml} happens to appear first — usually some
 * unrelated mod, not MinerTrack. MinerTrack then copies that other
 * mod's {@code config.yml} into its own data folder on first startup
 * (or parses it as the merged defaults), which silently breaks the
 * install. The bug reproduces whenever another mod on the classpath
 * ships a {@code config.yml} at the classpath root; removing the
 * conflicting mod "fixes" the symptom because the shared classloader
 * then happens to fall through to MinerTrack's entry.
 *
 * <p>This helper walks the protection domain of the calling class
 * instead, locates the mod's own JAR file (or the dev-time classes
 * directory), and returns an input stream for the named resource
 * if and only if that JAR / directory contains it. The shared
 * game classloader is never consulted, so name collisions with
 * other mods are impossible by construction.
 *
 * <p>Intentionally platform-agnostic: the caller passes
 * {@code this.getClass()} (i.e. the mod's adapter class) so the
 * lookup is anchored to MinerTrack's own code source, not to the
 * shared game classpath.
 */
public final class ModResourceLoader {

    private ModResourceLoader() {}

    /**
     * Look up {@code resourcePath} in {@code owner}'s own JAR file or
     * classes directory. Returns {@code null} if the resource is not
     * packaged with the mod — callers treat that as "no default
     * available" and fall back to an empty defaults map.
     *
     * <p>Resource path uses the JAR entry convention: forward slashes
     * as separators, no leading slash. {@code "config.yml"} resolves
     * to {@code <jar>/config.yml}; {@code "Configuration/overworld.yml"}
     * resolves to {@code <jar>/Configuration/overworld.yml}.
     *
     * <p>The returned stream is a {@link java.io.ByteArrayInputStream}:
     * the entry's bytes are copied into memory before the underlying
     * {@link JarFile} is closed, so the caller can consume the stream
     * at any time (and should close it once done, like any input
     * stream). This deliberately avoids returning a stream that
     * borrows from a still-open {@link JarFile} — a {@code JarFile}
     * closed in a try-with-resources block would leave the returned
     * stream reading zero bytes (the classic "stream from a closed
     * JAR" bug that produces empty config files).
     *
     * @param owner       the class whose code source (JAR or classes
     *                    directory) defines "this mod's resources".
     *                    Pass {@code this.getClass()} from the
     *                    platform adapter.
     * @param resourcePath path to the resource relative to the JAR
     *                     root (forward slashes, no leading slash).
     * @return an open input stream with the full resource content,
     *         or {@code null} if the resource is not packaged with
     *         the mod.
     */
    public static InputStream open(Class<?> owner, String resourcePath) {
        if (owner == null || resourcePath == null) return null;
        // Strip a leading slash for callers that pass "/config.yml"
        // instead of "config.yml" — common when callers port
        // ClassLoader#getResourceAsStream semantics, where the
        // leading slash is mandatory. JarFile.getJarEntry treats
        // "config.yml" and "/config.yml" differently (the latter
        // never matches), so normalise here.
        String normalised = resourcePath.startsWith("/")
                ? resourcePath.substring(1)
                : resourcePath;
        try {
            URL url = owner.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return null;
            if (!"file".equals(url.getProtocol())) return null;
            File file = new File(url.toURI());
            byte[] data = null;
            if (file.isFile()) {
                try (JarFile jar = new JarFile(file)) {
                    JarEntry entry = jar.getJarEntry(normalised);
                    if (entry != null) {
                        // Copy the entry into memory BEFORE the JarFile
                        // is closed by this try-with-resources block;
                        // a stream handed out here would otherwise be
                        // backed by a closed JAR and read zero bytes.
                        try (InputStream in = jar.getInputStream(entry)) {
                            data = readAll(in);
                        }
                    }
                }
            } else if (file.isDirectory()) {
                // Dev / IDE layout: classes are unpacked on disk.
                File resourceFile = new File(file, normalised);
                if (resourceFile.isFile()) {
                    try (InputStream in = new FileInputStream(resourceFile)) {
                        data = readAll(in);
                    }
                }
            }
            return data == null ? null : new java.io.ByteArrayInputStream(data);
        } catch (Exception ignored) {
            // Resource not packaged with this class, or the code
            // source location is unreadable. Either way the caller
            // treats it as "no default available" — safer than
            // throwing and aborting startup because of a malformed
            // resource on disk.
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
