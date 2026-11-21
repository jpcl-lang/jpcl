// jpcl_test.go
package jpcl_test

import (
	"errors"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	jpcl "github.com/jpcl-lang/jpcl/go/v2"
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

func obj(pairs ...any) *jpcl.Object {
	o := jpcl.NewObject()
	for i := 0; i < len(pairs); i += 2 {
		o.Set(pairs[i].(string), pairs[i+1])
	}
	return o
}

func sampleData() *jpcl.Object {
	return obj(
		"SERVER_ID", obj(
			"config", obj(
				"disabled_channels", nil,
				"disabled_users", []any{int64(9892), int64(82082), int64(8209)},
			),
		),
		"SERVER_ID_2", obj(
			"prefix", "!",
			"modules", obj("moderation", true, "fun", false),
		),
	)
}

func mustParse(t *testing.T, text string) *jpcl.Object {
	t.Helper()
	obj, err := jpcl.Parse(text, jpcl.ParseOptions{})
	if err != nil {
		t.Fatalf("Parse(%q): %v", text, err)
	}
	return obj
}

// -- reading ----------------------------------------------------------------

func TestParsesTheReferenceDocument(t *testing.T) {
	got := mustParse(t, sample)
	if !reflect.DeepEqual(got, sampleData()) {
		t.Fatalf("got %#v, want %#v", got, sampleData())
	}
}

func TestEmptyValueBecomesNil(t *testing.T) {
	data := mustParse(t, "[s]\na:,\nb:\nc: 1\n")
	s, _ := data.Get("s")
	want := obj("a", nil, "b", nil, "c", int64(1))
	if !reflect.DeepEqual(s, want) {
		t.Fatalf("got %#v, want %#v", s, want)
	}
}

func TestCommentsAndBlankLinesAreIgnored(t *testing.T) {
	text := "\n    ~~ leading comment\n\n    [s]   ~~ after a header\n" +
		"      a: 1  ~~ after a value\n      ~~ between entries\n      b: {\n" +
		"        ~~ inside an object\n        c: [1, 2]  ~~ inside, after a value\n      }\n    "
	data := mustParse(t, text)
	want := obj("s", obj("a", int64(1), "b", obj("c", []any{int64(1), int64(2)})))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestBlockCommentsAreIgnored(t *testing.T) {
	text := "\n    ~* a block comment\n       across lines *~\n    [s]  ~* inline *~\n" +
		"      a: ~* before a value *~ 1\n      b: 2 ~* after a value,\n" +
		"              spanning lines *~\n      c: [1, ~* inside *~ 2]\n    "
	data := mustParse(t, text)
	want := obj("s", obj("a", int64(1), "b", int64(2), "c", []any{int64(1), int64(2)}))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestBlockCommentSpanningLinesEndsTheEntry(t *testing.T) {
	data := mustParse(t, "[s]\na: ~* no\nvalue *~\nb: 1\n")
	want := obj("s", obj("a", nil, "b", int64(1)))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestUnterminatedBlockCommentIsAnError(t *testing.T) {
	_, err := jpcl.Parse("[s]\na: 1 ~* never closed\n", jpcl.ParseOptions{})
	if err == nil || !strings.Contains(err.Error(), "unterminated block comment") {
		t.Fatalf("got %v, want an unterminated block comment error", err)
	}
}

func TestBareValueStopsAtBlockComment(t *testing.T) {
	data := mustParse(t, "[s]\na: hello~* note *~\n")
	want := obj("s", obj("a", "hello"))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestHashDollarAndSingleTildeArePlainCharacters(t *testing.T) {
	data := mustParse(t, "[s]\ncolour: #8496AD\nprice: $5 ~~ cost\nhome: ~/jp\nlegacy: 1 $$ 2\n")
	want := obj(
		"s",
		obj(
			"colour", "#8496AD",
			"price", "$5",
			"home", "~/jp",
			"legacy", "1 $$ 2",
		),
	)
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestTrailingCommasAreAllowed(t *testing.T) {
	data := mustParse(t, "[s]\no: {a: 1, b: 2,}\nl: [1, 2, 3,]\n")
	s, _ := data.Get("s")
	want := obj("o", obj("a", int64(1), "b", int64(2)), "l", []any{int64(1), int64(2), int64(3)})
	if !reflect.DeepEqual(s, want) {
		t.Fatalf("got %#v, want %#v", s, want)
	}
}

func TestRepeatedAndLeadingCommasAreTolerated(t *testing.T) {
	data := mustParse(t, "[s]\no: {,a: 1,, b: 2,}\nl: [,1,,2,]\n")
	s, _ := data.Get("s")
	want := obj("o", obj("a", int64(1), "b", int64(2)), "l", []any{int64(1), int64(2)})
	if !reflect.DeepEqual(s, want) {
		t.Fatalf("got %#v, want %#v", s, want)
	}
}

func TestEntriesSeparatedByNewlinesCommasOrBoth(t *testing.T) {
	text := "[s]\no: {\n  a: 1\n  b: 2,\n  c: 3,\n}\nl: [\n  1\n  2,\n]\n"
	data := mustParse(t, text)
	s, _ := data.Get("s")
	want := obj("o", obj("a", int64(1), "b", int64(2), "c", int64(3)), "l", []any{int64(1), int64(2)})
	if !reflect.DeepEqual(s, want) {
		t.Fatalf("got %#v, want %#v", s, want)
	}
}

func TestMultipleKeysPerSectionKeepOrder(t *testing.T) {
	data := mustParse(t, "[s]\nz: 1\na: 2\nm: 3\n")
	s, _ := data.Get("s")
	got := s.(*jpcl.Object).Keys()
	want := []string{"z", "a", "m"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestScalarTypes(t *testing.T) {
	text := "[s]\n" +
		"i: 42\n" +
		"neg: -7\n" +
		"f: 3.5\n" +
		"exp: 1e3\n" +
		"under: 1_000\n" +
		"hex: 0xff\n" +
		"oct: 0o755\n" +
		"bin: -0b1010\n" +
		"t: true\n" +
		"f2: False\n" +
		"n: null\n" +
		"s: \"quoted\"\n" +
		"s2: 'single'\n" +
		"bare: hello world\n" +
		"notnum: 0xzz\n"
	data := mustParse(t, text)
	s, _ := data.Get("s")
	want := obj(
		"i", int64(42),
		"neg", int64(-7),
		"f", 3.5,
		"exp", 1000.0,
		"under", int64(1000),
		"hex", int64(255),
		"oct", int64(493),
		"bin", int64(-10),
		"t", true,
		"f2", false,
		"n", nil,
		"s", "quoted",
		"s2", "single",
		"bare", "hello world",
		"notnum", "0xzz",
	)
	if !reflect.DeepEqual(s, want) {
		t.Fatalf("got %#v, want %#v", s, want)
	}
}

func TestNonFiniteFloats(t *testing.T) {
	data := mustParse(t, "[s]\na: inf\nb: -inf\nc: nan\n")
	s, _ := data.Get("s")
	sec := s.(*jpcl.Object)
	a, _ := sec.Get("a")
	b, _ := sec.Get("b")
	c, _ := sec.Get("c")
	if a != math.Inf(1) {
		t.Fatalf("a -> %#v", a)
	}
	if b != math.Inf(-1) {
		t.Fatalf("b -> %#v", b)
	}
	if !math.IsNaN(c.(float64)) {
		t.Fatalf("c -> %#v", c)
	}
}

func TestBackslashNewlineContinuesAString(t *testing.T) {
	data := mustParse(t, "[s]\na: \"one \\\n     two\"\n")
	s, _ := data.Get("s")
	a, _ := s.(*jpcl.Object).Get("a")
	if a != "one two" {
		t.Fatalf("a -> %#v", a)
	}
}

func TestSimpleStringEscapes(t *testing.T) {
	data := mustParse(t, "[s]\na: \"tab\\there\\nline \\\\ \\\"q\\\"\"\n")
	s, _ := data.Get("s")
	a, _ := s.(*jpcl.Object).Get("a")
	want := "tab\there\nline \\ \"q\""
	if a != want {
		t.Fatalf("a -> %#v, want %#v", a, want)
	}
}

func TestQuotedKeysAllowAnyCharacter(t *testing.T) {
	data := mustParse(t, "[s]\n\"a b: c\": 1\n")
	s, _ := data.Get("s")
	want := obj("a b: c", int64(1))
	if !reflect.DeepEqual(s, want) {
		t.Fatalf("got %#v, want %#v", s, want)
	}
}

func TestKeysBeforeAnyHeaderLandAtTheRoot(t *testing.T) {
	data := mustParse(t, "version: 2\n\n[s]\na: 1\n")
	want := obj("version", int64(2), "s", obj("a", int64(1)))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestDottedHeadersNest(t *testing.T) {
	data := mustParse(t, "[a.b.c]\nx: 1\n\n[a.d]\ny: 2\n")
	want := obj("a", obj("b", obj("c", obj("x", int64(1))), "d", obj("y", int64(2))))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestQuotedHeaderSegmentIsNotSplit(t *testing.T) {
	data := mustParse(t, "[\"a.b\"]\nx: 1\n")
	want := obj("a.b", obj("x", int64(1)))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestDeeplyNestedContainers(t *testing.T) {
	data := mustParse(t, "[s]\na: {b: [{c: [1, {d: 2}]}]}\n")
	s, _ := data.Get("s")
	a, _ := s.(*jpcl.Object).Get("a")
	b, _ := a.(*jpcl.Object).Get("b")
	first := b.([]any)[0].(*jpcl.Object)
	c, _ := first.Get("c")
	inner := c.([]any)[1].(*jpcl.Object)
	d, _ := inner.Get("d")
	if d != int64(2) {
		t.Fatalf("d -> %#v", d)
	}
}

func TestEmptyContainers(t *testing.T) {
	data := mustParse(t, "[s]\na: {}\nb: []\n")
	s, _ := data.Get("s")
	sec := s.(*jpcl.Object)
	a, _ := sec.Get("a")
	b, _ := sec.Get("b")
	if a.(*jpcl.Object).Len() != 0 {
		t.Fatalf("a -> %#v", a)
	}
	if len(b.([]any)) != 0 {
		t.Fatalf("b -> %#v", b)
	}
}

func TestEmptyDocument(t *testing.T) {
	if data := mustParse(t, ""); data.Len() != 0 {
		t.Fatalf("got %#v", data)
	}
	if data := mustParse(t, "~~ just a comment\n\n"); data.Len() != 0 {
		t.Fatalf("got %#v", data)
	}
}

func TestEmptySection(t *testing.T) {
	data := mustParse(t, "[a]\n\n[b]\nx: 1\n")
	want := obj("a", obj(), "b", obj("x", int64(1)))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestCRLFAndBOMAreHandled(t *testing.T) {
	data := mustParse(t, "\uFEFF[s]\r\na: 1\r\n")
	want := obj("s", obj("a", int64(1)))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

// -- duplicate keys -----------------------------------------------------

func TestDuplicateKeyRaisesByDefault(t *testing.T) {
	_, err := jpcl.Parse("[s]\na: 1\na: 2\n", jpcl.ParseOptions{})
	if err == nil || !strings.Contains(err.Error(), "duplicate key") {
		t.Fatalf("err -> %v", err)
	}
}

func TestDuplicateKeyPolicies(t *testing.T) {
	text := "[s]\na: 1\na: 2\n"
	first, err := jpcl.Parse(text, jpcl.ParseOptions{DuplicateKeys: jpcl.DuplicateFirst})
	if err != nil {
		t.Fatal(err)
	}
	s, _ := first.Get("s")
	a, _ := s.(*jpcl.Object).Get("a")
	if a != int64(1) {
		t.Fatalf("first -> %#v", a)
	}

	last, err := jpcl.Parse(text, jpcl.ParseOptions{DuplicateKeys: jpcl.DuplicateLast})
	if err != nil {
		t.Fatal(err)
	}
	s, _ = last.Get("s")
	a, _ = s.(*jpcl.Object).Get("a")
	if a != int64(2) {
		t.Fatalf("last -> %#v", a)
	}
}

func TestDuplicateSectionRaises(t *testing.T) {
	_, err := jpcl.Parse("[s]\na: 1\n\n[s]\nb: 2\n", jpcl.ParseOptions{})
	if err == nil || !strings.Contains(err.Error(), "is defined twice") {
		t.Fatalf("err -> %v", err)
	}
}

func TestDuplicateSectionMergesUnderLastPolicy(t *testing.T) {
	data, err := jpcl.Parse("[s]\na: 1\n\n[s]\nb: 2\n", jpcl.ParseOptions{DuplicateKeys: jpcl.DuplicateLast})
	if err != nil {
		t.Fatal(err)
	}
	want := obj("s", obj("a", int64(1), "b", int64(2)))
	if !reflect.DeepEqual(data, want) {
		t.Fatalf("got %#v, want %#v", data, want)
	}
}

func TestInvalidDuplicatePolicyRejected(t *testing.T) {
	_, err := jpcl.Parse("", jpcl.ParseOptions{DuplicateKeys: "nope"})
	if err == nil || !strings.Contains(err.Error(), "duplicateKeys") {
		t.Fatalf("err -> %v", err)
	}
}

// -- errors ---------------------------------------------------------------

func TestSyntaxErrors(t *testing.T) {
	cases := []struct{ text, message string }{
		{"[s]\na 1\n", "expected ':' after key"},
		{"[s]\na: {b: 1\n", "unterminated object"},
		{"[s]\na: [1, 2\n", "unterminated array"},
		{"[s\na: 1\n", "unterminated section header"},
		{"[]\na: 1\n", "section header cannot be empty"},
		{"[s]\na: \"open\n", "unterminated string"},
		{"[s]\n: 1\n", "expected a key"},
		{"[s] junk\na: 1\n", "unexpected 'j' after section header"},
		{"version: 1\n[s.]\na: 1\n", "section header segment cannot be empty"},
		{"[s]\na: \"x\" \"y\"\n", "expected ',' or a line break"},
		{"[s]\na: {b: \"1\" extra}\n", "expected ',', a line break or '}'"},
	}
	for _, c := range cases {
		t.Run(c.message, func(t *testing.T) {
			_, err := jpcl.Parse(c.text, jpcl.ParseOptions{})
			if err == nil || !strings.Contains(err.Error(), c.message) {
				t.Fatalf("Parse(%q) -> %v, want containing %q", c.text, err, c.message)
			}
		})
	}
}

func TestErrorReportsLineColumnAndSource(t *testing.T) {
	_, err := jpcl.Parse("[s]\nprefix \"!\"\n", jpcl.ParseOptions{Filename: "servers.jp"})
	var decErr *jpcl.DecodeError
	if !errors.As(err, &decErr) {
		t.Fatalf("err -> %T %v", err, err)
	}
	if decErr.Line != 2 || decErr.Col != 8 {
		t.Fatalf("line/col -> %d:%d", decErr.Line, decErr.Col)
	}
	if decErr.Filename != "servers.jp" {
		t.Fatalf("filename -> %q", decErr.Filename)
	}
	if !strings.Contains(decErr.Error(), `prefix "!"`) || !strings.Contains(decErr.Error(), "^") {
		t.Fatalf("rendered error -> %s", decErr.Error())
	}
}

func TestSectionCannotShadowAScalar(t *testing.T) {
	_, err := jpcl.Parse("a: 1\n\n[a.b]\nx: 1\n", jpcl.ParseOptions{})
	if err == nil || !strings.Contains(err.Error(), "already a non-section value") {
		t.Fatalf("err -> %v", err)
	}
}

func TestNestingDepthIsCapped(t *testing.T) {
	text := "[s]\na: " + strings.Repeat("[", 500) + strings.Repeat("]", 500) + "\n"
	_, err := jpcl.Parse(text, jpcl.ParseOptions{})
	if err == nil || !strings.Contains(err.Error(), "nesting deeper than") {
		t.Fatalf("err -> %v", err)
	}
}

// -- writing ----------------------------------------------------------------

func TestDumpsMatchesTheReferenceStyle(t *testing.T) {
	text, err := jpcl.Dumps(sampleData(), jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	if text != sample {
		t.Fatalf("got:\n%s\nwant:\n%s", text, sample)
	}
}

func TestRoundTripIsStable(t *testing.T) {
	once, err := jpcl.Dumps(mustParse(t, sample), jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	twice, err := jpcl.Dumps(mustParse(t, once), jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	if once != twice {
		t.Fatalf("not stable:\n%s\n---\n%s", once, twice)
	}
}

func TestNoneIsEmptyInObjectsAndNullInArrays(t *testing.T) {
	data := obj("s", obj("a", nil, "b", []any{nil, int64(1)}))
	text, err := jpcl.Dumps(data, jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(text, "a:\n") || !strings.Contains(text, "b: [null, 1]") {
		t.Fatalf("text:\n%s", text)
	}
	if !reflect.DeepEqual(mustParse(t, text), data) {
		t.Fatalf("round trip mismatch")
	}
}

func TestLongArraysBreakOntoMultipleLines(t *testing.T) {
	ids := make([]any, 30)
	for i := range ids {
		ids[i] = int64(i)
	}
	data := obj("s", obj("ids", ids))
	text, err := jpcl.Dumps(data, jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(text, "ids: [\n") {
		t.Fatalf("text:\n%s", text)
	}
	got := mustParse(t, text)
	s, _ := got.Get("s")
	gotIDs, _ := s.(*jpcl.Object).Get("ids")
	if !reflect.DeepEqual(gotIDs, ids) {
		t.Fatalf("got %#v, want %#v", gotIDs, ids)
	}
}

func TestScalarRootsAreHoistedAboveSections(t *testing.T) {
	data := obj("s", obj("a", int64(1)), "version", int64(2))
	text, err := jpcl.Dumps(data, jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Index(text, "version: 2") > strings.Index(text, "[s]") {
		t.Fatalf("text:\n%s", text)
	}
}

func TestKeysAreQuotedOnlyWhenNecessary(t *testing.T) {
	data := obj("ok_key-1", obj("plain", int64(1), "needs quotes", int64(2), "", int64(3)))
	text, err := jpcl.Dumps(data, jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(text, "[ok_key-1]") ||
		!strings.Contains(text, `"needs quotes": 2`) ||
		!strings.Contains(text, `"": 3`) {
		t.Fatalf("text:\n%s", text)
	}
	if !reflect.DeepEqual(mustParse(t, text), data) {
		t.Fatalf("round trip mismatch")
	}
}

func TestStringValuesAreEscaped(t *testing.T) {
	value := "quote \" backslash \\ newline \n tab \t bell \a"
	text, err := jpcl.Dumps(obj("s", obj("a", value)), jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	got := mustParse(t, text)
	s, _ := got.Get("s")
	a, _ := s.(*jpcl.Object).Get("a")
	if a != value {
		t.Fatalf("got %q, want %q", a, value)
	}
}

func TestEnsureASCIIOption(t *testing.T) {
	withASCII, err := jpcl.Dumps(obj("s", obj("a", "é")), jpcl.StringifyOptions{Indent: 2, Width: 88, EnsureASCII: true})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(withASCII, "é") {
		t.Fatalf("expected no literal 'é' with EnsureASCII: %s", withASCII)
	}
	without, err := jpcl.Dumps(obj("s", obj("a", "é")), jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(without, "é") {
		t.Fatalf("expected literal 'é' without EnsureASCII: %s", without)
	}
}

func TestIndentAndSortKeysOptions(t *testing.T) {
	data := obj("s", obj("b", int64(1), "a", obj("z", int64(1))))
	text, err := jpcl.Dumps(data, jpcl.StringifyOptions{Indent: 4, Width: 88, SortKeys: true})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Index(text, "a: {") > strings.Index(text, "b: 1") {
		t.Fatalf("text:\n%s", text)
	}
	if !strings.Contains(text, "\n    z: 1") {
		t.Fatalf("text:\n%s", text)
	}
}

func TestWidthOption(t *testing.T) {
	data := obj("s", obj("a", []any{int64(1), int64(2), int64(3)}))
	text, err := jpcl.Dumps(data, jpcl.StringifyOptions{Indent: 2, Width: 8})
	if err != nil {
		t.Fatal(err)
	}
	want := "[s]\na: [\n  1,\n  2,\n  3\n]\n"
	if text != want {
		t.Fatalf("got:\n%s\nwant:\n%s", text, want)
	}
}

type unsupportedType struct{}

func TestUnserialisableTypeRaises(t *testing.T) {
	_, err := jpcl.Dumps(obj("s", obj("a", unsupportedType{})), jpcl.DefaultStringifyOptions())
	var encErr *jpcl.EncodeError
	if !errors.As(err, &encErr) || !strings.Contains(err.Error(), "not representable") {
		t.Fatalf("err -> %v", err)
	}
}

func TestDefaultHookConvertsUnknownTypes(t *testing.T) {
	opts := jpcl.DefaultStringifyOptions()
	opts.Default = func(v any) any {
		if _, ok := v.(unsupportedType); ok {
			return "converted"
		}
		return v
	}
	text, err := jpcl.Dumps(obj("s", obj("when", unsupportedType{})), opts)
	if err != nil {
		t.Fatal(err)
	}
	got := mustParse(t, text)
	s, _ := got.Get("s")
	when, _ := s.(*jpcl.Object).Get("when")
	if when != "converted" {
		t.Fatalf("when -> %#v", when)
	}
}

func TestDefaultHookMustConvert(t *testing.T) {
	opts := jpcl.DefaultStringifyOptions()
	opts.Default = func(v any) any { return v }
	_, err := jpcl.Dumps(obj("s", obj("a", unsupportedType{})), opts)
	if err == nil || !strings.Contains(err.Error(), "unconverted") {
		t.Fatalf("err -> %v", err)
	}
}

func TestBinaryDataIsRejectedWithAHint(t *testing.T) {
	_, err := jpcl.Dumps(obj("s", obj("a", []byte("x"))), jpcl.DefaultStringifyOptions())
	if err == nil || !strings.Contains(err.Error(), "binary data") {
		t.Fatalf("err -> %v", err)
	}
}

func TestCircularReferenceIsDetected(t *testing.T) {
	section := jpcl.NewObject()
	section.Set("self", section)
	_, err := jpcl.Dumps(obj("s", section), jpcl.DefaultStringifyOptions())
	if err == nil || !strings.Contains(err.Error(), "circular reference") {
		t.Fatalf("err -> %v", err)
	}
}

func TestSharedNonCircularReferencesAreFine(t *testing.T) {
	shared := []any{int64(1), int64(2)}
	text, err := jpcl.Dumps(obj("s", obj("a", shared, "b", shared)), jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	want := "[s]\na: [1, 2]\nb: [1, 2]\n"
	if text != want {
		t.Fatalf("got:\n%s\nwant:\n%s", text, want)
	}
}

func TestDumpsOfEmptyDocument(t *testing.T) {
	text, err := jpcl.Dumps(jpcl.NewObject(), jpcl.DefaultStringifyOptions())
	if err != nil {
		t.Fatal(err)
	}
	if text != "" {
		t.Fatalf("got %q", text)
	}
}

// -- files --------------------------------------------------------------

func TestLoadAndDumpPaths(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "servers.jp")
	if err := os.WriteFile(p, []byte(sample), 0o644); err != nil {
		t.Fatal(err)
	}
	data, err := jpcl.Load(p, jpcl.ParseOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(data, sampleData()) {
		t.Fatalf("got %#v", data)
	}
	out := filepath.Join(dir, "copy.jp")
	if err := jpcl.Dump(data, out, true, jpcl.DefaultStringifyOptions()); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(out)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != sample {
		t.Fatalf("got:\n%s", got)
	}
}

func TestDumpIsAtomicAndLeavesNoTempFiles(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "a.jp")
	if err := jpcl.Dump(obj("s", obj("a", int64(1))), p, true, jpcl.DefaultStringifyOptions()); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "a.jp" {
		names := make([]string, len(entries))
		for i, e := range entries {
			names[i] = e.Name()
		}
		t.Fatalf("dir contents -> %v", names)
	}
}

func TestDumpCreatesMissingParentDirectories(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "nested", "deep", "a.jp")
	if err := jpcl.Dump(obj("s", obj("a", int64(1))), p, true, jpcl.DefaultStringifyOptions()); err != nil {
		t.Fatal(err)
	}
	data, err := jpcl.Load(p, jpcl.ParseOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(data, obj("s", obj("a", int64(1)))) {
		t.Fatalf("got %#v", data)
	}
}

func TestErrorFromAFileNamesTheFile(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "broken.jp")
	if err := os.WriteFile(p, []byte("[s]\na 1\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	_, err := jpcl.Load(p, jpcl.ParseOptions{})
	if err == nil || !strings.Contains(err.Error(), "broken.jp") {
		t.Fatalf("err -> %v", err)
	}
}

func TestLoadDir(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "servers.jp"), []byte(sample), 0o644)
	os.WriteFile(filepath.Join(dir, "roles.jp"), []byte("[r]\na: 1\n"), 0o644)
	os.WriteFile(filepath.Join(dir, "ignored.txt"), []byte("nope"), 0o644)

	loaded, err := jpcl.LoadDir(dir, "", false, jpcl.ParseOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(loaded) != 2 {
		t.Fatalf("loaded -> %v", loaded)
	}
	if !reflect.DeepEqual(loaded["servers"], sampleData()) {
		t.Fatalf("servers -> %#v", loaded["servers"])
	}
}

func TestLoadDirRecursive(t *testing.T) {
	dir := t.TempDir()
	os.Mkdir(filepath.Join(dir, "guilds"), 0o755)
	os.WriteFile(filepath.Join(dir, "guilds", "a.jp"), []byte("[s]\nx: 1\n"), 0o644)

	loaded, err := jpcl.LoadDir(dir, "", true, jpcl.ParseOptions{})
	if err != nil {
		t.Fatal(err)
	}
	want := obj("s", obj("x", int64(1)))
	if !reflect.DeepEqual(loaded["guilds/a"], want) {
		t.Fatalf("loaded -> %#v", loaded)
	}
}

func TestLoadDirMissingDirectory(t *testing.T) {
	_, err := jpcl.LoadDir(filepath.Join(t.TempDir(), "nope"), "", false, jpcl.ParseOptions{})
	if err == nil {
		t.Fatal("expected an error")
	}
}

// -- JPConfig -------------------------------------------------------------

func TestConfigDottedPaths(t *testing.T) {
	cfg := jpcl.NewJPConfig(mustParse(t, sample), jpcl.ConfigOptions{})
	users := cfg.GetPath("SERVER_ID.config.disabled_users", nil)
	want := []any{int64(9892), int64(82082), int64(8209)}
	if !reflect.DeepEqual(users, want) {
		t.Fatalf("users -> %#v", users)
	}
	if v := cfg.GetPath("SERVER_ID.config.disabled_channels", "x"); v != nil {
		t.Fatalf("disabled_channels -> %#v", v)
	}
	if v := cfg.GetPath("nope.nope", "fallback"); v != "fallback" {
		t.Fatalf("fallback -> %#v", v)
	}
	if !cfg.HasPath("SERVER_ID.config.disabled_channels") {
		t.Fatal("expected HasPath true for a present-but-nil value")
	}
	if cfg.HasPath("SERVER_ID.missing") {
		t.Fatal("expected HasPath false")
	}
}

func TestConfigSetPathCreatesSections(t *testing.T) {
	cfg := jpcl.NewJPConfig(nil, jpcl.ConfigOptions{})
	if err := cfg.SetPath("A.b.c", []any{int64(1)}); err != nil {
		t.Fatal(err)
	}
	want := obj("A", obj("b", obj("c", []any{int64(1)})))
	if !reflect.DeepEqual(cfg.ToDict(), want) {
		t.Fatalf("got %#v", cfg.ToDict())
	}
}

func TestConfigSetPathReplacesAnEmptyValue(t *testing.T) {
	cfg := jpcl.NewJPConfig(mustParse(t, "[s]\nconfig:\n"), jpcl.ConfigOptions{})
	if err := cfg.SetPath("s.config.a", int64(1)); err != nil {
		t.Fatal(err)
	}
	want := obj("s", obj("config", obj("a", int64(1))))
	if !reflect.DeepEqual(cfg.ToDict(), want) {
		t.Fatalf("got %#v", cfg.ToDict())
	}
}

func TestConfigSetPathRefusesToDescendIntoAScalar(t *testing.T) {
	cfg := jpcl.NewJPConfig(mustParse(t, "[s]\na: 1\n"), jpcl.ConfigOptions{})
	err := cfg.SetPath("s.a.b", int64(2))
	if err == nil || !strings.Contains(err.Error(), "cannot descend") {
		t.Fatalf("err -> %v", err)
	}
}

func TestConfigSectionHelper(t *testing.T) {
	cfg := jpcl.NewJPConfig(mustParse(t, sample), jpcl.ConfigOptions{})
	sec, err := cfg.Section("SERVER_ID_2", false)
	if err != nil {
		t.Fatal(err)
	}
	prefix, _ := sec.Get("prefix")
	if prefix != "!" {
		t.Fatalf("prefix -> %#v", prefix)
	}
	if _, err := cfg.Section("missing", false); err == nil {
		t.Fatal("expected an error for a missing section")
	}
	created, err := cfg.Section("missing", true)
	if err != nil {
		t.Fatal(err)
	}
	if created.Len() != 0 {
		t.Fatalf("created -> %#v", created)
	}
}

func TestConfigMergeIsDeep(t *testing.T) {
	cfg := jpcl.NewJPConfig(mustParse(t, sample), jpcl.ConfigOptions{})
	cfg.Merge(mustParse(t, "[SERVER_ID_2]\nmodules: {fun: true, logs: true}\n"), true)
	modules := cfg.GetPath("SERVER_ID_2.modules", nil)
	want := obj("moderation", true, "fun", true, "logs", true)
	if !reflect.DeepEqual(modules, want) {
		t.Fatalf("modules -> %#v", modules)
	}
	if v := cfg.GetPath("SERVER_ID_2.prefix", nil); v != "!" {
		t.Fatalf("prefix -> %#v", v)
	}
}

func TestConfigShallowMergeReplaces(t *testing.T) {
	cfg := jpcl.NewJPConfig(mustParse(t, sample), jpcl.ConfigOptions{})
	cfg.Merge(mustParse(t, "[SERVER_ID_2]\nprefix: \"?\"\n"), false)
	got, _ := cfg.Data.Get("SERVER_ID_2")
	want := obj("prefix", "?")
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v", got)
	}
}

func TestConfigToDictIsADeepCopy(t *testing.T) {
	cfg := jpcl.NewJPConfig(mustParse(t, sample), jpcl.ConfigOptions{})
	snapshot := cfg.ToDict()
	users, _ := snapshot.Get("SERVER_ID")
	usersSlice, _ := users.(*jpcl.Object).Get("config")
	arr, _ := usersSlice.(*jpcl.Object).Get("disabled_users")
	arr = append(arr.([]any), int64(1)) // mutate the copy only
	_ = arr
	original := cfg.GetPath("SERVER_ID.config.disabled_users", nil)
	want := []any{int64(9892), int64(82082), int64(8209)}
	if !reflect.DeepEqual(original, want) {
		t.Fatalf("original mutated: %#v", original)
	}
}

func TestConfigSaveAndReload(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "servers.jp")
	os.WriteFile(p, []byte(sample), 0o644)

	cfg, err := jpcl.LoadJPConfig(p, false, jpcl.ConfigOptions{Write: jpcl.DefaultStringifyOptions()})
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.SetPath("SERVER_ID_2.prefix", "?"); err != nil {
		t.Fatal(err)
	}
	if _, err := cfg.Save(""); err != nil {
		t.Fatal(err)
	}

	reloaded, err := jpcl.LoadJPConfig(p, false, jpcl.ConfigOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if v := reloaded.GetPath("SERVER_ID_2.prefix", nil); v != "?" {
		t.Fatalf("reloaded prefix -> %#v", v)
	}

	if err := cfg.SetPath("SERVER_ID_2.prefix", "unsaved"); err != nil {
		t.Fatal(err)
	}
	if err := cfg.Reload(); err != nil {
		t.Fatal(err)
	}
	if v := cfg.GetPath("SERVER_ID_2.prefix", nil); v != "?" {
		t.Fatalf("prefix after reload -> %#v", v)
	}
}

func TestConfigLoadMissingOK(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "absent.jp")
	cfg, err := jpcl.LoadJPConfig(p, true, jpcl.ConfigOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Data.Len() != 0 {
		t.Fatalf("got %#v", cfg.Data)
	}
	if err := cfg.SetPath("s.a", int64(1)); err != nil {
		t.Fatal(err)
	}
	saved, err := cfg.Save("")
	if err != nil {
		t.Fatal(err)
	}
	if saved != p {
		t.Fatalf("saved -> %q, want %q", saved, p)
	}
	data, err := jpcl.Load(p, jpcl.ParseOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(data, obj("s", obj("a", int64(1)))) {
		t.Fatalf("got %#v", data)
	}
}

func TestConfigLoadMissingRaisesWithoutFlag(t *testing.T) {
	dir := t.TempDir()
	_, err := jpcl.LoadJPConfig(filepath.Join(dir, "absent.jp"), false, jpcl.ConfigOptions{})
	if err == nil {
		t.Fatal("expected an error")
	}
}

func TestConfigSaveWithoutPath(t *testing.T) {
	cfg := jpcl.NewJPConfig(nil, jpcl.ConfigOptions{})
	_, err := cfg.Save("")
	if err == nil || !strings.Contains(err.Error(), "no path") {
		t.Fatalf("err -> %v", err)
	}
}
