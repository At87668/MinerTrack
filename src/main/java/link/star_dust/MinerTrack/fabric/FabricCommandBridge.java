package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;

public class FabricCommandBridge implements CommandBridge {
    private final Object source; // ServerCommandSource - Fabric API not available in Bukkit build

    public FabricCommandBridge(Object source) {
        this.source = source;
    }

    @Override
    public void dispatchCommand(String command) {
        // TODO: implement via Fabric command API
    }

    @Override
    public boolean isPlayer() {
        return false;
    }

    @Override
    public boolean isConsole() {
        return true;
    }

    @Override
    public Object getSender() {
        return source;
    }
}