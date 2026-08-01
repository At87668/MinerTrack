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

import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Diagnostic test: scans the current runtime classpath for all
 * {@code net.minecraftforge.*} and {@code net.minecraft.*} classes and writes
 * them to {@code class.txt}.
 *
 * <p>This is used to diagnose why {@code ForgeReflection.forgeClass(...)}
 * returns {@code null} for classes like
 * {@code net.minecraftforge.event.server.ServerStartingEvent} on a live Forge
 * server. Run this test inside the Forge server environment (or with the Forge
 * JARs on the classpath) and inspect {@code class.txt} to see which classes are
 * actually visible to the runtime classloader.</p>
 */
class ForgeClassScannerTest {

    @Test
    void scanForgeAndMinecraftClasses() throws Exception {
        TreeSet<String> classes = new TreeSet<>();

        // 1. Scan the classpath (directories and JARs) for .class entries.
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            File f = new File(entry);
            if (f.isDirectory()) {
                scanDirectory(f, f, classes);
            } else if (f.isFile() && f.getName().endsWith(".jar")) {
                scanJar(f, classes);
            }
        }

        // 2. Also scan the thread context classloader's URLs (covers Forge's
        //    mod-launcher classpath which may not be in java.class.path).
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader) ctx).getURLs()) {
                File f = new File(url.getPath());
                if (f.isDirectory()) {
                    scanDirectory(f, f, classes);
                } else if (f.isFile() && f.getName().endsWith(".jar")) {
                    scanJar(f, classes);
                }
            }
        }

        // Filter to the packages of interest.
        List<String> filtered = new ArrayList<>();
        for (String c : classes) {
            if (c.startsWith("net.minecraftforge.") || c.startsWith("net.minecraft.")) {
                filtered.add(c);
            }
        }

        // Write to class.txt in the working directory.
        Path out = Paths.get("class.txt");
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (String c : filtered) {
                w.write(c);
                w.newLine();
            }
        }

        System.out.println("[MinerTrack:ClassScanner] Wrote " + filtered.size()
                + " net.minecraftforge.* / net.minecraft.* classes to " + out.toAbsolutePath());
    }

    private static void scanDirectory(File root, File dir, TreeSet<String> classes) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(root, f, classes);
            } else if (f.getName().endsWith(".class")) {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                // rel looks like "net/minecraft/.../Foo.class"
                String cls = rel.replace('/', '.').replace('\\', '.');
                if (cls.endsWith(".class")) {
                    cls = cls.substring(0, cls.length() - ".class".length());
                }
                classes.add(cls);
            }
        }
    }

    private static void scanJar(File jarFile, TreeSet<String> classes) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (name.endsWith(".class")) {
                    String cls = name.replace('/', '.').substring(0, name.length() - ".class".length());
                    classes.add(cls);
                }
            }
        } catch (IOException ignored) {
            // Skip unreadable JARs.
        }
    }
}
