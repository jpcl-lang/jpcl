// main_test.go
package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const sample = `[SERVER_ID]
config: {
  disabled_channels:,
  disabled_users: [9892, 82082, 8209]
}

[SERVER_ID_2]
prefix: "!"
modules: {
  moderation: true,
  fun: false
}
`

func runCLI(t *testing.T, args []string, stdin string) (code int, stdout, stderr string) {
	t.Helper()
	var out, errBuf bytes.Buffer
	code = run(args, &out, &errBuf, strings.NewReader(stdin))
	return code, out.String(), errBuf.String()
}

func sampleFile(t *testing.T, contents string) string {
	t.Helper()
	if contents == "" {
		contents = sample
	}
	file := filepath.Join(t.TempDir(), "a.jp")
	if err := os.WriteFile(file, []byte(contents), 0o644); err != nil {
		t.Fatal(err)
	}
	return file
}

func TestCLICheckOK(t *testing.T) {
	code, out, _ := runCLI(t, []string{"check", sampleFile(t, "")}, "")
	if code != 0 || !strings.Contains(out, "ok") {
		t.Fatalf("code=%d out=%q", code, out)
	}
	code, out, _ = runCLI(t, []string{"check", "-q", sampleFile(t, "")}, "")
	if code != 0 || out != "" {
		t.Fatalf("quiet: code=%d out=%q", code, out)
	}
}

func TestCLICheckFailure(t *testing.T) {
	code, _, errOut := runCLI(t, []string{"check", sampleFile(t, "[s]\na 1\n")}, "")
	if code != 1 || !strings.Contains(errOut, "expected ':'") {
		t.Fatalf("code=%d err=%q", code, errOut)
	}
}

func TestCLICheckMissingFile(t *testing.T) {
	code, _, errOut := runCLI(t, []string{"check", filepath.Join(t.TempDir(), "nope.jp")}, "")
	if code != 1 || errOut == "" {
		t.Fatalf("code=%d err=%q", code, errOut)
	}
}

func TestCLICheckReadsStdin(t *testing.T) {
	code, _, _ := runCLI(t, []string{"check", "-"}, sample)
	if code != 0 {
		t.Fatalf("code=%d", code)
	}
	_, _, errOut := runCLI(t, []string{"check", "-"}, "[s]\na 1\n")
	if !strings.Contains(errOut, "<stdin>:2:4: expected ':' after key") {
		t.Fatalf("err=%q", errOut)
	}
}

func TestCLIFmtWritesInPlace(t *testing.T) {
	file := sampleFile(t, "[s]\n  a:   {b:1,}\n")
	code, _, errOut := runCLI(t, []string{"fmt", "-w", file}, "")
	if code != 0 || !strings.Contains(errOut, "reformatted") {
		t.Fatalf("code=%d err=%q", code, errOut)
	}
	got, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "[s]\na: {\n  b: 1\n}\n" {
		t.Fatalf("got %q", got)
	}
	_, _, errOut = runCLI(t, []string{"fmt", "-w", file}, "")
	if errOut != "" {
		t.Fatalf("expected no reformat on second run, got %q", errOut)
	}
}

func TestCLIFmtPrintsToStdout(t *testing.T) {
	_, out, _ := runCLI(t, []string{"fmt", "--sort-keys", "-"}, "[s]\nb: 1\na: 2\n")
	if out != "[s]\na: 2\nb: 1\n" {
		t.Fatalf("out=%q", out)
	}
}

func TestCLIGet(t *testing.T) {
	code, out, _ := runCLI(t, []string{"get", sampleFile(t, ""), "SERVER_ID.config.disabled_users"}, "")
	if code != 0 {
		t.Fatalf("code=%d", code)
	}
	var got []int
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatal(err)
	}
	want := []int{9892, 82082, 8209}
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] || got[2] != want[2] {
		t.Fatalf("got %v", got)
	}

	_, out, _ = runCLI(t, []string{"get", "-r", sampleFile(t, ""), "SERVER_ID_2.prefix"}, "")
	if out != "!\n" {
		t.Fatalf("out=%q", out)
	}
}

func TestCLIGetMissingPath(t *testing.T) {
	code, _, errOut := runCLI(t, []string{"get", sampleFile(t, ""), "nope"}, "")
	if code != 1 || !strings.Contains(errOut, "no such path") {
		t.Fatalf("code=%d err=%q", code, errOut)
	}
}

func TestCLIJSONConversions(t *testing.T) {
	source := sampleFile(t, "")
	dir := filepath.Dir(source)
	target := filepath.Join(dir, "a.json")

	code, _, _ := runCLI(t, []string{"to-json", "-o", target, source}, "")
	if code != 0 {
		t.Fatalf("to-json code=%d", code)
	}
	back := filepath.Join(dir, "b.jp")
	code, _, _ = runCLI(t, []string{"from-json", "-o", back, target}, "")
	if code != 0 {
		t.Fatalf("from-json code=%d", code)
	}
	got, err := os.ReadFile(back)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != sample {
		t.Fatalf("got:\n%s", got)
	}
}

func TestCLIFromJSONReportsInvalidInput(t *testing.T) {
	code, _, _ := runCLI(t, []string{"from-json", "-"}, "{nope")
	if code != 1 {
		t.Fatalf("code=%d", code)
	}
	_, _, errOut := runCLI(t, []string{"from-json", "-"}, "[1, 2]")
	if !strings.Contains(errOut, "must be a JSON object") {
		t.Fatalf("err=%q", errOut)
	}
}

func TestCLIUsageErrorsExitTwo(t *testing.T) {
	if code, _, _ := runCLI(t, []string{}, ""); code != 2 {
		t.Fatalf("no command: code=%d", code)
	}
	if _, _, errOut := runCLI(t, []string{"nope"}, ""); !strings.Contains(errOut, "invalid command") {
		t.Fatalf("err=%q", errOut)
	}
	if _, _, errOut := runCLI(t, []string{"check"}, ""); !strings.Contains(errOut, "required: files") {
		t.Fatalf("err=%q", errOut)
	}
	if _, _, errOut := runCLI(t, []string{"get", "a.jp"}, ""); !strings.Contains(errOut, "required") {
		t.Fatalf("err=%q", errOut)
	}
	if code, _, _ := runCLI(t, []string{"check", "--bogus", "a.jp"}, ""); code != 2 {
		t.Fatalf("bogus flag: code=%d", code)
	}
}

func TestCLIHelpAndVersion(t *testing.T) {
	_, out, _ := runCLI(t, []string{"--help"}, "")
	if !strings.Contains(out, "from-json") {
		t.Fatalf("out=%q", out)
	}
	_, out, _ = runCLI(t, []string{"fmt", "-h"}, "")
	if !strings.Contains(out, "--sort-keys") {
		t.Fatalf("out=%q", out)
	}
	_, out, _ = runCLI(t, []string{"--version"}, "")
	if !strings.HasPrefix(out, "jpcl ") {
		t.Fatalf("out=%q", out)
	}
}
