/* JPObject.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A section or {@code {...}} object. Keys keep their insertion order. */
public final class JPObject implements Value, Iterable<Map.Entry<String, Value>> {
    private final LinkedHashMap<String, Value> entries = new LinkedHashMap<>();

    public JPObject() {
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean containsKey(String key) {
        return entries.containsKey(key);
    }

    /** The value for {@code key}, or empty when it is missing. An empty value is {@link Value.Null}. */
    public Optional<Value> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    /** Insert or replace. A replaced key keeps its original position. Returns this object. */
    public JPObject put(String key, Value value) {
        Objects.requireNonNull(key, "key");
        entries.put(key, value == null ? Value.Null.INSTANCE : value);
        return this;
    }

    public JPObject put(String key, String value) {
        return put(key, Value.of(value));
    }

    public JPObject put(String key, long value) {
        return put(key, Value.of(value));
    }

    public JPObject put(String key, double value) {
        return put(key, Value.of(value));
    }

    public JPObject put(String key, boolean value) {
        return put(key, Value.of(value));
    }

    /** Remove {@code key}, returning its old value if there was one. */
    public Optional<Value> remove(String key) {
        return Optional.ofNullable(entries.remove(key));
    }

    /** The keys in insertion order. */
    public Set<String> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /** A read-only view of the entries in insertion order. */
    public Map<String, Value> asMap() {
        return Collections.unmodifiableMap(entries);
    }

    @Override
    public Iterator<Map.Entry<String, Value>> iterator() {
        return asMap().entrySet().iterator();
    }

    // Equal when the same keys map to equal values, in the same order
    @Override
    public boolean equals(java.lang.Object other) {
        if (!(other instanceof JPObject that)) {
            return false;
        }
        List<Map.Entry<String, Value>> mine = new ArrayList<>(entries.entrySet());
        List<Map.Entry<String, Value>> theirs = new ArrayList<>(that.entries.entrySet());
        return mine.equals(theirs);
    }

    @Override
    public int hashCode() {
        return new ArrayList<>(entries.entrySet()).hashCode();
    }

    @Override
    public String toString() {
        return "JPObject" + entries;
    }
}
