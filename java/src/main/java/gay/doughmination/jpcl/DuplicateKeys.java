/* DuplicateKeys.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

/** What to do when a key appears twice in the same mapping. */
public enum DuplicateKeys {
    /** Fail with a {@link JPDecodeException}. The default. */
    ERROR,

    /** Keep the first value. */
    FIRST,

    /** Keep the last value. */
    LAST
}
