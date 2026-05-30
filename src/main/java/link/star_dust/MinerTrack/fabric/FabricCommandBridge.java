package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;

import java.util.UUID;

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

    @Override
    public void sendMessage(String message) {
        // TODO: implement via Fabric API
    }

    @Override
    public void sendMessageToPlayer(UUID playerId, String message) {
        // TODO: implement via Fabric API
    }

    @Override
    public void sendMessageToConsole(String message) {
        // TODO: implement via Fabric API
    }

    @Override
    public void toggleVerbose() {
        // TODO: implement via Fabric API
    }

    @Override
    public boolean hasPermission(String node) {
        return false;
    }

    @Override
    public boolean hasPermissionForPlayer(UUID playerId, String node) {
        return false;
    }
}