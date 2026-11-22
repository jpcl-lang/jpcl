/* ParseOptions.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.util.Objects;

/**
 * Options for reading a document.
 *
 * @param duplicateKeys what to do when a key appears twice
 */
public record ParseOptions(DuplicateKeys duplicateKeys) {
    /** Duplicate keys are an error. */
    public static final ParseOptions DEFAULT = new ParseOptions(DuplicateKeys.ERROR);

    public ParseOptions {
        Objects.requireNonNull(duplicateKeys, "duplicateKeys");
    }

    public ParseOptions withDuplicateKeys(DuplicateKeys duplicateKeys) {
        return new ParseOptions(duplicateKeys);
    }
}
