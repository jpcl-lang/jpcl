/* cli/MainTest.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    record TestRun(
        int code,
        String stdout,
        String stderr
    ) {
    }

    static TestRun run(List<String> argv, String input) throws IOException {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Main cli = new Main(stdout, stderr, () -> input.getBytes(StandardCharsets.UTF_8));
        int code = cli.run(argv);
        return new TestRun(
            code,
            stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8)
        );
    }

    @Test
    void checkReportsTheFirstErrorWithACaret() throws IOException {
        TestRun result = run(List.of("check", "-"), "[s]\nprefix \"!\"\n");
        assertEquals(1, result.code());
        assertEquals(
            "<stdin>:2:8: expected ':' after key 'prefix', found '\"'\n    prefix \"!\"\n           ^\n",
            result.stderr()
        );
    }

    @Test
    void fmtPrintsCanonicalText() throws IOException {
        TestRun result = run(List.of("fmt", "--indent=4", "-"), "[s]\nk: {a: 1}\n");
        assertEquals(0, result.code());
        assertEquals("[s]\nk: {\n    a: 1\n}\n", result.stdout());
    }

    @Test
    void fmtWriteRewritesInPlace(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("a.jp");
        Files.writeString(file, "[s]\nk: {a: 1}\n");

        TestRun result = run(List.of("fmt", file.toString(), "-w"), "");
        assertEquals(0, result.code());
        assertEquals("reformatted " + file + "\n", result.stderr());
        assertEquals("[s]\nk: {\n  a: 1\n}\n", Files.readString(file));

        TestRun again = run(List.of("fmt", "-w", file.toString()), "");
        assertEquals("", again.stderr());
    }

    @Test
    void getAndToJson() throws IOException {
        assertEquals("hello\n", run(List.of("get", "-r", "-", "s.name"), "[s]\nname: hello\n").stdout());

        TestRun missing = run(List.of("get", "-", "s.nope"), "[s]\nname: hello\n");
        assertEquals(1, missing.code());
        assertEquals("no such path: s.nope\n", missing.stderr());

        assertEquals("{\n  \"a\": [\n    1\n  ]\n}\n", run(List.of("to-json", "-"), "a: [1]\n").stdout());
    }

    @Test
    void fromJsonKeepsBigIntegersAndKeyOrder() throws IOException {
        TestRun result = run(List.of("from-json", "-"), "{\"z\": 1, \"s\": {\"id\": 123456789012345678901234}}");
        assertEquals("z: 1\n\n[s]\nid: 123456789012345678901234\n", result.stdout());

        TestRun array = run(List.of("from-json", "-"), "[1]");
        assertEquals("the top level of a .jp document must be an object, got 'Array'\n", array.stderr());
    }

    @Test
    void missingFilesAreReported() throws IOException {
        TestRun result = run(List.of("check", "definitely-missing.jp"), "");
        assertEquals(1, result.code());
        assertEquals("definitely-missing.jp: cannot read file: no such file or directory\n", result.stderr());
    }

    @Test
    void usageErrorsExitWith2() throws IOException {
        assertEquals(2, run(List.of(), "").code());

        TestRun bad = run(List.of("fmt", "--indent", "x", "-"), "");
        assertEquals(2, bad.code());
        assertEquals(
            "usage: jpcl fmt [-w] [--indent N] [--width N] [--sort-keys] files...\n"
                + "jpcl fmt: error: argument --indent: invalid non-negative int value: 'x'\n",
            bad.stderr()
        );
        assertEquals(
            "usage: jpcl get [-r] file path\n"
                + "jpcl get: error: the following arguments are required: file, path\n",
            run(List.of("get"), "").stderr()
        );
        assertEquals("jpcl 2.0.0\n", run(List.of("--version"), "").stdout());
    }
}
