package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.NotifyBridge;

public class FabricNotifyBridge implements NotifyBridge {
    @Override
    public void notify(String message) {
        // TODO: implement via Fabric logging
    }

    @Override
    public void notifyRaw(String message) {
        // TODO
    }

    @Override
    public boolean isVerboseEnabled(Object player) {
        return false;
    }

    @Override
    public void setVerboseEnabled(Object player, boolean enabled) {
        // TODO
    }

    @Override
    public boolean isVerboseConsole() {
        return false;
    }

    @Override
    public void setVerboseConsole(boolean enabled) {
        // TODO
    }
}