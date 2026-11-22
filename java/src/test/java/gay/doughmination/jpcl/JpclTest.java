/* JpclTest.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JpclTest {
    @Test
    void readmeExample() throws JPDecodeException {
        JPObject root = Jpcl.parse("[SERVER_ID]\nprefix: \"!\"\n");
        assertEquals("!", Jpcl.getPath(root, "SERVER_ID.prefix").flatMap(Value::asString).orElseThrow());
    }

    @Test
    void versionMatchesTheOtherPorts() {
        assertEquals("2.0.0", Jpcl.VERSION);
    }

    @Test
    void getPathWalksObjectsOnly() throws JPDecodeException {
        JPObject root = Jpcl.parse("[a]\nb: {c: [1]}\nempty:\n");
        assertEquals(Value.Array.of(Value.of(1)), Jpcl.getPath(root, "a.b.c").orElseThrow());
        assertEquals(Value.Null.INSTANCE, Jpcl.getPath(root, "a.empty").orElseThrow());
        assertFalse(Jpcl.getPath(root, "a.b.c.d").isPresent());
        assertFalse(Jpcl.getPath(root, "missing").isPresent());
    }

    @Test
    void invalidUtf8IsADecodeError() {
        byte[] bytes = {'a', ':', ' ', (byte) 0xff, '\n'};
        JPDecodeException error = assertThrows(
            JPDecodeException.class,
            () -> Jpcl.parseBytes(bytes, ParseOptions.DEFAULT)
        );
        assertEquals("invalid UTF-8", error.rawMessage());
        assertEquals(1, error.line());
        assertEquals(4, error.col());
    }

    @Test
    void invalidUtf8AfterAByteOrderMark() {
        byte[] bytes = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', ':', ' ', (byte) 0xc3, '\n'};
        JPDecodeException error = assertThrows(
            JPDecodeException.class,
            () -> Jpcl.parseBytes(bytes, ParseOptions.DEFAULT)
        );
        assertEquals(4, error.col());
    }

    @Test
    void loadAndDumpRoundTrip(@TempDir Path folder) throws Exception {
        Path path = folder.resolve("nested").resolve("out.jp");
        JPObject root = new JPObject()
            .put("version", 2)
            .put("id", Value.of(new BigInteger("123456789012345678901234")));

        Jpcl.dump(root, path);
        assertEquals(root, Jpcl.load(path));
        assertEquals("version: 2\nid: 123456789012345678901234\n", Files.readString(path, StandardCharsets.UTF_8));

        // Nothing but the file itself is left behind
        assertEquals(List.of(path), Files.list(path.getParent()).toList());
    }

    @Test
    void loadNamesTheFile(@TempDir Path folder) throws Exception {
        assertThrows(NoSuchFileException.class, () -> Jpcl.load(folder.resolve("definitely-missing.jp")));

        Path broken = folder.resolve("broken.jp");
        Files.writeString(broken, "[s]\nprefix \"!\"\n");
        JPDecodeException error = assertThrows(JPDecodeException.class, () -> Jpcl.load(broken));
        assertTrue(error.getMessage().startsWith(broken + ":2:8: "));
    }

    @Test
    void objectsKeepInsertionOrder() {
        JPObject object = new JPObject()
            .put("b", 1)
            .put("a", 2)
            .put("b", 3);
        assertEquals(List.of("b", "a"), List.copyOf(object.keys()));
        assertEquals(Value.of(3), object.get("b").orElseThrow());
    }
}
