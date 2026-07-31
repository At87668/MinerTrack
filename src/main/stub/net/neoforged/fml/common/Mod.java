/*
 * COMPILE-TIME STUB — NOT SHIPPED IN THE JAR.
 *
 * This mirrors net.neoforged.fml.common.Mod so the @Mod annotation
 * can be used in NeoForgeMod.java without pulling in the full NeoForge JAR.
 * At runtime the server provides the real class.
 */
package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();
}
