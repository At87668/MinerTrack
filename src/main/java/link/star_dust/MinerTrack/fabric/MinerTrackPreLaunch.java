package link.star_dust.MinerTrack.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * PreLaunch entrypoint that initializes the Mojang class redirector and
 * the Mojmap mapping resolver before any reflection code runs.
 *
 * <p>For Minecraft versions 1.18–1.21.x:
 * <ol>
 *   <li>Initializes {@link MojangClassRedirector} — downloads and parses
 *       Mojang's ProGuard mappings into a simple class map
 *       ({@code mojang → official}), then at reflection time uses Fabric's
 *       native {@code official → intermediary} resolver for a reliable
 *       two-step resolution chain. This completely bypasses the fragile
 *       Fabric Loader mojmap namespace injection.</li>
 *   <li>Also initializes {@link InternalMappingResolver} as a fallback for
 *       method and field name resolution (used by
 *       {@link FabricReflection#unmapMojmapMethodName} and
 *       {@link FabricReflection#resolveMojmapFieldName}).</li>
 * </ol>
 *
 * <p>For Minecraft 26.x and later, the server jar ships unobfuscated so no
 * mapping download or injection is performed.
 */
public class MinerTrackPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = Logger.getLogger("MinerTrack/PreLaunch");

    @Override
    public void onPreLaunch() {
        String mcVersion = MojangClassRedirector.getMinecraftVersion();

        if (!MojangClassRedirector.isRedirectRequired(mcVersion)) {
            LOGGER.info("Minecraft " + mcVersion
                    + " does not require class redirection — skipping mapping downloads.");
            return;
        }

        // ── 1. Initialize MojangClassRedirector (primary class resolution) ──
        LOGGER.info("Initializing Mojang class redirector for Minecraft " + mcVersion);
        MojangClassRedirector redirector = new MojangClassRedirector(
                FabricLoader.getInstance().getGameDir(), mcVersion);
        try {
            redirector.load();
            FabricReflection.setClassRedirector(redirector);
            LOGGER.info("Mojang class redirector initialized successfully for " + mcVersion);
        } catch (IOException e) {
            LOGGER.warning("Failed to initialize class redirector for "
                    + mcVersion + ": " + e.getMessage()
                    + " — reflection will fall back to legacy resolution.");
        }

        // ── 2. Initialize InternalMappingResolver (fallback: method/field resolution) ──
        LOGGER.info("Initializing Mojmap resolver for Minecraft " + mcVersion);
        InternalMappingResolver resolver = new InternalMappingResolver(
                FabricLoader.getInstance().getGameDir(), mcVersion);

        try {
            resolver.loadAndInject();
            LOGGER.info("Mojmap mappings injected successfully for " + mcVersion);
        } catch (IOException e) {
            LOGGER.warning("Failed to download/inject Mojmap mappings for "
                    + mcVersion + ": " + e.getMessage()
                    + " — method/field resolution will fall back to name-only lookup.");
        }
    }
}