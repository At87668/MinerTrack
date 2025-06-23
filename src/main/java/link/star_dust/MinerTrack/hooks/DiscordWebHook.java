/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/hooks/DiscordWebHook.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668, Zhang12334
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack.hooks;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import link.star_dust.MinerTrack.MinerTrack;

import javax.net.ssl.*;
import java.security.cert.X509Certificate;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.bukkit.Bukkit;

public class DiscordWebHook {

    private final MinerTrack plugin;
    private final String webHookUrl;
    private final Gson gson = new Gson();

    public DiscordWebHook(MinerTrack plugin, String webHookUrl) {
        this.plugin = plugin;
        this.webHookUrl = webHookUrl;
    }

    /**
     * Sends a simple text message to the Discord WebHook.
     *
     * @param content The message content to send.
     */
    public void sendMessage(String content) {
        send(new Payload(content));
    }

    /**
     * Sends an embed message to the Discord WebHook.
     *
     * @param embed The embed payload to send.
     */
    public void sendEmbed(Embed embed) {
        send(new Payload(embed));
    }

    /**
     * Sends a payload to the Discord WebHook asynchronously.
     *
     * @param payload The payload to send.
     */
    private void send(Payload payload) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(webHookUrl);
                post.setHeader("Content-Type", "application/json");

                String jsonPayload = gson.toJson(payload);
                post.setEntity(new StringEntity(jsonPayload));

                try (CloseableHttpResponse response = httpClient.execute(post)) {
                    int statusCode = response.getCode();
                    if (statusCode != 200 && statusCode != 204) {
                        plugin.getLogger().warning("Failed to send message to Discord WebHook. Response Code: " + statusCode);
                    }
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Error while sending message to Discord WebHook: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Represents the payload structure for a WebHook message.
     */
    public static class Payload {
        @SerializedName("content")
        private final String content;

        @SerializedName("embeds")
        private final Embed[] embeds;

        public Payload(String content) {
            this.content = content;
            this.embeds = null;
        }

        public Payload(Embed embed) {
            this.content = null;
            this.embeds = new Embed[]{embed};
        }
    }

    /**
     * Represents an embed structure for Discord messages.
     */
    public static class Embed {
        @SerializedName("title")
        private final String title;

        @SerializedName("description")
        private final String description;

        @SerializedName("color")
        private final int color;

        public Embed(String title, String description, int color) {
            this.title = title;
            this.description = description;
            this.color = color;
        }
    }
}

