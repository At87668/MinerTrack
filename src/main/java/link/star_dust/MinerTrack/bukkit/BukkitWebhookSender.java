package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CoreWebhookManager;
import link.star_dust.MinerTrack.common.PluginAdapter;
import org.bukkit.Bukkit;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Bukkit implementation of CoreWebhookManager.WebhookSenderBridge.
 * Handles async HTTP sending via Bukkit scheduler or plain thread (Folia-safe).
 */
public class BukkitWebhookSender implements CoreWebhookManager.WebhookSenderBridge {
    private final PluginAdapter adapter;

    public BukkitWebhookSender(PluginAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void sendAsync(String url, String jsonPayload) {
        if (url == null || url.isEmpty()) return;
        Runnable task = () -> {
            try {
                org.apache.hc.client5.http.classic.methods.HttpPost post =
                    new org.apache.hc.client5.http.classic.methods.HttpPost(url);
                post.setHeader("Content-Type", "application/json; charset=UTF-8");
                post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(
                    jsonPayload, org.apache.hc.core5.http.ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

                try (var client = org.apache.hc.client5.http.impl.classic.HttpClients.createDefault();
                     var response = client.execute(post)) {
                    int code = response.getCode();
                    if (code != 200 && code != 204) {
                        adapter.info("Webhook response code: " + code);
                    }
                }
            } catch (Exception e) {
                adapter.info("Webhook error: " + e.getMessage());
            }
        };
        executeAsync(task);
    }

    @Override
    public void sendAsync(String url, Map<String, String> placeholders, String jsonFormat) {
        // Custom JSON handled by core via placeholders - no direct CustomJsonWebHook reference
        if (url == null || url.isEmpty()) return;
        // Format and send custom JSON
        String jsonPayload = jsonFormat;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            jsonPayload = jsonPayload.replace("%" + e.getKey() + "%", e.getValue());
        }
        sendAsync(url, jsonPayload);
    }

    private void executeAsync(Runnable task) {
        // Use the adapter's plugin context when scheduling async tasks
        Object p = adapter.getPlugin();
        if (p instanceof org.bukkit.plugin.Plugin) {
            Bukkit.getScheduler().runTaskAsynchronously((org.bukkit.plugin.Plugin) p, task);
        } else {
            // fallback: run on a plain thread
            Thread t = new Thread(task, "MinerTrack-Webhook");
            t.setDaemon(true);
            t.start();
        }
    }
}
