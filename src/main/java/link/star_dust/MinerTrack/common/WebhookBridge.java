package link.star_dust.MinerTrack.common;

/**
 * Platform-agnostic webhook operations.
 */
public interface WebhookBridge {
    void sendMessage(String content);
    void sendEmbed(Object embed);
    boolean isEnabled();
    int getColor();
    String getTitle();
    java.util.List<String> getText();
    int getVlRequired();
}