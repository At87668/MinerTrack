package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;
import org.bukkit.Bukkit;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Bukkit platform implementation of {@link WebhookEngine.Sender}.
 *
 * <p>Routes HTTP POSTs through the Bukkit async scheduler (or, on
 * Folia, a daemon thread, since {@link Bukkit#getScheduler()} is not
 * available there). The HTTP transport itself is Apache HttpClient 5,
 * which is already a transitive dependency of the plugin.
 *
 * <p>The class is intentionally tiny: it does <em>not</em> own any
 * configuration, placeholder-substitution logic, or JSON rendering.
 * All of that lives in {@link WebhookEngine} so the same engine code
 * path works on Fabric / non-Bukkit platforms with their own sender.
 */
public class BukkitWebhookSender implements WebhookEngine.Sender {
    private final PluginAdapter adapter;

    public BukkitWebhookSender(PluginAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void sendAsync(String url, String payload) {
        if (url == null || url.isEmpty()) return;
        if (payload == null) return;
        executeAsync(() -> doPost(url, payload));
    }

    @Override
    public void sendAsync(String url, Map<String, String> placeholders, String jsonFormat) {
        if (url == null || url.isEmpty()) return;
        if (jsonFormat == null || jsonFormat.isEmpty()) return;
        // Inlined placeholder substitution matching WebhookEngine.substitute.
        // Kept here so the sender does not depend on the engine instance
        // (and so the engine stays a pure formatter with no I/O knowledge).
        String payload = substitute(jsonFormat, placeholders);
        executeAsync(() -> doPost(url, payload));
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
                    adapter.info("Webhook response code: " + code);
                }
            }
        } catch (Exception e) {
            adapter.info("Webhook error: " + e.getMessage());
        }
    }

    private void executeAsync(Runnable task) {
        // Use the adapter's plugin context when scheduling async tasks.
        Object p = adapter.getPlugin();
        if (p instanceof org.bukkit.plugin.Plugin) {
            Bukkit.getScheduler().runTaskAsynchronously((org.bukkit.plugin.Plugin) p, task);
        } else {
            // Folia / non-Bukkit fallback: run on a plain daemon thread.
            Thread t = new Thread(task, "MinerTrack-Webhook");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Mirrors {@code WebhookEngine.substitute} — kept in sync by
     * comment so the sender does not need a back-reference to the
     * engine instance.
     */
    static String substitute(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '%' && i + 1 < n) {
                int end = template.indexOf('%', i + 1);
                if (end > i + 1) {
                    String key = template.substring(i + 1, end);
                    String value = placeholders.get(key);
                    if (value != null) {
                        out.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
