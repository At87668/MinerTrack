package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.WebhookBridge;
import link.star_dust.MinerTrack.common.PluginAdapter;
import java.util.Collections;
import java.util.List;

/**
 * Minimal Bukkit-side WebhookBridge implementation.
 * This class intentionally avoids referencing legacy code. It provides
 * simple defaults and delegates where possible to a PluginAdapter.
 */
public class BukkitWebhookBridge implements WebhookBridge {
    private final PluginAdapter adapter;

    public BukkitWebhookBridge(PluginAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void sendMessage(String content) {
        // No-op here; core should use CoreWebhookManager + WebhookSenderBridge for actual sends.
        adapter.info("Webhook sendMessage: " + content);
    }

    @Override
    public void sendEmbed(Object embed) {
        adapter.info("Webhook sendEmbed: " + String.valueOf(embed));
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public int getColor() {
        return 0x000000;
    }

    @Override
    public String getTitle() {
        return "";
    }

    @Override
    public List<String> getText() {
        return Collections.emptyList();
    }

    @Override
    public int getVlRequired() {
        return Integer.MAX_VALUE;
    }
}