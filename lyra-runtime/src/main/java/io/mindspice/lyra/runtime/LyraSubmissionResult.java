package io.mindspice.lyra.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compiler-owned exact type on the fixed, non-executing submission-result accessor. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LyraSubmissionResult {
    String value();
}
