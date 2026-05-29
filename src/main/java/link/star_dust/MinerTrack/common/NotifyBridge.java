package link.star_dust.MinerTrack.common;

/**
 * Platform-agnostic notification operations.
 */
public interface NotifyBridge {
    void notify(String message);
    void notifyRaw(String message);
    boolean isVerboseEnabled(Object player);
    void setVerboseEnabled(Object player, boolean enabled);
    boolean isVerboseConsole();
    void setVerboseConsole(boolean enabled);
}