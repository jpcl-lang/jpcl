// main.go
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math"
	"os"
	"strconv"
	"strings"

	jpcl "github.com/jpcl-lang/jpcl/go"
)

const checkUsage = `usage: jpcl check [-q] files...

  -q, --quiet     only report errors`

const fmtUsage = `usage: jpcl fmt [-w] [--indent N] [--width N] [--sort-keys] files...

  -w, --write     rewrite files in place
  --indent N      spaces per level (default 2)
  --width N       column budget for inline arrays (default 88)
  --sort-keys     sort keys alphabetically`

const getUsage = `usage: jpcl get [-r] file path

  path            e.g. SERVER_ID.config.disabled_users
  -r, --raw       print strings unquoted`

const toJSONUsage = `usage: jpcl to-json [-o OUTPUT] [--indent N] file

  -o, --output    write to a file instead of stdout
  --indent N      spaces per level (default 2)`

const fromJSONUsage = `usage: jpcl from-json [-o OUTPUT] [--indent N] [--width N] [--sort-keys] file

  -o, --output    write to a file instead of stdout
  --indent N      spaces per level (default 2)
  --width N       column budget for inline arrays (default 88)
  --sort-keys     sort keys alphabetically`

type command struct {
	name string
	help string
	run  func(args []string, stdout, stderr io.Writer, stdin io.Reader) int
}

var registry = []command{
	{"check", "validate .jp files", cmdCheck},
	{"fmt", "reformat .jp files", cmdFmt},
	{"get", "print one value by dotted path", cmdGet},
	{"to-json", "convert .jp to JSON", cmdToJSON},
	{"from-json", "convert JSON to .jp", cmdFromJSON},
}

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr, os.Stdin))
}

func run(args []string, stdout, stderr io.Writer, stdin io.Reader) int {
	if len(args) == 0 {
		fmt.Fprintf(stderr, "%s\njpcl: error: a command is required\n", firstLine(mainUsage()))
		return 2
	}
	name, rest := args[0], args[1:]
	switch name {
	case "-h", "--help":
		fmt.Fprint(stdout, mainUsage())
		return 0
	case "--version":
		fmt.Fprintf(stdout, "jpcl %s\n", jpcl.Version)
		return 0
	}
	for _, c := range registry {
		if c.name == name {
			return c.run(rest, stdout, stderr, stdin)
		}
	}
	fmt.Fprintf(stderr, "%s\njpcl: error: invalid command '%s' (choose from %s)\n",
		firstLine(mainUsage()), name, strings.Join(commandNames(), ", "))
	return 2
}

func commandNames() []string {
	names := make([]string, len(registry))
	for i, c := range registry {
		names[i] = c.name
	}
	return names
}

func mainUsage() string {
	var b strings.Builder
	fmt.Fprintf(&b, "usage: jpcl [-h] [--version] {%s} ...\n\n", strings.Join(commandNames(), ","))
	b.WriteString("Work with .jp configuration files.\n\ncommands:\n")
	for _, c := range registry {
		fmt.Fprintf(&b, "  %-12s%s\n", c.name, c.help)
	}
	b.WriteString("\noptions:\n  -h, --help      show this help message and exit\n  --version       show the version and exit\n")
	return b.String()
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i]
	}
	return s
}

// -- shared helpers ---------------------------------------------------------

func readInput(path string, stdin io.Reader) (*jpcl.Object, error) {
	if path == "-" {
		data, err := io.ReadAll(stdin)
		if err != nil {
			return nil, err
		}
		return jpcl.Parse(string(data), jpcl.ParseOptions{Filename: "<stdin>"})
	}
	return jpcl.Load(path, jpcl.ParseOptions{})
}

func formatOptions(indent, width int, sortKeys bool) jpcl.StringifyOptions {
	return jpcl.StringifyOptions{Indent: indent, Width: width, SortKeys: sortKeys}
}

func writeOutput(output, text string, stdout, stderr io.Writer) int {
	if output == "" {
		fmt.Fprint(stdout, text)
		return 0
	}
	if err := os.WriteFile(output, []byte(text), 0o644); err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	return 0
}

// -- commands -----------------------------------------------------------

func cmdCheck(args []string, stdout, stderr io.Writer, stdin io.Reader) int {
	fs := flag.NewFlagSet("check", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	quiet := fs.Bool("q", false, "only report errors")
	fs.BoolVar(quiet, "quiet", false, "only report errors")
	help := fs.Bool("h", false, "show help")
	fs.BoolVar(help, "help", false, "show help")

	if err := fs.Parse(args); err != nil {
		fmt.Fprintf(stderr, "%s\njpcl check: error: %v\n", firstLine(checkUsage), err)
		return 2
	}
	if *help {
		fmt.Fprintln(stdout, checkUsage)
		return 0
	}
	files := fs.Args()
	if len(files) == 0 {
		fmt.Fprintf(stderr, "%s\njpcl check: error: the following arguments are required: files\n", firstLine(checkUsage))
		return 2
	}

	failures := 0
	for _, path := range files {
		if _, err := readInput(path, stdin); err != nil {
			failures++
			fmt.Fprintln(stderr, err)
			continue
		}
		if !*quiet {
			fmt.Fprintf(stdout, "ok  %s\n", path)
		}
	}
	if failures > 0 {
		return 1
	}
	return 0
}

func cmdFmt(args []string, stdout, stderr io.Writer, stdin io.Reader) int {
	fs := flag.NewFlagSet("fmt", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	write := fs.Bool("w", false, "rewrite files in place")
	fs.BoolVar(write, "write", false, "rewrite files in place")
	indent := fs.Int("indent", 2, "spaces per level")
	width := fs.Int("width", 88, "column budget for inline arrays")
	sortKeys := fs.Bool("sort-keys", false, "sort keys alphabetically")
	help := fs.Bool("h", false, "show help")
	fs.BoolVar(help, "help", false, "show help")

	if err := fs.Parse(args); err != nil {
		fmt.Fprintf(stderr, "%s\njpcl fmt: error: %v\n", firstLine(fmtUsage), err)
		return 2
	}
	if *help {
		fmt.Fprintln(stdout, fmtUsage)
		return 0
	}
	files := fs.Args()
	if len(files) == 0 {
		fmt.Fprintf(stderr, "%s\njpcl fmt: error: the following arguments are required: files\n", firstLine(fmtUsage))
		return 2
	}

	format := formatOptions(*indent, *width, *sortKeys)
	failures := 0
	for _, path := range files {
		data, err := readInput(path, stdin)
		if err != nil {
			failures++
			fmt.Fprintln(stderr, err)
			continue
		}
		text, err := jpcl.Dumps(data, format)
		if err != nil {
			failures++
			fmt.Fprintln(stderr, err)
			continue
		}
		if *write && path != "-" {
			if current, err := os.ReadFile(path); err == nil && string(current) == text {
				continue
			}
			if err := jpcl.Dump(data, path, true, format); err != nil {
				failures++
				fmt.Fprintln(stderr, err)
				continue
			}
			fmt.Fprintf(stderr, "reformatted %s\n", path)
		} else {
			fmt.Fprint(stdout, text)
		}
	}
	if failures > 0 {
		return 1
	}
	return 0
}

func cmdGet(args []string, stdout, stderr io.Writer, stdin io.Reader) int {
	fs := flag.NewFlagSet("get", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	raw := fs.Bool("r", false, "print strings unquoted")
	fs.BoolVar(raw, "raw", false, "print strings unquoted")
	help := fs.Bool("h", false, "show help")
	fs.BoolVar(help, "help", false, "show help")

	if err := fs.Parse(args); err != nil {
		fmt.Fprintf(stderr, "%s\njpcl get: error: %v\n", firstLine(getUsage), err)
		return 2
	}
	if *help {
		fmt.Fprintln(stdout, getUsage)
		return 0
	}
	positional := fs.Args()
	if len(positional) < 2 {
		fmt.Fprintf(stderr, "%s\njpcl get: error: the following arguments are required: file, path\n", firstLine(getUsage))
		return 2
	}
	if len(positional) > 2 {
		fmt.Fprintf(stderr, "%s\njpcl get: error: unrecognized arguments: %s\n", firstLine(getUsage), strings.Join(positional[2:], " "))
		return 2
	}
	file, path := positional[0], positional[1]

	data, err := readInput(file, stdin)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	cfg := jpcl.NewJPConfig(data, jpcl.ConfigOptions{})
	sentinel := &struct{}{}
	value := cfg.GetPath(path, sentinel)
	if value == any(sentinel) {
		fmt.Fprintf(stderr, "no such path: %s\n", path)
		return 1
	}
	if s, ok := value.(string); ok && *raw {
		fmt.Fprintln(stdout, s)
	} else {
		fmt.Fprintln(stdout, toJSON(value, 2, 0))
	}
	return 0
}

func cmdToJSON(args []string, stdout, stderr io.Writer, stdin io.Reader) int {
	fs := flag.NewFlagSet("to-json", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	output := fs.String("o", "", "write to a file instead of stdout")
	fs.StringVar(output, "output", "", "write to a file instead of stdout")
	indent := fs.Int("indent", 2, "spaces per level")
	help := fs.Bool("h", false, "show help")
	fs.BoolVar(help, "help", false, "show help")

	if err := fs.Parse(args); err != nil {
		fmt.Fprintf(stderr, "%s\njpcl to-json: error: %v\n", firstLine(toJSONUsage), err)
		return 2
	}
	if *help {
		fmt.Fprintln(stdout, toJSONUsage)
		return 0
	}
	positional := fs.Args()
	if len(positional) != 1 {
		fmt.Fprintf(stderr, "%s\njpcl to-json: error: expected exactly one file argument\n", firstLine(toJSONUsage))
		return 2
	}

	data, err := readInput(positional[0], stdin)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	text := toJSON(data, *indent, 0) + "\n"
	return writeOutput(*output, text, stdout, stderr)
}

func cmdFromJSON(args []string, stdout, stderr io.Writer, stdin io.Reader) int {
	fs := flag.NewFlagSet("from-json", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	output := fs.String("o", "", "write to a file instead of stdout")
	fs.StringVar(output, "output", "", "write to a file instead of stdout")
	indent := fs.Int("indent", 2, "spaces per level")
	width := fs.Int("width", 88, "column budget for inline arrays")
	sortKeys := fs.Bool("sort-keys", false, "sort keys alphabetically")
	help := fs.Bool("h", false, "show help")
	fs.BoolVar(help, "help", false, "show help")

	if err := fs.Parse(args); err != nil {
		fmt.Fprintf(stderr, "%s\njpcl from-json: error: %v\n", firstLine(fromJSONUsage), err)
		return 2
	}
	if *help {
		fmt.Fprintln(stdout, fromJSONUsage)
		return 0
	}
	positional := fs.Args()
	if len(positional) != 1 {
		fmt.Fprintf(stderr, "%s\njpcl from-json: error: expected exactly one file argument\n", firstLine(fromJSONUsage))
		return 2
	}

	var raw []byte
	var err error
	if positional[0] == "-" {
		raw, err = io.ReadAll(stdin)
	} else {
		raw, err = os.ReadFile(positional[0])
	}
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	value, err := parseJSONOrdered(raw)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	obj, ok := value.(*jpcl.Object)
	if !ok {
		fmt.Fprintln(stderr, "the top level of a .jp document must be a JSON object")
		return 1
	}
	text, err := jpcl.Dumps(obj, formatOptions(*indent, *width, *sortKeys))
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	return writeOutput(*output, text, stdout, stderr)
}

// -- JSON, order-preserving in both directions ---------------------------

func toJSON(value any, indent, level int) string {
	switch v := value.(type) {
	case nil:
		return "null"
	case bool:
		if v {
			return "true"
		}
		return "false"
	case string:
		b, _ := json.Marshal(v)
		return string(b)
	case int64:
		return strconv.FormatInt(v, 10)
	case float64:
		switch {
		case math.IsNaN(v):
			return "NaN"
		case math.IsInf(v, 1):
			return "Infinity"
		case math.IsInf(v, -1):
			return "-Infinity"
		default:
			return strconv.FormatFloat(v, 'g', -1, 64)
		}
	case *jpcl.Object:
		return jsonObject(v, indent, level)
	case []any:
		return jsonArray(v, indent, level)
	default:
		return "null"
	}
}

func jsonObject(obj *jpcl.Object, indent, level int) string {
	keys := obj.Keys()
	if len(keys) == 0 {
		return "{}"
	}
	inner := strings.Repeat(" ", indent*(level+1))
	outer := strings.Repeat(" ", indent*level)
	parts := make([]string, len(keys))
	for i, k := range keys {
		v, _ := obj.Get(k)
		kb, _ := json.Marshal(k)
		parts[i] = fmt.Sprintf("%s: %s", kb, toJSON(v, indent, level+1))
	}
	return "{\n" + inner + strings.Join(parts, ",\n"+inner) + "\n" + outer + "}"
}

func jsonArray(values []any, indent, level int) string {
	if len(values) == 0 {
		return "[]"
	}
	inner := strings.Repeat(" ", indent*(level+1))
	outer := strings.Repeat(" ", indent*level)
	parts := make([]string, len(values))
	for i, v := range values {
		parts[i] = toJSON(v, indent, level+1)
	}
	return "[\n" + inner + strings.Join(parts, ",\n"+inner) + "\n" + outer + "]"
}

func parseJSONOrdered(data []byte) (any, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}
	return decodeJSONValue(dec, tok)
}

func decodeJSONValue(dec *json.Decoder, tok json.Token) (any, error) {
	switch t := tok.(type) {
	case json.Delim:
		switch t {
		case '{':
			obj := jpcl.NewObject()
			for dec.More() {
				keyTok, err := dec.Token()
				if err != nil {
					return nil, err
				}
				key, _ := keyTok.(string)
				valTok, err := dec.Token()
				if err != nil {
					return nil, err
				}
				val, err := decodeJSONValue(dec, valTok)
				if err != nil {
					return nil, err
				}
				obj.Set(key, val)
			}
			if _, err := dec.Token(); err != nil { // closing '}'
				return nil, err
			}
			return obj, nil
		case '[':
			arr := []any{}
			for dec.More() {
				valTok, err := dec.Token()
				if err != nil {
					return nil, err
				}
				val, err := decodeJSONValue(dec, valTok)
				if err != nil {
					return nil, err
				}
				arr = append(arr, val)
			}
			if _, err := dec.Token(); err != nil { // closing ']'
				return nil, err
			}
			return arr, nil
		}
		return nil, fmt.Errorf("unexpected JSON delimiter %v", t)
	case json.Number:
		if i, err := t.Int64(); err == nil {
			return i, nil
		}
		return t.Float64()
	default:
		return t, nil // string, bool, or nil
	}
}
