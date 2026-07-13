package link.star_dust.MinerTrack.common;

import java.util.List;

public interface LanguageBridge {
    String getPrefixedMessage(String key);
    String getLogFormat();
    String applyColors(String message);
    String getPrefix();
    List<String> getHelpMessages();
    String getMessage(String path);
    String getColoredMessage(String path);
    boolean isKickBroadcastEnabled();
    String getKickFormat();
}
