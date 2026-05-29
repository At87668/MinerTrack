package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.WebhookBridge;

import java.util.List;

public class FabricWebhookBridge implements WebhookBridge {
    @Override
    public void sendMessage(String content) {
        // TODO: implement HTTP client for Discord webhook
    }

    @Override
    public void sendEmbed(Object embed) {
        // TODO
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public int getColor() {
        return 0xFF5733;
    }

    @Override
    public String getTitle() {
        return "";
    }

    @Override
    public List<String> getText() {
        return List.of();
    }

    @Override
    public int getVlRequired() {
        return 0;
    }
}