package link.star_dust.MinerTrack.common;

import java.util.UUID;

/**
 * Platform-agnostic command operations.
 */
public interface CommandBridge {
    void dispatchCommand(String command);
    boolean isPlayer();
    boolean isConsole();
    Object getSender();

    // Messaging
    void sendMessage(String message);
    void sendMessageToPlayer(UUID playerId, String message);
    void sendMessageToConsole(String message);

    // Verbose toggle (player or console)
    void toggleVerbose();

    // Permission checks
    boolean hasPermission(String node);
    boolean hasPermissionForPlayer(UUID playerId, String node);
}