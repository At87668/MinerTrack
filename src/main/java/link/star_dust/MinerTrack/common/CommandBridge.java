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

    // Verbose toggle (player or console).
    // Returns the new verbose state after the toggle:
    //   true  -> verbose is now ENABLED for this sender
    //   false -> verbose is now DISABLED for this sender
    // The caller is responsible for sending the corresponding
    // `verbose-enable` / `verbose-disable` message (via LanguageBridge)
    // so the wording stays in the language file rather than being
    // hard-coded in each platform bridge.
    boolean toggleVerbose();

    // Permission checks
    boolean hasPermission(String node);
    boolean hasPermissionForPlayer(UUID playerId, String node);
}