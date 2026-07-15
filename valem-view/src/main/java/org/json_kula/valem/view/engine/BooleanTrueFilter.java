package org.json_kula.valem.view.engine;

/** Jackson valueFilter that suppresses boolean {@code true} — for fields where {@code true} is the default. */
public final class BooleanTrueFilter {
    @Override public boolean equals(Object o) { return Boolean.TRUE.equals(o); }
    @Override public int hashCode() { return 1; }
}
