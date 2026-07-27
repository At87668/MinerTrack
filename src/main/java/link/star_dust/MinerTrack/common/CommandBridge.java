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