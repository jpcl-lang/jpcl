// writer.go
package jpml

import (
	"fmt"
	"math"
	"reflect"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

var bareKeyPattern = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9_\-]*$`)

var stringEscapes = map[rune]string{
	'\\': `\\`, '"': `\"`, '\n': `\n`, '\r': `\r`, '\t': `\t`, '\b': `\b`, '\f': `\f`,
}

type StringifyOptions struct {
	Indent      int
	Width       int
	SortKeys    bool
	EnsureASCII bool
	Default     func(value any) any
}

func DefaultStringifyOptions() StringifyOptions {
	return StringifyOptions{Indent: 2, Width: 88}
}

func Dumps(obj *Object, opts StringifyOptions) (string, error) {
	if opts.Indent < 0 {
		return "", newEncodeError("indent must be >= 0")
	}
	w := &writerState{
		indent:      strings.Repeat(" ", opts.Indent),
		width:       opts.Width,
		sortKeys:    opts.SortKeys,
		ensureASCII: opts.EnsureASCII,
		defaultFunc: opts.Default,
		active:      make(map[any]struct{}),
	}
	return w.document(obj)
}

type writerState struct {
	indent      string
	width       int
	sortKeys    bool
	ensureASCII bool
	defaultFunc func(any) any
	active      map[any]struct{}
}

type kv struct {
	key   string
	value any
}

func (w *writerState) items(obj *Object) []kv {
	keys := obj.Keys()
	out := make([]kv, len(keys))
	for i, k := range keys {
		v, _ := obj.Get(k)
		out[i] = kv{k, v}
	}
	if w.sortKeys {
		sort.Slice(out, func(i, j int) bool { return out[i].key < out[j].key })
	}
	return out
}

func (w *writerState) document(root *Object) (string, error) {
	var preamble []string
	var blocks []string

	for _, item := range w.items(root) {
		if obj, ok := item.value.(*Object); ok {
			var body []string
			for _, sub := range w.items(obj) {
				line, err := w.entry(sub.key, sub.value, 0)
				if err != nil {
					return "", err
				}
				body = append(body, line)
			}
			header := "[" + w.formatKey(item.key) + "]"
			blocks = append(blocks, strings.Join(append([]string{header}, body...), "\n"))
		} else {
			line, err := w.entry(item.key, item.value, 0)
			if err != nil {
				return "", err
			}
			preamble = append(preamble, line)
		}
	}

	if len(preamble) > 0 {
		blocks = append([]string{strings.Join(preamble, "\n")}, blocks...)
	}
	if len(blocks) == 0 {
		return "", nil
	}
	return strings.Join(blocks, "\n\n") + "\n", nil
}

func (w *writerState) entry(key string, value any, level int) (string, error) {
	prefix := strings.Repeat(w.indent, level) + w.formatKey(key) + ":"
	if value == nil {
		return prefix, nil
	}
	rendered, err := w.render(value, level, utf8.RuneCountInString(prefix)+1)
	if err != nil {
		return "", err
	}
	return prefix + " " + rendered, nil
}

func (w *writerState) formatKey(key string) string {
	if bareKeyPattern.MatchString(key) {
		return key
	}
	return w.formatString(key)
}

func (w *writerState) formatString(value string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range value {
		if esc, ok := stringEscapes[r]; ok {
			b.WriteString(esc)
		} else if r < ' ' || r == 0x7f {
			b.WriteString(escapeChar(r))
		} else if w.ensureASCII && r > 0x7E {
			b.WriteString(escapeChar(r))
		} else {
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
	return b.String()
}

func escapeChar(r rune) string {
	code := int(r)
	if code > 0xFFFF {
		code -= 0x10000
		high := 0xD800 + (code >> 10)
		low := 0xDC00 + (code & 0x3FF)
		return fmt.Sprintf("\\u%04x\\u%04x", high, low)
	}
	return fmt.Sprintf("\\u%04x", code)
}

func (w *writerState) formatFloat(v float64) string {
	if math.IsNaN(v) {
		return "nan"
	}
	if math.IsInf(v, 1) {
		return "inf"
	}
	if math.IsInf(v, -1) {
		return "-inf"
	}
	return strconv.FormatFloat(v, 'g', -1, 64)
}

func (w *writerState) render(value any, level, column int) (string, error) {
	switch v := value.(type) {
	case nil:
		return "null", nil
	case bool:
		if v {
			return "true", nil
		}
		return "false", nil
	case string:
		return w.formatString(v), nil
	case int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64:
		return fmt.Sprintf("%d", v), nil
	case float32:
		return w.formatFloat(float64(v)), nil
	case float64:
		return w.formatFloat(v), nil
	case *Object:
		return w.renderObject(v, level)
	case []byte:
		return "", newEncodeError("binary data has no .jp representation; decode or encode it to a string first")
	case []any:
		return w.renderArray(v, level, column)
	default:
		return w.renderDefault(value, level, column)
	}
}

func (w *writerState) renderDefault(value any, level, column int) (string, error) {
	if w.defaultFunc == nil {
		return "", newEncodeError("value of type %T is not representable in .jp; set Default to convert it", value)
	}
	converted := w.defaultFunc(value)
	if reflect.TypeOf(converted) == reflect.TypeOf(value) {
		return "", newEncodeError("Default returned an unconverted %T", value)
	}
	return w.render(converted, level, column)
}

func (w *writerState) renderObject(obj *Object, level int) (string, error) {
	if err := w.enterGuard(obj); err != nil {
		return "", err
	}
	defer w.exitGuard(obj)

	items := w.items(obj)
	if len(items) == 0 {
		return "{}", nil
	}
	lines := make([]string, len(items))
	for i, item := range items {
		line, err := w.entry(item.key, item.value, level+1)
		if err != nil {
			return "", err
		}
		lines[i] = line
	}
	indent := strings.Repeat(w.indent, level)
	return "{\n" + strings.Join(lines, ",\n") + "\n" + indent + "}", nil
}

func (w *writerState) renderArray(values []any, level, column int) (string, error) {
	if err := w.enterGuard(values); err != nil {
		return "", err
	}
	defer w.exitGuard(values)

	if len(values) == 0 {
		return "[]", nil
	}
	parts := make([]string, len(values))
	for i, v := range values {
		rendered, err := w.render(v, level+1, column)
		if err != nil {
			return "", err
		}
		parts[i] = rendered
	}

	inline := "[" + strings.Join(parts, ", ") + "]"
	if !strings.Contains(inline, "\n") && column+utf8.RuneCountInString(inline) <= w.width {
		return inline, nil
	}
	pad := strings.Repeat(w.indent, level+1)
	lines := make([]string, len(parts))
	for i, p := range parts {
		lines[i] = pad + p
	}
	indent := strings.Repeat(w.indent, level)
	return "[\n" + strings.Join(lines, ",\n") + "\n" + indent + "]", nil
}

func guardKeyFor(container any) (any, bool) {
	switch v := container.(type) {
	case *Object:
		return v, true
	case []any:
		if len(v) == 0 {
			return nil, false
		}
		return &v[0], true
	}
	return nil, false
}

func (w *writerState) enterGuard(container any) error {
	key, ok := guardKeyFor(container)
	if !ok {
		return nil
	}
	if _, active := w.active[key]; active {
		return newEncodeError("circular reference detected")
	}
	w.active[key] = struct{}{}
	return nil
}

func (w *writerState) exitGuard(container any) {
	key, ok := guardKeyFor(container)
	if !ok {
		return
	}
	delete(w.active, key)
}
