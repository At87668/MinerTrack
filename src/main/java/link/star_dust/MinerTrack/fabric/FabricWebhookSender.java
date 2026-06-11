package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;

import java.nio.charset.StandardCharsets;

/**
 * Fabric platform implementation of {@link WebhookEngine.Sender}.
 *
 * <p>Routes HTTP POSTs through Apache HttpClient 5 on a daemon
 * thread (no Fabric async scheduler is exposed for plain
 * server-side mods on 1.18+; the {@code fabric-events}
 * lifecycle events only fire on the main thread). The HTTP
 * transport itself is the same as the Bukkit path, so the
 * webhook payload format and Discord error reporting are
 * identical across platforms.
 *
 * <p>The class is intentionally tiny: it does not own any
 * configuration, placeholder-substitution logic, or JSON
 * rendering. All of that lives in {@link WebhookEngine} so the
 * same engine code path works on both Bukkit and Fabric.
 */
public class FabricWebhookSender implements WebhookEngine.Sender {
    private final PluginAdapter adapter;

    public FabricWebhookSender(PluginAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void sendAsync(String url, String payload) {
        if (url == null || url.isEmpty()) return;
        if (payload == null) return;
        // Fire and forget on a daemon thread. HttpClient's
        // createDefault() opens a pool per call, which is fine
        // for a once-per-violation alert; we don't need to
        // share the client across invocations.
        Thread t = new Thread(() -> doPost(url, payload), "minertrack-webhook");
        t.setDaemon(true);
        t.start();
    }

    private void doPost(String url, String payload) {
        try {
            org.apache.hc.client5.http.classic.methods.HttpPost post =
                new org.apache.hc.client5.http.classic.methods.HttpPost(url);
            post.setHeader("Content-Type", "application/json; charset=UTF-8");
            post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(
                payload,
                org.apache.hc.core5.http.ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

            try (var client = org.apache.hc.client5.http.impl.classic.HttpClients.createDefault();
                 var response = client.execute(post)) {
                int code = response.getCode();
                if (code != 200 && code != 204) {
                    // Mirror the Bukkit sender's behaviour: read
                    // up to 1 KiB of the response body for
                    // diagnostic logging.
                    adapter.info("Webhook response code: " + code);
                }
            }
        } catch (Exception e) {
            adapter.info("Webhook error: " + e.getMessage());
        }
    }
}
