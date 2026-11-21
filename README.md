<!-- README.md -->

# JPCL

**A configuration language that borrows TOML's sections and JSON's nesting.**

[![CI (npm)](https://github.com/jpcl-lang/jpcl/actions/workflows/ci-npm.yml/badge.svg)](https://github.com/jpcl-lang/jpcl/actions/workflows/ci-npm.yml)
[![CI (py)](https://github.com/jpcl-lang/jpcl/actions/workflows/ci-py.yml/badge.svg)](https://github.com/jpcl-lang/jpcl/actions/workflows/ci-py.yml)
[![CI (go)](https://github.com/jpcl-lang/jpcl/actions/workflows/ci-go.yml/badge.svg)](https://github.com/jpcl-lang/jpcl/actions/workflows/ci-go.yml)
[![npm](https://img.shields.io/npm/v/jpcl-lang)](https://www.npmjs.com/package/jpcl-lang)
[![PyPI](https://img.shields.io/pypi/v/jpcl)](https://pypi.org/project/jpcl/)
[![Go Reference](https://pkg.go.dev/badge/github.com/jpcl-lang/jpcl/go.svg)](https://pkg.go.dev/github.com/jpcl-lang/jpcl/go)

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

## Implementations

All three share the same format and the same writer, so a file written by one
reads back unchanged in the others. A grammar, error
message or writer change belongs in every implementation.

| Language | Package | Install | Source |
| --- | --- | --- | --- |
| TypeScript / JavaScript | [`jpcl-lang`](https://www.npmjs.com/package/jpcl-lang) | `npm install jpcl-lang` | [`npm/`](npm) |
| Python | [`jpcl`](https://pypi.org/project/jpcl/) | `pip install jpcl` | [`py/`](py) |
| Go | [`github.com/jpcl-lang/jpcl/go`](https://pkg.go.dev/github.com/jpcl-lang/jpcl/go) | `go get github.com/jpcl-lang/jpcl/go` | [`go/`](go) |

None of them have runtime dependencies.

* **TypeScript:** ships ESM with types. Node 20+, Bun and Deno. Also
  `bun add`, `pnpm add` or `yarn add`.
* **Python:** Python 3.14+. Also `uv add jpcl`.
* **Go:** standard library only. See [`go/go.mod`](go/go.mod) for the minimum
  Go version.

Rust and Zig implementations are planned in [`rust/`](rust) and [`zig/`](zig).

---

## Why another format

JSON has no comments, demands quotes on every key, and rejects a trailing
comma. TOML has comments and headers, but nesting anything non-trivial means
either deeply dotted keys or a table per level.

`.jp` takes the half of each that suits configuration files people edit by hand:

* **Sections for the top level.** `[SERVER_ID]` reads better than another brace.
* **JSON for everything below it.** Nest objects and arrays as deep as you like.
* **No ceremony.** Unquoted keys, comments anywhere, trailing commas ignored.
* **Empty values are legal.** `disabled_channels:,` means the key exists and has
  no value yet — a real state in configs that JSON can only spell as `null`.

It is a small, fully specified format with a strict parser, precise error
messages, and a deterministic writer, so files stay stable when a program
rewrites them.

---

## The format

### Sections

A `[NAME]` header opens a root key. Everything below it, until the next header,
belongs to that section.

```jp
[SERVER_ID]
prefix: "!"
```

Headers may be dotted to nest, and quoted when a name contains a dot:

```jp
[guild.limits]        $$ -> { guild: { limits: {...} } }
["weird.name"]        $$ -> { "weird.name": {...} }
```

Key/value pairs written *before* the first header land at the document root:

```jp
version: 2

[SERVER_ID]
prefix: "!"
```

### Entries

An entry is `key: value`. Keys need no quotes; a bare key may contain spaces but
not brackets, commas or quotes — quote it if it needs those.

Entries are separated by a line break, a comma, or both. Trailing and repeated
commas are accepted:

```jp
[SERVER_ID]
a: 1
b: {x: 1, y: 2,}
c: [1, 2, 3,]
```

### Empty values

A key with nothing after the colon parses to null:

```jp
config: {
  disabled_channels:,      $$ -> null
  timeout:                 $$ -> null
}
```

Because of this, **a value must start on the same line as its `:`**. An opening
`{` or `[` goes on the colon's line; its contents may then wrap freely.

### Values

| Type | Examples | TypeScript | Python | Go |
| --- | --- | --- | --- | --- |
| String | `"hello"`, `'hello'`, `hello world` (unquoted) | `string` | `str` | `string` |
| Integer | `42`, `-7`, `1_000`, `0xff`, `0o755`, `0b1010` | `number`, or `bigint` beyond 2^53 | `int` | `int64` |
| Float | `3.5`, `1e3`, `inf`, `-inf`, `nan` | `number` | `float` | `float64` |
| Boolean | `true`, `false` (case-insensitive, so `True` works too) | `boolean` | `bool` | `bool` |
| Null | `null`, `none`, `nil`, or nothing at all | `null` | `None` | `nil` |
| Object | `{a: 1, b: 2}` | plain object | `dict` | `*jpcl.Object` |
| Array | `[1, 2, 3]` | array | `list` | `[]any` |

Unquoted values are read as a keyword first, then a number, then a plain string.
Quote a value if it contains `$$`, `$*`, a comma, a bracket, or
leading/trailing whitespace you want to keep. A lone `#` or `$` is just text, so
`colour: #8496AD` and `price: $5` need no quotes.

Strings honour the usual escapes — `\n`, `\t`, `\\`, `\"`, `\uXXXX`,
`\U0001F600`, plus `\` at end of line to continue onto the next.

### Comments

`$$` runs to the end of the line. `$* ... *$` wraps a block comment that may
span lines. Both are allowed anywhere, including inside objects and arrays.

```jp
$* Server settings,
   one section per guild *$
[SERVER_ID]
prefix: "!"   $$ the command prefix
```

---

## TypeScript / JavaScript

```ts
import { load } from "jpcl-lang";

await load("data/servers.jp");
// {
//   SERVER_ID: { config: { disabled_channels: null, disabled_users: [9892, 82082, 8209] } },
//   SERVER_ID_2: { prefix: "!", modules: { moderation: true, fun: false } }
// }
```

### Read and write files

```ts
import * as jpcl from "jpcl-lang";

// -> object
const data = await jpcl.load("data/servers.jp");

// Formatted, atomic write
await jpcl.dump(data, "data/servers.jp");

// -> string, and back again
const text = jpcl.dumps(data);
const again = jpcl.loads(text);
```

Every file function has a synchronous twin — `loadSync`, `dumpSync`,
`loadDirSync` — for startup code that would rather not `await`. Paths may be
strings or `file:` URLs. `parse` and `stringify` are aliases of `loads` and
`dumps` if you prefer the `JSON` spelling.

Options worth knowing:

```ts
// duplicateKeys: "error" (default), "first", "last"
jpcl.loads(text, { duplicateKeys: "last" });

// integers: "auto" (default), "bigint", "number"
jpcl.loads(text, { integers: "bigint" });

// Also: width, ensureAscii
jpcl.dumps(data, { indent: 4, sortKeys: true });

jpcl.dumps(data, { default: (value) => (value instanceof Date ? value.toISOString() : String(value)) });
```

### Big numbers stay exact

JavaScript numbers lose precision past 2^53, which is exactly where Discord
snowflakes and other 64-bit IDs live. By default an integer that fits safely
parses to a `number` and one that doesn't parses to a `bigint`, so no digit is
ever silently changed:

```ts
jpcl.loads("[s]\nsmall: 42\nid: 111111111111111111\n");
// { s: { small: 42, id: 111111111111111111n } }
```

`bigint` values are written back as plain integers. Pass `integers: "bigint"` to
get a `bigint` for every integer, or `integers: "number"` to always get a
`number` and accept the precision loss.

### Edit a config in place

`JPConfig` is a map-like object that remembers the file it came from.

```ts
import { JPConfig } from "jpcl-lang";

const cfg = await JPConfig.load("data/servers.jp", { missingOk: true });

// Plain object access
cfg.data.SERVER_ID.prefix;

// Also: set, has, delete, keys, size
cfg.get("SERVER_ID");

// Never throws
cfg.getPath("SERVER_ID.config.disabled_users", []);

// Creates missing sections
cfg.setPath("SERVER_ID.config.disabled_users", [9892]);

cfg.hasPath("SERVER_ID.prefix");
cfg.section("NEW_SERVER", { create: true }).prefix = "?";

// Deep merge
cfg.merge({ SERVER_ID: { modules: { fun: true } } });

// Atomic, back to its own path
await cfg.save();

// Discard in-memory changes
await cfg.reload();

// Deep copy as a plain object
cfg.toObject();
```

`loadSync`, `saveSync` and `reloadSync` do the same without promises.

`missingOk: true` gives an empty config bound to the path, which is what you
want for a program that writes its config on first run. Formatting options given
when loading or constructing are remembered by `save()`:

```ts
const cfg = await JPConfig.load("data/servers.jp", { indent: 4, sortKeys: true });
```

### Load a whole folder

```ts
// { servers: {...}, roles: {...} }
const config = await jpcl.loadDir("data");

// { "1234567890": {...}, ... }
const guilds = await jpcl.loadDir("data/guilds");

const everything = await jpcl.loadDir("data", { recursive: true });
```

Each file becomes one key, named after the file. Nested files are keyed by
their relative path (`"guilds/1234567890"`), and `pattern` picks which files
count (default `"*.jp"`).

### Errors

Every error derives from `JPError`. `JPDecodeError` carries `.line`, `.col`,
`.pos`, `.filename` and `.rawMessage` (see [Errors](#errors) for the message
format). `JPEncodeError` explains what could not be serialised — an
unsupported type, a non-string key, a circular reference.

### In the browser

`jpcl-lang/core` is the same parser and writer without any filesystem access,
for browsers, workers and edge runtimes:

```ts
import { loads, dumps, JPDecodeError } from "jpcl-lang/core";
```

---

## Python

```python
>>> import jpcl
>>> jpcl.load("data/servers.jp")
{'SERVER_ID': {'config': {'disabled_channels': None,
                          'disabled_users': [9892, 82082, 8209]}},
 'SERVER_ID_2': {'prefix': '!', 'modules': {'moderation': True, 'fun': False}}}
```

### Read and write files

```python
import jpcl

# -> dict
data = jpcl.load("data/servers.jp")

# Formatted, atomic write
jpcl.dump(data, "data/servers.jp")

# -> str, and back again
text = jpcl.dumps(data)
data = jpcl.loads(text)
```

Options worth knowing:

```python
# duplicate_keys: "error" (default), "first", "last"
jpcl.load("servers.jp", duplicate_keys="last")

# Also: width, ensure_ascii
jpcl.dumps(data, indent=4, sort_keys=True)

# Convert datetimes and friends
jpcl.dumps(data, default=str)
```

### Edit a config in place

`JPConfig` is a `MutableMapping` that remembers the file it came from.

```python
from jpcl import JPConfig

cfg = JPConfig.load("data/servers.jp", missing_ok=True)

# Plain dict access
cfg["SERVER_ID"]["prefix"]

# Never raises
cfg.get_path("SERVER_ID.config.disabled_users", [])

# Creates missing sections
cfg.set_path("SERVER_ID.config.disabled_users", [9892])

cfg.has_path("SERVER_ID.prefix")
cfg.section("NEW_SERVER", create=True)["prefix"] = "?"

# Deep merge
cfg.merge({"SERVER_ID": {"modules": {"fun": True}}})

# Atomic, back to its own path
cfg.save()

# Discard in-memory changes
cfg.reload()

# Deep copy as a plain dict
cfg.to_dict()
```

`missing_ok=True` gives an empty config bound to the path, which is what you
want for a program that writes its config on first run. Formatting options given
to the constructor are remembered by `save()`:

```python
cfg = JPConfig.load("data/servers.jp", indent=4, sort_keys=True)
```

### Load a whole folder

```python
# {'servers': {...}, 'roles': {...}}
config = jpcl.load_dir("data")

# {'1234567890': {...}, ...}
guilds = jpcl.load_dir("data/guilds")

everything = jpcl.load_dir("data", recursive=True)
```

Each file becomes one key, named after the file.

### Errors

Every error derives from `jpcl.JPError`. `JPDecodeError` (a `ValueError`)
carries `.line`, `.col`, `.pos`, `.filename` and `.raw_message`.
`JPEncodeError` (a `TypeError`) explains what could not be serialised — an
unsupported type, a non-string key, a circular reference.

---

## Go

```go
package main

import (
	"fmt"

	jpcl "github.com/jpcl-lang/jpcl/go"
)

func main() {
	data, err := jpcl.Load("data/servers.jp", jpcl.ParseOptions{})
	if err != nil {
		panic(err)
	}

	// [SERVER_ID SERVER_ID_2]
	fmt.Println(data.Keys())
}
```

The module path ends in `/go` but the package is named `jpcl`, so import it
with the `jpcl` alias as above. The API follows the same rules as the other
implementations, but leans on Go idioms rather than mirroring their syntax.

### Ordered objects

`jpcl.Object` is an **ordered map** — a small type this package defines
itself, because Go's built-in `map` does not preserve insertion order and
the writer needs to reproduce keys in the order they were written:

```go
obj := jpcl.NewObject()
obj.Set("prefix", "!")
obj.Set("id", int64(1234567890))

// "!", true
value, ok := obj.Get("prefix")

// "prefix", then "id" -- insertion order
for _, key := range obj.Keys() {
	fmt.Println(key)
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

### Reading

```go
// From a path
data, err := jpcl.Load("data/servers.jp", jpcl.ParseOptions{})

// From a string
data, err := jpcl.Parse(text, jpcl.ParseOptions{})
```

`ParseOptions`:

```go
type ParseOptions struct {
	// Used in error messages
	Filename string

	// DuplicateError (default), DuplicateFirst, DuplicateLast
	DuplicateKeys DuplicateKeys
}
```

Every error from a malformed document is a `*jpcl.DecodeError`. It carries
`Message`, `Doc`, `Pos`, `Line`, `Col` and `Filename` fields, and implements
the standard `error` interface:

```go
data, err := jpcl.Load(path, jpcl.ParseOptions{})

var decodeErr *jpcl.DecodeError
if errors.As(err, &decodeErr) {
	fmt.Println(decodeErr.Line, decodeErr.Col)
}
```

### Load a whole folder

```go
// map[string]*jpcl.Object{"servers": ..., "roles": ...}
config, err := jpcl.LoadDir("data", "", false, jpcl.ParseOptions{})

guilds, err := jpcl.LoadDir("data/guilds", "", false, jpcl.ParseOptions{})

// Recursive
everything, err := jpcl.LoadDir("data", "", true, jpcl.ParseOptions{})
```

Each file becomes one entry, named after the file (without its `.jp` suffix).
The returned value is a plain Go `map`, since it is a lookup table rather
than a document you write back out — unlike `*jpcl.Object`, its iteration
order is not meaningful.

### Writing

```go
text, err := jpcl.Dumps(obj, jpcl.DefaultStringifyOptions())

// Atomic write
err = jpcl.Dump(obj, "data/servers.jp", true, jpcl.DefaultStringifyOptions())
```

`StringifyOptions`:

```go
type StringifyOptions struct {
	// Spaces per level
	Indent int

	// Column budget for inline arrays
	Width int

	// Sort keys alphabetically instead of insertion order
	SortKeys bool

	// Escape non-ASCII characters
	EnsureASCII bool

	// Convert otherwise-unsupported types
	Default func(value any) any
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

Every write-side error is a `*jpcl.EncodeError` — an unsupported type
(nothing matches `nil`/`bool`/`string`/an integer type/`float32`/`float64`/
`*jpcl.Object`/`[]any`), a circular reference, or binary data (`[]byte`,
which has no `.jp` representation; convert it to a string first).

### Edit a config in place

`JPConfig` wraps an `*Object` and remembers the file it came from, adding
dotted-path access and atomic saving. Its backing store is a public field,
`Data`, since Go has no subscript operator to stand in for Python's
`cfg["key"]`:

```go
// missingOK: true -- an empty config bound to the path if it doesn't exist yet
cfg, err := jpcl.LoadJPConfig("data/servers.jp", true, jpcl.ConfigOptions{
	Write: jpcl.DefaultStringifyOptions(),
})

// Plain Object access
prefix, _ := cfg.Data.Get("SERVER_ID")

// Never fails on a missing section
cfg.GetPath("SERVER_ID.config.disabled_users", []any{})

// Creates missing sections
cfg.SetPath("SERVER_ID.config.disabled_users", []any{int64(9892)})

cfg.HasPath("SERVER_ID.prefix")

// create: true
section, _ := cfg.Section("NEW_SERVER", true)
section.Set("prefix", "?")

// Deep merge
cfg.Merge(other, true)

// Atomic, back to its own bound path
cfg.Save("")

// Discard in-memory changes
cfg.Reload()

// Deep copy as a plain *Object
cfg.ToDict()
```

Unlike the Python and TypeScript versions, formatting options passed to
`ConfigOptions.Write` are fixed at construction time — `Save`/`Dumps` always
use them, with no per-call override, again to avoid the zero-value-ambiguity
problem. Call the package-level `jpcl.Dumps(cfg.Data, customOpts)` directly
for a one-off different format.

---

## Errors

A malformed document points at the exact offending character:

```
data/servers.jp:2:8: expected ':' after key 'prefix', found '"'
    prefix "!"
           ^
```

The TypeScript and Python messages match byte for byte. Go quotes the key
with Go's `%q`, so it reads `after key "prefix"`.

By default a repeated key is an error rather than a silent overwrite. Each
implementation has an option to keep the first or the last value instead.

---

## Command line

Every implementation ships the same `jpcl` command:

```bash
# Validate; non-zero exit on failure
jpcl check data/*.jp

# Reformat in place
jpcl fmt -w data/servers.jp

# Read one value
jpcl get data/servers.jp SERVER_ID.prefix

jpcl to-json -o out.json data/servers.jp
jpcl from-json -o data/servers.jp out.json
```

`-` reads stdin in place of a filename.

| Implementation | Get the command |
| --- | --- |
| TypeScript | `npm install jpcl-lang`, then `npx jpcl ...` |
| Python | `pip install jpcl`, then `jpcl ...` or `python -m jpcl ...` |
| Go | `go install github.com/jpcl-lang/jpcl/go/cmd/jpcl@latest` |

* **TypeScript:** large integers survive `to-json` and `from-json` intact.
* **Go:** flags must come **before** positional arguments (`jpcl fmt -w file.jp`,
  not `jpcl fmt file.jp -w`) — a limitation of Go's standard `flag` package,
  which stops looking for flags at the first non-flag argument. The TypeScript
  and Python versions allow either order.

---

## Round trips

`dumps` is deterministic, so a file rewritten twice is byte-identical:

* every top-level object becomes a `[SECTION]`, separated by a blank line;
* section entries sit one per line, with no separating commas;
* nested objects always expand across lines, `{}` being the only inline form;
* arrays stay inline while they fit inside the width (default 88), then break
  one element per line;
* null is written as an empty value inside objects (`key:`) and as `null`
  inside arrays, since an array element cannot be empty;
* insertion order is preserved unless keys are sorted.

Two things never survive a rewrite, in any implementation:

* **Comments are dropped.** Rewriting a hand-annotated file loses its notes.
* **Root-level scalars move above the first section**, because anything after a
  header would be read back as part of that section.

TypeScript has three more, all from JavaScript itself:

* **Integer-like keys move to the front.** JavaScript objects always list keys
  such as `"1234567890"` first, in ascending order, before every other key. A
  `[1234567890]` section therefore comes out ahead of `[SERVER_ID]` even if the
  file had it last. Pass a `Map` to `dumps` if the order of such keys matters.
* **Whole floats become integers.** JavaScript has one number type, so `1.0`
  reads back as `1` and is written as `1`.
* **`undefined` follows `JSON.stringify`.** Object properties whose value is
  `undefined` are skipped, and `undefined` array elements are written as
  `null`. A `Map` is written like an object, and may use integer keys.

---

## Organising your configs

Nothing is enforced, but this layout is what the folder loaders are built for:

```
your-project/
├─ data/
│  ├─ servers.jp            # one file per concern
│  ├─ roles.jp
│  ├─ servers.example.jp    # committed template, safe to publish
│  └─ guilds/               # optional: one file per entity
│     ├─ 1234567890.jp
│     └─ 9876543210.jp
└─ src/
```

A few habits that save pain later:

1. **One file per concern.** A parse error then takes out one feature, not
   everything.
2. **Keep live data out of git**, and commit a template instead:
   ```gitignore
   data/*.jp
   !data/*.example.jp
   ```
3. **Use IDs as section names.** `[1234567890]` parses to the string key
   `"1234567890"`. Python stringifies integer keys on write, so
   `{1234567890: {...}}` round-trips.
4. **Write through `JPConfig`'s save** rather than by hand, so an interrupted
   write cannot truncate a live config.
5. **Validate in CI** with `jpcl check data/*.jp`.

---

## Contributing and releases

Each implementation lives in its own folder with its own tests:

* [`npm/CONTRIBUTING.md`](npm/CONTRIBUTING.md)
* [`py/CONTRIBUTING.md`](py/CONTRIBUTING.md)
* Go: `cd go && go test ./... -race`

Releases are tagged per implementation as `npm/vX.Y.Z`, `py/vX.Y.Z` or
`go/vX.Y.Z`. Each tag only triggers its own publish workflow.

## Licence

MIT.
