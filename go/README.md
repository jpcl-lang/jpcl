# jpcl-go

Go package for JPCL

**A configuration language that borrows TOML's sections and JSON's nesting.**

`.jp` files use `[SECTION]` headers at the top level and `{...}` / `[...]`
structures inside them. Keys need no quotes, `$$` starts a line comment,
`$* ... *$` wraps a block comment, trailing commas are fine, and a value is
allowed to be *empty*.

```jp
[SERVER_ID]
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
```

```go
package main

import (
	"fmt"

	"github.com/jpcl-lang/jpcl-go"
)

func main() {
	data, err := jpcl.Load("data/servers.jp", jpcl.ParseOptions{})
	if err != nil {
		panic(err)
	}
	fmt.Println(data.Keys()) // [SERVER_ID SERVER_ID_2]
}
```

See [`py/README.md`](../py/README.md) for the full rationale behind the
format and the round-trip guarantees the writer makes; this file covers the
Go package's API, which follows the same rules as the Python and TypeScript
implementations but leans on Go idioms rather than mirroring their syntax.

```bash
go get github.com/jpcl-lang/jpcl-go
```

No runtime dependencies -- uses only the standard library. See `go.mod` for
the minimum Go version.

---

## Values

`.jp` documents parse into a small set of concrete Go types, carried around
as `any`:

| `.jp` type | Go type |
| --- | --- |
| Object / section | `*jpcl.Object` |
| Array | `[]any` |
| String | `string` |
| Integer | `int64` |
| Float | `float64` |
| Boolean | `bool` |
| Null / empty value | `nil` |

`jpcl.Object` is an **ordered map** — a small type this package defines
itself, because Go's built-in `map` does not preserve insertion order and
the writer needs to reproduce keys in the order they were written:

```go
obj := jpcl.NewObject()
obj.Set("prefix", "!")
obj.Set("id", int64(1234567890))

value, ok := obj.Get("prefix") // "!", true
for _, key := range obj.Keys() {
    fmt.Println(key) // "prefix", then "id" -- insertion order
}
```

There is no `obj["key"]` syntax (Go has no operator overloading), and no
`map[string]any` support in the writer — building a document means calling
`Set`, not writing a map literal, so the writer's ordering guarantee always
holds.

Integers are `int64`, not arbitrary precision like Python's `int` or
JavaScript's optional `bigint`. A number larger than roughly 9.2×10¹⁸ loses
precision by falling through to `float64`. This is fine for things like
Discord snowflakes but is a real limitation to know about if you need bigger
integers.

---

## Reading

```go
data, err := jpcl.Load("data/servers.jp", jpcl.ParseOptions{})   // from a path
data, err := jpcl.Parse(text, jpcl.ParseOptions{})                // from a string
```

`ParseOptions`:

```go
type ParseOptions struct {
	Filename      string        // used in error messages
	DuplicateKeys DuplicateKeys // DuplicateError (default), DuplicateFirst, DuplicateLast
}
```

Every error from a malformed document is a `*jpcl.DecodeError`, which points
at the exact offending character:

```
data/servers.jp:2:8: expected ':' after key "prefix", found '"'
    prefix "!"
           ^
```

It carries `Message`, `Doc`, `Pos`, `Line`, `Col` and `Filename` fields, and
implements the standard `error` interface, so:

```go
data, err := jpcl.Load(path, jpcl.ParseOptions{})
var decErr *jpcl.DecodeError
if errors.As(err, &decErr) {
	fmt.Println(decErr.Line, decErr.Col)
}
```

By default a repeated key is an error rather than a silent overwrite; pass
`DuplicateKeys: jpcl.DuplicateFirst` or `jpcl.DuplicateLast` if you would
rather it not be.

### Load a whole folder

```go
config, err := jpcl.LoadDir("data", "", false, jpcl.ParseOptions{})
// map[string]*jpcl.Object{"servers": ..., "roles": ...}

guilds, err := jpcl.LoadDir("data/guilds", "", false, jpcl.ParseOptions{})
everything, err := jpcl.LoadDir("data", "", true, jpcl.ParseOptions{}) // recursive
```

Each file becomes one entry, named after the file (without its `.jp` suffix).
The returned value is a plain Go `map`, since it is a lookup table rather
than a document you write back out — unlike `*jpcl.Object`, its iteration
order is not meaningful.

---

## Writing

```go
text, err := jpcl.Dumps(obj, jpcl.DefaultStringifyOptions())
err = jpcl.Dump(obj, "data/servers.jp", true, jpcl.DefaultStringifyOptions()) // atomic write
```

`StringifyOptions`:

```go
type StringifyOptions struct {
	Indent      int    // spaces per level
	Width       int    // column budget for inline arrays
	SortKeys    bool   // sort keys alphabetically instead of insertion order
	EnsureASCII bool   // escape non-ASCII characters
	Default     func(value any) any // convert otherwise-unsupported types
}
```

Go structs have no default field values and no default function arguments,
so `DefaultStringifyOptions()` returns `{Indent: 2, Width: 88}` as a starting
point — build on it rather than a bare `StringifyOptions{}`, whose zero
values (`Indent: 0, Width: 0`) are indistinguishable from an explicit choice:

```go
opts := jpcl.DefaultStringifyOptions()
opts.SortKeys = true
text, err := jpcl.Dumps(obj, opts)
```

Writes are atomic by default when going through `Dump`: the file goes to a
temporary neighbour and is renamed into place, so a crash or a concurrent
reader never sees half a config.

Every write-side error is a `*jpcl.EncodeError` — an unsupported type
(nothing matches `nil`/`bool`/`string`/an integer type/`float32`/`float64`/
`*jpcl.Object`/`[]any`), a circular reference, or binary data (`[]byte`,
which has no `.jp` representation; convert it to a string first).

---

## Round trips

`Dumps` is deterministic, so a document written twice is byte-identical:

* every top-level `*jpcl.Object` value becomes a `[SECTION]`, separated by a
  blank line;
* section entries sit one per line, with no separating commas;
* nested objects always expand across lines, `{}` being the only inline
  form;
* arrays stay inline while they fit inside `Width` (default 88), then break
  one element per line;
* `nil` is written as an empty value inside objects (`key:`) and as `null`
  inside arrays, since an array element cannot be empty;
* insertion order is preserved unless `SortKeys: true`.

Comments are dropped on rewrite, and root-level scalars are hoisted above
the first section — the same two round-trip caveats as the Python and
TypeScript versions.

---

## Editing a config in place: `JPConfig`

`JPConfig` wraps an `*Object` and remembers the file it came from, adding
dotted-path access and atomic saving. Its backing store is a public field,
`Data`, since Go has no subscript operator to stand in for Python's
`cfg["key"]`:

```go
cfg, err := jpcl.LoadJPConfig("data/servers.jp", true, jpcl.ConfigOptions{
	Write: jpcl.DefaultStringifyOptions(),
}) // missingOK: true -- an empty config bound to the path if it doesn't exist yet

prefix, _ := cfg.Data.Get("SERVER_ID") // plain Object access
cfg.GetPath("SERVER_ID.config.disabled_users", []any{})  // never fails on a missing section
cfg.SetPath("SERVER_ID.config.disabled_users", []any{int64(9892)}) // creates missing sections
cfg.HasPath("SERVER_ID.prefix")
sec, _ := cfg.Section("NEW_SERVER", true) // create: true
sec.Set("prefix", "?")
cfg.Merge(other, true) // deep merge
cfg.Save("")           // atomic, back to its own bound path
cfg.Reload()            // discard in-memory changes
cfg.ToDict()             // deep copy as a plain *Object
```

Unlike the Python and TypeScript versions, formatting options passed to
`ConfigOptions.Write` are fixed at construction time — `Save`/`Dumps` always
use them, with no per-call override, again to avoid the zero-value-ambiguity
problem. Call the package-level `jpcl.Dumps(cfg.Data, customOpts)` directly
for a one-off different format.

---

## Command line

```bash
go install github.com/jpcl-lang/jpcl-go/cmd/jpcl@latest

jpcl check data/*.jp                      # validate; non-zero exit on failure
jpcl fmt -w data/servers.jp               # reformat in place
jpcl get data/servers.jp SERVER_ID.prefix # read one value
jpcl to-json -o out.json data/servers.jp
jpcl from-json -o data/servers.jp out.json
```

`-` reads stdin in place of a filename.

Flags must come **before** positional arguments (`jpcl fmt -w file.jp`, not
`jpcl fmt file.jp -w`) — a limitation of Go's standard `flag` package, which
stops looking for flags at the first non-flag argument. This is the one
place the Go CLI's ergonomics are a step behind the Python (`argparse`) and
TypeScript (`util.parseArgs`) versions, which allow either order.

---

## Licence

MIT, same as the rest of the JPCL implementations.
