package link.star_dust.MinerTrack.common;

/**
 * Platform-agnostic command operations.
 */
public interface CommandBridge {
    void dispatchCommand(String command);
    boolean isPlayer();
    boolean isConsole();
    Object getSender();
}