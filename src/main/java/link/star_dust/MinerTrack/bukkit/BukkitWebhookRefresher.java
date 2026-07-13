package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.core.config.WebhookConfig;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;

/**
 * Tiny static hook so {@link BukkitAdapter#reloadConfig()} can ask the
 * platform to refresh the {@link WebhookEngine} without taking a
 * compile-time dependency on {@link BukkitPlatform} (which would cycle
 * through the adapter).
 *
 * <p>Wire-up: {@link BukkitPlatform#onEnable()} registers a refresher
 * that rebuilds the {@link WebhookConfig} from the violation manager's
 * latest merged {@code config.yml} and pushes the resulting
 * {@link WebhookEngine} back into the violation engine. Tests / Fabric
 * can leave the refresher as the no-op default and assemble their own
 * engine up front.
 */
public final class BukkitWebhookRefresher {
    private static volatile Refresher refresher = (vm, sender) -> {
        // Default no-op: the platform never registered a refresher, so we
        // fall back to rebuilding the engine with whatever config the
        // violation manager currently holds. This keeps reload working
        // even when the hook was not installed (e.g. in unit tests).
        WebhookConfig cfg = WebhookConfig.from(vm.getMainConfig());
        vm.setWebhookEngine(new WebhookEngine(cfg, sender));
    };

    private BukkitWebhookRefresher() {}

    public interface Refresher {
        /**
         * Rebuild the {@link WebhookEngine} and push it into the
         * violation manager. Called after every successful config
         * reload so edits to {@code DiscordWebHook.*} take effect
         * without a server restart.
         */
        void refresh(BukkitViolationManager violationManager, BukkitWebhookSender sender);
    }

    /** Replace the active refresher. Pass {@code null} to restore the default. */
    public static void set(Refresher r) {
        refresher = r != null ? r : (vm, sender) -> {
            WebhookConfig cfg = WebhookConfig.from(vm.getMainConfig());
            vm.setWebhookEngine(new WebhookEngine(cfg, sender));
        };
    }

    static void refresh(BukkitViolationManager vm, BukkitWebhookSender sender) {
        refresher.refresh(vm, sender);
    }
}
