package link.star_dust.MinerTrack.common;

import java.util.UUID;

/**
 * Platform-agnostic command operations.
 *
 * <p>Follows the LuckPerms {@code Sender} abstraction pattern: a single
 * interface that each platform implements using its native API. The core
 * command layer never touches platform types directly.
 */
public interface CommandBridge {
    void dispatchCommand(String command);
    boolean isPlayer();
    boolean isConsole();
    Object getSender();

    // ── Messaging ──────────────────────────────────────────────────

    /**
     * Send an informational message to the command source.
     * On Minecraft platforms this corresponds to {@code sendFeedback}
     * / {@code sendSuccess} (shows in chat for players).
     */
    void sendMessage(String message);

    /**
     * Send a success-style message. On Fabric this routes through
     * {@code CommandSourceStack.sendSuccess(Supplier{@literal <Text>}, boolean)}
     * so the message appears as green/white chat text rather than a
     * system message or console line.
     */
    default void sendSuccess(String message) {
        sendMessage(message);
    }

    /**
     * Send a failure/error-style message. On Fabric this routes through
     * {@code CommandSourceStack.sendFailure(Text)} so the message
     * appears as red chat text.
     */
    default void sendFailure(String message) {
        sendMessage(message);
    }

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