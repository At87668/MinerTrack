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

package link.star_dust.MinerTrack.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection performance accelerator shared by all modding platforms
 * (Fabric / Forge / NeoForge).
 *
 * <p>Plain {@link Method#invoke} / {@link Field#get} go through the JVM's
 * reflective machinery on every call, which the JIT cannot fully inline.
 * This class instead compiles each {@link Method} / {@link Field} into a
 * {@link MethodHandle} once, then invokes it through a single uniform
 * {@code (Object, Object[]) -> Object} signature.  After warm-up the JIT
 * inlines the handle chain, so repeated calls run at near-direct-invocation
 * speed — the Java equivalent of compiling a C# expression tree into a
 * delegate (the {@code DynamicMethodExecutor} pattern).
 *
 * <p>Handles are cached per {@link Method} / {@link Field} object, so the
 * (already cached) reflective members are compiled exactly once.  If a
 * handle cannot be built (e.g. access is denied), the methods return
 * {@code null} and callers fall back to plain reflection.
 *
 * <p>This class is platform-agnostic and depends only on {@code java.lang.invoke}.
 */
public final class FastReflection {

    private FastReflection() {}

    /** Sentinel for "no handle available" (ConcurrentHashMap forbids null). */
    private static final MethodHandle NOT_FOUND = MethodHandles.constant(Object.class, null);

    private static final ConcurrentHashMap<Method, MethodHandle> METHOD_HANDLES = new ConcurrentHashMap<>(128);
    private static final ConcurrentHashMap<Field, MethodHandle>  FIELD_GETTERS  = new ConcurrentHashMap<>(64);

    // ==================================================================
    // Method invocation
    // ==================================================================

    /**
     * Invoke {@code method} on {@code instance} (or statically when
     * {@code instance == null}) with {@code args}, returning the boxed
     * result (or {@code null} for {@code void} methods).
     *
     * @return the invocation result, or {@code null} if the handle could
     *         not be built or the invocation failed
     */
    public static Object invoke(Method method, Object instance, Object[] args) {
        MethodHandle mh = methodHandle(method);
        if (mh == null) return null;
        try {
            return mh.invokeExact(instance, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Invoke a static {@code method} with {@code args}.
     *
     * @return the invocation result, or {@code null} on failure
     */
    public static Object invokeStatic(Method method, Object[] args) {
        return invoke(method, null, args);
    }

    // ==================================================================
    // Field access
    // ==================================================================

    /**
     * Read {@code field} from {@code instance} (or a static field when
     * {@code instance == null}), returning the boxed value.
     *
     * @return the field value, or {@code null} if the handle could not be
     *         built or the read failed
     */
    public static Object get(Field field, Object instance) {
        MethodHandle mh = getterHandle(field);
        if (mh == null) return null;
        try {
            return mh.invokeExact(instance);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // Handle construction & caching
    // ==================================================================

    private static MethodHandle methodHandle(Method method) {
        if (method == null) return null;
        MethodHandle cached = METHOD_HANDLES.get(method);
        if (cached != null) return cached == NOT_FOUND ? null : cached;

        MethodHandle mh = buildMethodHandle(method);
        if (mh == null) {
            METHOD_HANDLES.put(method, NOT_FOUND);
            return null;
        }
        METHOD_HANDLES.put(method, mh);
        return mh;
    }

    private static MethodHandle getterHandle(Field field) {
        if (field == null) return null;
        MethodHandle cached = FIELD_GETTERS.get(field);
        if (cached != null) return cached == NOT_FOUND ? null : cached;

        MethodHandle mh = buildGetterHandle(field);
        if (mh == null) {
            FIELD_GETTERS.put(field, NOT_FOUND);
            return null;
        }
        FIELD_GETTERS.put(field, mh);
        return mh;
    }

    /**
     * Build a uniform {@code (Object, Object[]) -> Object} handle for a
     * method.  For instance methods the first {@code Object} is the
     * receiver; for static methods it is ignored (dropped).
     */
    private static MethodHandle buildMethodHandle(Method method) {
        try {
            MethodHandles.Lookup lookup = lookupFor(method.getDeclaringClass());
            MethodHandle mh = lookup.unreflect(method);

            int paramCount = method.getParameterCount();
            // Spread the Object[] into individual arguments.
            mh = mh.asSpreader(Object[].class, paramCount);
            // Unify to (Object, Object[]) -> Object.  void → Object returns null.
            MethodType target = MethodType.methodType(Object.class, Object.class, Object[].class);
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                // Static handle has no receiver; drop a leading ignored Object.
                mh = MethodHandles.dropArguments(mh, 0, Object.class);
            }
            return mh.asType(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Build a uniform {@code (Object) -> Object} getter handle for a field.
     * For static fields the receiver is ignored (dropped).
     */
    private static MethodHandle buildGetterHandle(Field field) {
        try {
            MethodHandles.Lookup lookup = lookupFor(field.getDeclaringClass());
            MethodHandle mh = lookup.unreflectGetter(field);
            MethodType target = MethodType.methodType(Object.class, Object.class);
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                mh = MethodHandles.dropArguments(mh, 0, Object.class);
            }
            return mh.asType(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Obtain a lookup that can access private members of {@code cls}.
     * Falls back to the public lookup when {@code privateLookupIn} is
     * unavailable or denied (e.g. strongly encapsulated modules).
     */
    private static MethodHandles.Lookup lookupFor(Class<?> cls) {
        try {
            return MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
        } catch (Throwable t) {
            return MethodHandles.lookup();
        }
    }
}
