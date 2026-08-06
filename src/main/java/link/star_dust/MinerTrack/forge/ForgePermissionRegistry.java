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

package link.star_dust.MinerTrack.forge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers all {@code minertrack.*} permission nodes with the Forge native
 * permission system (Forge 1.19+).
 *
 * <p>Forge's {@code PermissionAPI.getPermission(player, node)} throws
 * {@code UnregisteredPermissionException} for any node that was never
 * registered via {@code PermissionGatherEvent.Nodes}. The bridge can only rely
 * on the native API if the nodes actually exist, so this registry creates a
 * {@code PermissionNode} (BOOLEAN type, default resolver = {@code false}) for
 * every MinerTrack permission and registers them when the gather event fires.
 *
 * <p>Called reflectively to avoid compile-time coupling with the Forge classes.
 * The gather event fires during {@code MinecraftServer} construction (from
 * {@code PermissionAPI.initializePermissionAPI()}), which happens BEFORE
 * {@code ServerStartingEvent} — so {@link #registerGatherListener()} must be
 * invoked from the mod constructor, exactly like command registration.
 */
final class ForgePermissionRegistry {

    /** Every MinerTrack permission node, registered as BOOLEAN (default false). */
    private static final String[] NODES = {
        "minertrack.help",
        "minertrack.sendnotify",
        "minertrack.notify",
        "minertrack.verbose",
        "minertrack.check",
        "minertrack.reset",
        "minertrack.kick",
        "minertrack.reload",
        "minertrack.checkupdate",
        "minertrack.logs",
        "minertrack.bypass"
    };

    /** nodeName -> created PermissionNode instance (kept for direct queries). */
    private static final Map<String, Object> NODE_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean listenerRegistered = false;

    private ForgePermissionRegistry() {}

    /**
     * Register the {@code PermissionGatherEvent.Nodes} listener. Must be called
     * from the ForgeMod constructor (the event fires during MinecraftServer
     * construction, before ServerStartingEvent).
     */
    static void registerGatherListener() {
        if (listenerRegistered) return;
        listenerRegistered = true;
        ForgeReflection.registerEventListener(
            ForgeReflection.getMainEventBus(),
            ForgeReflection.forgeClass("net.minecraftforge.server.permission.events.PermissionGatherEvent$Nodes"),
            ForgePermissionRegistry::onGatherNodes);
    }

    /** Return the registered PermissionNode instance for a node name, or null. */
    static Object getNode(String nodeName) {
        return NODE_CACHE.get(nodeName);
    }

    private static void onGatherNodes(Object event) {
        try {
            Class<?> nodeCls = ForgeReflection.forgeClass("net.minecraftforge.server.permission.nodes.PermissionNode");
            Class<?> typesCls = ForgeReflection.forgeClass("net.minecraftforge.server.permission.nodes.PermissionTypes");
            Class<?> resolverCls = ForgeReflection.forgeClass("net.minecraftforge.server.permission.nodes.PermissionNode$PermissionResolver");
            Class<?> dynKeyCls = ForgeReflection.forgeClass("net.minecraftforge.server.permission.nodes.PermissionDynamicContextKey");
            if (nodeCls == null || typesCls == null || resolverCls == null || dynKeyCls == null) return;

            Object booleanType = typesCls.getField("BOOLEAN").get(null);

            // PermissionResolver: the DefaultPermissionHandler (used when no
            // third-party permission plugin is installed) forwards every query
            // to this resolver, so it must NOT be hardcoded false — otherwise
            // every player is denied. Resolve to the vanilla op status instead:
            // an op player is granted, everyone else is denied. When a real
            // permission handler (e.g. LuckPerms-Forge) is active it ignores
            // this resolver and uses its own data.
            Object resolver = java.lang.reflect.Proxy.newProxyInstance(
                resolverCls.getClassLoader(),
                new Class<?>[]{resolverCls},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "resolve":
                            Object player = (args != null && args.length > 0) ? args[0] : null;
                            return player != null && ForgeCommandBridge.isPlayerOperator(player);
                        case "toString": return "MinerTrackDefaultResolver";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return null;
                    }
                });

            Object emptyDyns = java.lang.reflect.Array.newInstance(dynKeyCls, 0);
            // PermissionNode(String modID, String nodeName, PermissionType,
            //                PermissionResolver, PermissionDynamicContextKey...)
            Constructor<?> ctor = nodeCls.getConstructor(String.class, String.class,
                booleanType.getClass(), resolverCls, emptyDyns.getClass());

            // Build a real PermissionNode[] (NOT Object[]) so getMethod finds
            // addNodes(PermissionNode<?>...) via an exact array-type match.
            Object nodes = java.lang.reflect.Array.newInstance(nodeCls, NODES.length);
            for (int i = 0; i < NODES.length; i++) {
                int dot = NODES[i].indexOf('.');
                String modId = NODES[i].substring(0, dot);
                String name = NODES[i].substring(dot + 1);
                Object node = ctor.newInstance(modId, name, booleanType, resolver, emptyDyns);
                NODE_CACHE.put(NODES[i], node);
                java.lang.reflect.Array.set(nodes, i, node);
            }

            // event.addNodes(PermissionNode<?>... nodes)
            Method addNodes = event.getClass().getMethod("addNodes", nodes.getClass());
            addNodes.invoke(event, nodes);
            System.out.println("[MinerTrack:ForgePermissionRegistry] Registered " + NODES.length + " permission nodes: " + String.join(", ", NODES));
        } catch (Throwable t) {
            System.out.println("[MinerTrack:ForgePermissionRegistry] Failed to register permission nodes: " + t);
        }
    }
}
