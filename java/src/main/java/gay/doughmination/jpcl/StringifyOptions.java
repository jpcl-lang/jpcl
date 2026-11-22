/* StringifyOptions.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

/**
 * Options for writing a document.
 *
 * @param indent      spaces per nesting level
 * @param width       column budget for keeping an array on one line
 * @param sortKeys    sort keys by code point instead of keeping insertion order
 * @param ensureAscii escape non-ASCII characters instead of writing them literally
 */
public record StringifyOptions(
    int indent,
    int width,
    boolean sortKeys,
    boolean ensureAscii
) {
    /** Indent 2, width 88, insertion order, UTF-8 output. */
    public static final StringifyOptions DEFAULT = new StringifyOptions(
        2,
        88,
        false,
        false
    );

    public StringifyOptions {
        if (indent < 0) {
            throw new IllegalArgumentException("indent cannot be negative: " + indent);
        }
        if (width < 0) {
            throw new IllegalArgumentException("width cannot be negative: " + width);
        }
    }

    public StringifyOptions withIndent(int indent) {
        return new StringifyOptions(indent, width, sortKeys, ensureAscii);
    }

    public StringifyOptions withWidth(int width) {
        return new StringifyOptions(indent, width, sortKeys, ensureAscii);
    }

    public StringifyOptions withSortKeys(boolean sortKeys) {
        return new StringifyOptions(indent, width, sortKeys, ensureAscii);
    }

    public StringifyOptions withEnsureAscii(boolean ensureAscii) {
        return new StringifyOptions(indent, width, sortKeys, ensureAscii);
    }
}
