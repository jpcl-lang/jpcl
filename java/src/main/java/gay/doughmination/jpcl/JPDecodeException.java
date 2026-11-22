/* JPDecodeException.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.io.Serial;
import java.util.Optional;

/** A malformed {@code .jp} document. {@link #getMessage()} gives {@code file:line:col: message} and a caret excerpt. */
public final class JPDecodeException extends JPException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String rawMessage;
    private final int pos;
    private final int line;
    private final int col;
    private final String filename;

    // The offending line, or null when the document was empty
    private final String excerpt;

    private JPDecodeException(
        String rawMessage,
        int pos,
        int line,
        int col,
        String filename,
        String excerpt
    ) {
        super(render(rawMessage, line, col, filename, excerpt));
        this.rawMessage = rawMessage;
        this.pos = pos;
        this.line = line;
        this.col = col;
        this.filename = filename;
        this.excerpt = excerpt;
    }

    static JPDecodeException at(String message, String text, int pos) {
        int clamped = Math.min(Math.max(pos, 0), text.length());
        String before = text.substring(0, clamped);
        int lineStart = before.lastIndexOf('\n') + 1;
        int nextBreak = text.indexOf('\n', clamped);
        int lineEnd = nextBreak < 0 ? text.length() : nextBreak;

        int line = (int) before.chars().filter(unit -> unit == '\n').count() + 1;
        int col = before.codePointCount(lineStart, before.length()) + 1;

        String excerpt = null;
        if (!text.isEmpty()) {
            excerpt = text.substring(lineStart, lineEnd).replaceAll("\r+$", "");
        }
        return new JPDecodeException(
            message,
            clamped,
            line,
            col,
            null,
            excerpt
        );
    }

    /** A copy naming the file the text came from, for the message prefix. */
    public JPDecodeException withFilename(String filename) {
        return new JPDecodeException(
            rawMessage,
            pos,
            line,
            col,
            filename,
            excerpt
        );
    }

    /** The message without the location prefix or source excerpt. */
    public String rawMessage() {
        return rawMessage;
    }

    /** Zero-based UTF-16 index of the error inside the parsed text. */
    public int pos() {
        return pos;
    }

    /** One-based line number. */
    public int line() {
        return line;
    }

    /** One-based column, counted in code points. */
    public int col() {
        return col;
    }

    /** Name of the file the text came from, if known. */
    public Optional<String> filename() {
        return Optional.ofNullable(filename);
    }

    private static String render(
        String message,
        int line,
        int col,
        String filename,
        String excerpt
    ) {
        String shownName = filename == null ? "<string>" : filename;
        StringBuilder out = new StringBuilder();
        out.append(shownName)
            .append(':')
            .append(line)
            .append(':')
            .append(col)
            .append(": ")
            .append(message);
        if (excerpt == null) {
            return out.toString();
        }

        // Keep tabs in the caret prefix so the marker stays aligned
        StringBuilder prefix = new StringBuilder();
        excerpt.codePoints()
            .limit(col - 1)
            .forEach(point -> prefix.append(point == '\t' ? '\t' : ' '));

        out.append("\n    ")
            .append(excerpt)
            .append("\n    ")
            .append(prefix)
            .append('^');
        return out.toString();
    }
}
