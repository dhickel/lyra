package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.types.NominalSchema;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Compiler-only heap snapshot. Runtime objects use typed JVM fields, not this map. */
public record NominalObjectState(NominalSchema schema, Map<Integer, ValueAlternatives> fields, boolean singleton) {
    public NominalObjectState {
        Objects.requireNonNull(schema, "schema");
        var copy = new TreeMap<Integer, ValueAlternatives>();
        Objects.requireNonNull(fields, "fields").forEach((index, value) -> {
            if (index < 0 || index >= schema.members().size()) throw new IllegalArgumentException("object field index outside schema");
            Objects.requireNonNull(value, "field value");
            if (value.isEmpty() || value.alternatives().stream().anyMatch(alternative ->
                    !alternative.type().equals(schema.members().get(index).type()))) {
                throw new IllegalArgumentException("object field value disagrees with declared member contract");
            }
            copy.put(index, value);
        });
        fields = Collections.unmodifiableMap(copy);
    }

    public NominalObjectState write(int index, ValueAlternatives value, boolean strong) {
        var updated = new TreeMap<>(fields);
        updated.merge(index, value, (old, replacement) -> strong && singleton ? replacement : old.join(replacement));
        return new NominalObjectState(schema, updated, singleton);
    }

    public NominalObjectState join(NominalObjectState other) {
        if (!schema.equals(other.schema)) throw new IllegalArgumentException("object heap schemas disagree");
        var joined = new TreeMap<>(fields);
        other.fields.forEach((index, value) -> joined.merge(index, value, ValueAlternatives::join));
        return new NominalObjectState(schema, joined, singleton && other.singleton);
    }

    public NominalObjectState repeatedAllocation() { return new NominalObjectState(schema, fields, false); }
}
