package link.star_dust.MinerTrack.common;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ViolationBridge {
    boolean isLogFileEnabled();
    String getLogFormat();
    void appendLogLine(String line);
    void runConsoleCommand(String command);
    Set<UUID> getVerbosePlayers();
    boolean isVerboseConsoleEnabled();
    void sendMessageToPlayer(UUID playerId, String message);
    boolean isWebHookEnabled();
    int getWebHookVLRequired();
    void sendWebhook(UUID playerId, String oreType, int minedVeins, int oreCount, CommonLocation location);
    Map<String,Object> getConfigSection(String path);
    Object getConfig(String path);
    String getPrefixedMessage(String key);
    File getDataFolder();
}
