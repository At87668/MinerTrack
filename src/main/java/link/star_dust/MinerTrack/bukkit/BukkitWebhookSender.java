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

package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;
import org.bukkit.Bukkit;

import java.nio.charset.StandardCharsets;

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
                    // Read Discord's response body so the operator can
                    // see WHY the webhook was rejected. Discord returns
                    // a JSON body with a {code, message} field on every
                    // 4xx; printing it makes a 400 "title too long" or
                    // "invalid JSON" diagnosable from the server log
                    // alone. We cap the read to 1 KiB so a misbehaving
                    // proxy cannot stall the worker thread.
                    String body = readBodyCapped(response, 1024);
                    if (body.isEmpty()) {
                        adapter.info("Webhook response code: " + code);
                    } else {
                        adapter.info("Webhook response code: " + code + " body: " + body);
                    }
                }
            }
        } catch (Exception e) {
            adapter.info("Webhook error: " + e.getMessage());
        }
    }

    /**
     * Read up to {@code maxBytes} from {@code response}'s entity and
     * return it as a UTF-8 string. Returns an empty string when the
     * response has no body or the read fails for any reason — the
     * caller treats "no body" and "real failure" the same way (just
     * log the status code).
     */
    private static String readBodyCapped(org.apache.hc.core5.http.ClassicHttpResponse response, int maxBytes) {
        var entity = response.getEntity();
        if (entity == null) return "";
        try (var in = entity.getContent()) {
            byte[] buf = new byte[maxBytes];
            int total = 0;
            int n;
            while (total < maxBytes && (n = in.read(buf, total, maxBytes - total)) != -1) {
                total += n;
            }
            if (total == 0) return "";
            return new String(buf, 0, total, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void executeAsync(Runnable task) {
        // Use the adapter's plugin context when scheduling async tasks.
        Object p = adapter.getPlugin();
        if (p instanceof org.bukkit.plugin.Plugin plugin) {
            // Folia removed Bukkit.getScheduler().runTaskAsynchronously
            // (it throws UnsupportedOperationException) but ships
            // Bukkit.getAsyncScheduler() (a Paper async scheduler
            // available on both Paper and Folia). Use it when the
            // server is Folia; otherwise fall back to the classic
            // BukkitScheduler.runTaskAsynchronously on Paper/Spigot.
            if (isFoliaServer()) {
                try {
                    // Bukkit.getAsyncScheduler().runNow(Plugin, Consumer)
                    // The method is on the async-scheduler interface
                    // returned by Bukkit#getAsyncScheduler(). We
                    // resolve the scheduler reflectively so we don't
                    // have to bump the compileOnly Folia/Paper
                    // dependency set just for one call.
                    Object asyncScheduler = Bukkit.class
                        .getMethod("getAsyncScheduler")
                        .invoke(null);
                    Class<?> consumerCls = Class.forName("java.util.function.Consumer");
                    asyncScheduler.getClass()
                        .getMethod("runNow", org.bukkit.plugin.Plugin.class, consumerCls)
                        .invoke(asyncScheduler, plugin, (java.util.function.Consumer<Object>) t -> task.run());
                    return;
                } catch (Throwable reflectionFailure) {
                    // Async scheduler is missing on this server build
                    // (e.g. a Paper fork that pre-dates the
                    // Bukkit#getAsyncScheduler addition). Drop through
                    // to the daemon-thread fallback so the HTTP POST
                    // still happens off the main thread.
                }
            }
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
                return;
            } catch (Throwable unsupported) {
                // Folia / server that disabled the legacy async
                // scheduler. Fall through to the daemon-thread path.
            }
        }
        // Non-Bukkit or unsupported-server fallback: run on a plain
        // daemon thread. We deliberately use a fresh thread per
        // dispatch (not a shared executor) because the task is rare
        // (only fires on a VL increase) and a shared executor would
        // keep a non-daemon worker alive past plugin shutdown.
        Thread t = new Thread(task, "MinerTrack-Webhook");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Cached Folia detection result. The check is the same one
     * {@code FoliaCheck} / {@code BukkitPlatform#isFolia} use: if the
     * Paper Folia region scheduler class is on the classpath, this is
     * a Folia server. Cached so we don't pay the reflection cost on
     * every webhook dispatch.
     */
    private static volatile Boolean foliaCached;
    private static boolean isFoliaServer() {
        Boolean cached = foliaCached;
        if (cached != null) return cached;
        boolean result;
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            result = true;
        } catch (ClassNotFoundException e) {
            result = false;
        }
        foliaCached = result;
        return result;
    }
}
