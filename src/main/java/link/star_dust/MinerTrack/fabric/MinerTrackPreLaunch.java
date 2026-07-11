package link.star_dust.MinerTrack.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * PreLaunch entrypoint that downloads and injects Mojang's official mappings
 * into Fabric Loader's mapping system before any reflection code runs.
 *
 * <p>For Minecraft versions 1.18–1.21.x, this downloads the ProGuard mappings
 * from Mojang's metadata API, converts them to Tiny v2 format, caches them at
 * {@code <game_dir>/cache/minertrack/mojmap_<version>.tiny}, and injects them
 * under the {@code "mojmap"} namespace.
 *
 * <p>For Minecraft 26.x and later, the server jar ships unobfuscated so no
 * mapping download or injection is performed.
 */
public class MinerTrackPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = Logger.getLogger("MinerTrack/PreLaunch");

    @Override
    public void onPreLaunch() {
        String mcVersion = InternalMappingResolver.getMinecraftVersion();

        if (!InternalMappingResolver.isMojmapRequired(mcVersion)) {
            LOGGER.info("Minecraft " + mcVersion
                    + " does not require Mojmap resolution — skipping mapping injection.");
            return;
        }

        LOGGER.info("Initializing Mojmap resolver for Minecraft " + mcVersion);
        InternalMappingResolver resolver = new InternalMappingResolver(
                FabricLoader.getInstance().getGameDir(), mcVersion);

        try {
            resolver.loadAndInject();
            LOGGER.info("Mojmap mappings injected successfully for " + mcVersion);
        } catch (IOException e) {
            LOGGER.warning("Failed to download/inject Mojmap mappings for "
                    + mcVersion + ": " + e.getMessage()
                    + " — reflection will fall back to intermediary-based resolution.");
        }
    }
}