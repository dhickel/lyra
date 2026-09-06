package io.mindspice.lyra.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Exact contract of a compiler-owned scalar storage accessor. Not a public source export. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LyraSessionBinding {
    long id();
    long storageIdentity();
    String name();
    String type();
    boolean mutable();
}
