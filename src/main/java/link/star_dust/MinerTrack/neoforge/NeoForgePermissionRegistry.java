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

package link.star_dust.MinerTrack.neoforge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers all {@code minertrack.*} permission nodes with the NeoForge native
 * permission system (NeoForge 1.20.4+ / 26.x).
 *
 * <p>NeoForge's {@code PermissionAPI.getPermission(player, node)} throws
 * {@code UnregisteredPermissionException} for any node that was never
 * registered via {@code PermissionGatherEvent.Nodes}. The bridge can only rely
 * on the native API if the nodes actually exist, so this registry creates a
 * {@code PermissionNode} (BOOLEAN type, default resolver = {@code false}) for
 * every MinerTrack permission and registers them when the gather event fires.
 *
 * <p>Called reflectively to avoid compile-time coupling with the NeoForge
 * classes. The gather event fires during {@code MinecraftServer} construction
 * (from {@code PermissionAPI.initializePermissionAPI()}), which happens BEFORE
 * {@code ServerStartingEvent} — so {@link #registerGatherListener()} must be
 * invoked from the mod constructor, exactly like command registration.
 */
final class NeoForgePermissionRegistry {

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

    private NeoForgePermissionRegistry() {}

    /**
     * Register the {@code PermissionGatherEvent.Nodes} listener. Must be called
     * from the NeoForgeMod constructor (the event fires during MinecraftServer
     * construction, before ServerStartingEvent).
     */
    static void registerGatherListener() {
        if (listenerRegistered) return;
        listenerRegistered = true;
        NeoForgeReflection.registerEventListener(
            NeoForgeReflection.getMainEventBus(),
            NeoForgeReflection.neoClass("net.neoforged.neoforge.server.permission.events.PermissionGatherEvent$Nodes"),
            NeoForgePermissionRegistry::onGatherNodes);
    }

    /** Return the registered PermissionNode instance for a node name, or null. */
    static Object getNode(String nodeName) {
        return NODE_CACHE.get(nodeName);
    }

    private static void onGatherNodes(Object event) {
        try {
            Class<?> nodeCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.server.permission.nodes.PermissionNode");
            Class<?> typesCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.server.permission.nodes.PermissionTypes");
            Class<?> resolverCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.server.permission.nodes.PermissionNode$PermissionResolver");
            Class<?> dynKeyCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContextKey");
            if (nodeCls == null || typesCls == null || resolverCls == null || dynKeyCls == null) return;

            Object booleanType = typesCls.getField("BOOLEAN").get(null);

            // PermissionResolver: default value for every node is false.
            Object resolver = java.lang.reflect.Proxy.newProxyInstance(
                resolverCls.getClassLoader(),
                new Class<?>[]{resolverCls},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "resolve": return Boolean.FALSE;
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

            Object[] nodes = new Object[NODES.length];
            for (int i = 0; i < NODES.length; i++) {
                int dot = NODES[i].indexOf('.');
                String modId = NODES[i].substring(0, dot);
                String name = NODES[i].substring(dot + 1);
                Object node = ctor.newInstance(modId, name, booleanType, resolver, emptyDyns);
                NODE_CACHE.put(NODES[i], node);
                nodes[i] = node;
            }

            // event.addNodes(PermissionNode<?>... nodes)
            Method addNodes = event.getClass().getMethod("addNodes", nodes.getClass());
            addNodes.invoke(event, (Object) nodes);
        } catch (Throwable t) {
            // Permission system absent — native path is a no-op.
        }
    }
}
