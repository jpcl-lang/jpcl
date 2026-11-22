/* JPException.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.io.Serial;

/** The base of every jpcl error. */
public class JPException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public JPException(String message) {
        super(message);
    }
}
