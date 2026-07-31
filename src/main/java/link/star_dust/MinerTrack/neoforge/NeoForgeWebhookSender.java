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

package link.star_dust.MinerTrack.neoforge;

import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;

import java.nio.charset.StandardCharsets;

/** NeoForge Webhook Sender. */
public class NeoForgeWebhookSender implements WebhookEngine.Sender {
    private final PluginAdapter adapter;

    public NeoForgeWebhookSender(PluginAdapter adapter) { this.adapter = adapter; }

    @Override
    public void sendAsync(String url, String payload) {
        if (url == null || url.isEmpty()) return;
        if (payload == null) return;
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
                if (code != 200 && code != 204) adapter.info("Webhook response code: " + code);
            }
        } catch (Exception e) { adapter.info("Webhook error: " + e.getMessage()); }
    }
}
