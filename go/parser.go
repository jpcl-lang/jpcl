// parser.go
package jpml

import (
	"fmt"
	"strconv"
	"strings"
)

const MaxDepth = 200

type DuplicateKeys string

const (
	DuplicateError DuplicateKeys = "error"
	DuplicateFirst DuplicateKeys = "first"
	DuplicateLast  DuplicateKeys = "last"
)

type ParseOptions struct {
	Filename      string
	DuplicateKeys DuplicateKeys
}

type parser struct {
	scanner       *scanner
	duplicateKeys DuplicateKeys
	depth         int
	defined       map[string]struct{}
}

func newParser(text string, opts ParseOptions) (*parser, error) {
	dk := opts.DuplicateKeys
	if dk == "" {
		dk = DuplicateError
	}
	switch dk {
	case DuplicateError, DuplicateFirst, DuplicateLast:
	default:
		return nil, fmt.Errorf("duplicateKeys must be \"error\", \"first\" or \"last\", got %q", dk)
	}
	return &parser{
		scanner:       newScanner(text, opts.Filename),
		duplicateKeys: dk,
		defined:       make(map[string]struct{}),
	}, nil
}

func Parse(text string, opts ParseOptions) (*Object, error) {
	p, err := newParser(text, opts)
	if err != nil {
		return nil, err
	}
	return p.parse()
}

func (p *parser) parse() (*Object, error) {
	s := p.scanner
	root := NewObject()
	current := root

	for {
		s.skipIgnorable()
		if s.eof() {
			return root, nil
		}
		if ch, ok := s.peek(); ok && ch == '[' {
			next, err := p.parseHeader(root)
			if err != nil {
				return nil, err
			}
			current = next
			continue
		}
		key, value, keyPos, err := p.parseEntry()
		if err != nil {
			return nil, err
		}
		if err := p.store(current, key, value, keyPos); err != nil {
			return nil, err
		}
		if err := p.consumeSeparator(""); err != nil {
			return nil, err
		}
	}
}

func (p *parser) parseHeader(root *Object) (*Object, error) {
	s := p.scanner
	start := s.pos
	s.advance() // '['

	var path []string
	pendingDot := false
	for {
		s.skipInline()
		ch, ok := s.peek()
		if !ok || ch == '\r' || ch == '\n' {
			return nil, s.errorAt("unterminated section header", start)
		}
		if ch == ']' {
			if len(path) == 0 {
				return nil, s.errorAt("section header cannot be empty", start)
			}
			if pendingDot {
				return nil, s.errorHere("section header segment cannot be empty")
			}
			s.advance()
			break
		}
		if ch == '"' || ch == '\'' {
			seg, err := s.scanString()
			if err != nil {
				return nil, err
			}
			path = append(path, seg)
		} else {
			segStart := s.pos
			segment := strings.TrimSpace(s.scanUntil(headerEndChars))
			if segment == "" {
				return nil, s.errorAt("section header segment cannot be empty", segStart)
			}
			path = append(path, segment)
		}
		pendingDot = false

		s.skipInline()
		ch, ok = s.peek()
		if ok && ch == '.' {
			s.advance()
			pendingDot = true
			continue
		}
		if ok && ch == ']' {
			s.advance()
			break
		}
		if !ok || ch == '\r' || ch == '\n' {
			return nil, s.errorAt("unterminated section header", start)
		}
		return nil, s.errorHere(fmt.Sprintf("expected '.' or ']' in section header, found %s", s.describeHere()))
	}

	s.skipInline()
	if ch, ok := s.peek(); ok && ch != '\r' && ch != '\n' {
		return nil, s.errorHere(fmt.Sprintf("unexpected %s after section header", s.describeHere()))
	}

	return p.openSection(root, path, start)
}

func (p *parser) openSection(root *Object, path []string, start int) (*Object, error) {
	s := p.scanner
	id := strings.Join(path, "\x1f")
	if _, seen := p.defined[id]; seen && p.duplicateKeys == DuplicateError {
		return nil, s.errorAt(fmt.Sprintf("section '[%s]' is defined twice", strings.Join(path, ".")), start)
	}
	p.defined[id] = struct{}{}

	node := root
	for depth, part := range path {
		existing, ok := node.Get(part)
		if !ok {
			child := NewObject()
			node.Set(part, child)
			node = child
			continue
		}
		child, isObject := existing.(*Object)
		if !isObject {
			pretty := strings.Join(path[:depth+1], ".")
			return nil, s.errorAt(fmt.Sprintf(
				"cannot open section '[%s]': '%s' is already a non-section value",
				strings.Join(path, "."), pretty,
			), start)
		}
		node = child
	}
	return node, nil
}

func (p *parser) parseEntry() (string, any, int, error) {
	s := p.scanner
	s.skipInline()
	keyPos := s.pos
	key, err := p.parseKey()
	if err != nil {
		return "", nil, 0, err
	}

	s.skipInline()
	ch, ok := s.peek()
	if !ok || ch != ':' {
		return "", nil, 0, s.errorHere(fmt.Sprintf("expected ':' after key %s, found %s", strconv.Quote(key), s.describeHere()))
	}
	s.advance()

	value, err := p.parseOptionalValue()
	if err != nil {
		return "", nil, 0, err
	}
	return key, value, keyPos, nil
}

func (p *parser) parseKey() (string, error) {
	s := p.scanner
	ch, ok := s.peek()
	if !ok {
		return "", s.errorHere("expected a key, found end of file")
	}
	if ch == '"' || ch == '\'' {
		return s.scanString()
	}
	if ch == '}' || ch == ']' {
		return "", s.errorHere(fmt.Sprintf("unexpected %s", s.describeHere()))
	}

	start := s.pos
	key := strings.TrimSpace(s.scanUntil(keyEndChars))
	if key == "" {
		return "", s.errorAt(fmt.Sprintf("expected a key, found %s", s.describeHere()), start)
	}
	return key, nil
}

func (p *parser) parseOptionalValue() (any, error) {
	s := p.scanner
	s.skipInline()
	ch, ok := s.peek()
	if !ok || ch == ',' || ch == '\r' || ch == '\n' {
		return nil, nil
	}
	return p.parseValue()
}

func (p *parser) parseValue() (any, error) {
	s := p.scanner
	ch, ok := s.peek()
	if ok && ch == '{' {
		return p.parseObject()
	}
	if ok && ch == '[' {
		return p.parseArray()
	}
	if ok && (ch == '"' || ch == '\'') {
		return s.scanString()
	}
	return interpretBare(s.scanUntil(valueEndChars)), nil
}

func (p *parser) enter(start int) error {
	p.depth++
	if p.depth > MaxDepth {
		return p.scanner.errorAt(fmt.Sprintf("nesting deeper than %d levels", MaxDepth), start)
	}
	return nil
}

func (p *parser) parseObject() (*Object, error) {
	s := p.scanner
	start := s.pos
	if err := p.enter(start); err != nil {
		return nil, err
	}
	s.advance() // '{'
	obj := NewObject()

	for {
		s.skipIgnorable()
		ch, ok := s.peek()
		if !ok {
			return nil, s.errorAt("unterminated object: missing '}'", start)
		}
		if ch == '}' {
			s.advance()
			p.depth--
			return obj, nil
		}
		if ch == ',' {
			s.advance()
			continue
		}
		key, value, keyPos, err := p.parseEntry()
		if err != nil {
			return nil, err
		}
		if err := p.store(obj, key, value, keyPos); err != nil {
			return nil, err
		}
		if err := p.consumeSeparator("}"); err != nil {
			return nil, err
		}
	}
}

func (p *parser) parseArray() ([]any, error) {
	s := p.scanner
	start := s.pos
	if err := p.enter(start); err != nil {
		return nil, err
	}
	s.advance() // '['
	items := []any{}

	for {
		s.skipIgnorable()
		ch, ok := s.peek()
		if !ok {
			return nil, s.errorAt("unterminated array: missing ']'", start)
		}
		if ch == ']' {
			s.advance()
			p.depth--
			return items, nil
		}
		if ch == ',' {
			s.advance()
			continue
		}
		if ch == '}' {
			return nil, s.errorAt("unterminated array: found '}' before ']'", start)
		}
		value, err := p.parseValue()
		if err != nil {
			return nil, err
		}
		items = append(items, value)
		if err := p.consumeSeparator("]"); err != nil {
			return nil, err
		}
	}
}

func (p *parser) consumeSeparator(closing string) error {
	s := p.scanner
	s.skipInline()
	ch, ok := s.peek()
	if !ok || ch == '\r' || ch == '\n' {
		return nil
	}
	if ch == ',' {
		s.advance()
		return nil
	}
	if closing != "" && string(ch) == closing {
		return nil
	}
	if closing == "" && ch == '[' {
		return s.errorHere("expected a line break before a section header")
	}
	expected := "',' or a line break"
	if closing != "" {
		expected = fmt.Sprintf("',', a line break or '%s'", closing)
	}
	return s.errorHere(fmt.Sprintf("expected %s after value, found %s", expected, s.describeHere()))
}

func (p *parser) store(target *Object, key string, value any, keyPos int) error {
	if _, exists := target.Get(key); exists {
		if p.duplicateKeys == DuplicateError {
			return p.scanner.errorAt(fmt.Sprintf("duplicate key %s", strconv.Quote(key)), keyPos)
		}
		if p.duplicateKeys == DuplicateFirst {
			return nil
		}
	}
	target.Set(key, value)
	return nil
}
