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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Open a resource packaged inside MinerTrack's own mod file on Forge.
 *
 * <p>On Forge 26.x the mod is loaded through ModLauncher / SecureJar, so the
 * adapter class's code source is not a plain {@code file:} URL and
 * {@link ModResourceLoader} (which walks the protection domain) cannot locate
 * the JAR. When that happens {@code saveResource} silently writes nothing and
 * the first startup crashes with {@code FileNotFoundException} on
 * {@code config.yml}.
 *
 * <p>This helper goes through Forge's own mod-file API instead:
 * {@code ModList.getModFileById("minertrack").getFile().findResource(path)}.
 * That resolves the resource inside the mod's SecureJar regardless of how the
 * classloader exposes the code source. If the Forge API is unavailable (e.g.
 * a hybrid server that does not expose it), it falls back to the
 * protection-domain loader.
 */
final class ForgeResourceLoader {

    private ForgeResourceLoader() {}

    /**
     * @param resourcePath path relative to the mod file root (forward slashes,
     *                     leading slash optional)
     * @return an open stream with the resource content, or {@code null} if the
     *         resource is not packaged with the mod
     */
    static InputStream open(String resourcePath) {
        if (resourcePath == null) return null;
        String normalised = resourcePath.startsWith("/")
                ? resourcePath.substring(1)
                : resourcePath;

        // --- Forge mod-file API (ModList -> IModFileInfo -> IModFile) ---
        try {
            Class<?> modListCls = ForgeReflection.forgeClass("net.minecraftforge.fml.ModList");
            Class<?> modFileInfoCls = ForgeReflection.forgeClass("net.minecraftforge.forgespi.language.IModFileInfo");
            Class<?> modFileCls = ForgeReflection.forgeClass("net.minecraftforge.forgespi.locating.IModFile");
            if (modListCls != null && modFileInfoCls != null && modFileCls != null) {
                Object modFileInfo = modListCls.getMethod("getModFileById", String.class)
                        .invoke(null, "minertrack");
                if (modFileInfo != null) {
                    Object modFile = modFileInfoCls.getMethod("getFile").invoke(modFileInfo);
                    if (modFile != null) {
                        Object pathObj = modFileCls.getMethod("findResource", String[].class)
                                .invoke(modFile, (Object) new String[]{normalised});
                        if (pathObj instanceof Path) {
                            Path p = (Path) pathObj;
                            if (Files.isRegularFile(p)) return Files.newInputStream(p);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Fall through to the protection-domain loader below.
        }

        // --- Fallback: protection-domain lookup (dev / non-SecureJar setups) ---
        return ModResourceLoader.open(ForgeAdapter.class, normalised);
    }
}
