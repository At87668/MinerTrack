package link.star_dust.MinerTrack.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.util.logging.Logger;

/**
 * PreLaunch entrypoint for MinerTrack Fabric support.
 *
 * <p>Since Fabric Loader remaps the Minecraft JAR to intermediary names at
 * startup, and intermediary names are the same across all Minecraft versions,
 * no mapping download or injection is needed. Class name redirects are
 * hardcoded in {@link FabricReflection#tryMcMigration(String)} using the
 * cross-version stable {@code net.minecraft.class_NNNN} names.
 */
public class MinerTrackPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = Logger.getLogger("MinerTrack/PreLaunch");

    @Override
    public void onPreLaunch() {
        String mcVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        LOGGER.info("MinerTrack pre-launch initialized for Minecraft " + mcVersion
                + " — class redirection is handled via hardcoded intermediary mappings.");
    }
}