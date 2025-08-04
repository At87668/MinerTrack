/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/hooks/CustomJsonWebHook.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668, Zhang12334
 * 
 * DON'T REMOVE THIS
**/

package link.star_dust.MinerTrack.hooks;

import link.star_dust.MinerTrack.MinerTrack;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.bukkit.Bukkit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.nio.charset.StandardCharsets;

public class CustomJsonWebHook {
    private final MinerTrack plugin;
    private final String webHookUrl;
    private final String jsonFormat;

    public CustomJsonWebHook(MinerTrack plugin, String webHookUrl, String jsonFormat) {
        this.plugin = plugin;
        this.webHookUrl = webHookUrl;
        this.jsonFormat = jsonFormat;
    }

    public void sendMessage(Map<String, String> placeholders) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(webHookUrl);
                post.setHeader("Content-Type", "application/json; charset=UTF-8");

                // Add timestamp
                placeholders.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

                // Replace holders
                String jsonPayload = jsonFormat;
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    jsonPayload = jsonPayload.replace("%" + entry.getKey() + "%", entry.getValue());
                }

                post.setEntity(new StringEntity(jsonPayload, StandardCharsets.UTF_8));

                try (CloseableHttpResponse response = httpClient.execute(post)) {
                    int statusCode = response.getCode();
                    if (statusCode != 200 && statusCode != 204) {
                        plugin.getLogger().warning("Failed to send custom JSON webhook, Response Code: " + statusCode);
                    }
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Error while sending custom JSON webhook: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
} 