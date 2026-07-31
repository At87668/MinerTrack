/*
 * COMPILE-TIME STUB — NOT SHIPPED IN THE JAR.
 *
 * This mirrors net.minecraftforge.fml.common.Mod so the @Mod annotation
 * can be used in ForgeMod.java without pulling in the full Forge JAR.
 * At runtime the server provides the real class.
 */
package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();
}
