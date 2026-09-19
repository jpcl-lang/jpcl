// scanner.go
package jpcl

import (
	"fmt"
	"math"
	"strconv"
	"strings"
)

const (
	inlineSpaceChars = " \t\f\v"
	valueEndChars    = ",}]\r\n#"
	keyEndChars      = ":,{}[]\"'\r\n#"
	headerEndChars   = ".]\r\n#"
)

var simpleEscapes = map[rune]rune{
	'"': '"', '\'': '\'', '\\': '\\', '/': '/', '0': 0,
	'a': '\a', 'b': '\b', 'e': 0x1b, 'f': '\f', 'n': '\n', 'r': '\r', 't': '\t', 'v': '\v',
}

var hexEscapeWidth = map[rune]int{'x': 2, 'u': 4, 'U': 8}

var keywords = map[string]any{
	"true": true, "false": false,
	"null": nil, "none": nil, "nil": nil,
	"nan":       math.NaN(),
	"inf":       math.Inf(1),
	"infinity":  math.Inf(1),
	"+inf":      math.Inf(1),
	"+infinity": math.Inf(1),
	"-inf":      math.Inf(-1),
	"-infinity": math.Inf(-1),
}

func isHexDigit(r rune) bool {
	return (r >= '0' && r <= '9') || (r >= 'a' && r <= 'f') || (r >= 'A' && r <= 'F')
}

func parseNumber(text string) (any, bool) {
	sign := ""
	body := text
	if body != "" && (body[0] == '+' || body[0] == '-') {
		sign = body[:1]
		body = body[1:]
	}
	body = strings.ReplaceAll(body, "_", "")
	if body == "" {
		return nil, false
	}
	lower := strings.ToLower(body)
	if strings.HasPrefix(lower, "0x") || strings.HasPrefix(lower, "0o") || strings.HasPrefix(lower, "0b") {
		n, err := strconv.ParseInt(sign+body, 0, 64)
		if err != nil {
			return nil, false
		}
		return n, true
	}
	if n, err := strconv.ParseInt(sign+body, 10, 64); err == nil {
		return n, true
	}
	if f, err := strconv.ParseFloat(sign+body, 64); err == nil {
		return f, true
	}
	return nil, false
}

func interpretBare(text string) any {
	token := strings.TrimSpace(text)
	if token == "" {
		return nil
	}
	if kw, ok := keywords[strings.ToLower(token)]; ok {
		return kw
	}
	if n, ok := parseNumber(token); ok {
		return n
	}
	return token
}

type scanner struct {
	text     []rune
	pos      int
	filename string
}

func newScanner(text, filename string) *scanner {
	text = strings.TrimLeft(text, "\uFEFF")
	return &scanner{text: []rune(text), filename: filename}
}

func (s *scanner) eof() bool {
	return s.pos >= len(s.text)
}

func (s *scanner) peek() (rune, bool) {
	if s.pos >= len(s.text) {
		return 0, false
	}
	return s.text[s.pos], true
}

func (s *scanner) advance() {
	s.pos++
}

func (s *scanner) errorAt(message string, pos int) *DecodeError {
	return newDecodeError(message, string(s.text), pos, s.filename)
}

func (s *scanner) errorHere(message string) *DecodeError {
	return s.errorAt(message, s.pos)
}

func (s *scanner) describeHere() string {
	ch, ok := s.peek()
	if !ok {
		return "end of file"
	}
	if ch == '\r' || ch == '\n' {
		return "end of line"
	}
	return strconv.QuoteRune(ch)
}

func (s *scanner) skipInline() {
	for s.pos < len(s.text) {
		ch := s.text[s.pos]
		switch {
		case strings.ContainsRune(inlineSpaceChars, ch):
			s.pos++
		case ch == '#':
			for s.pos < len(s.text) && s.text[s.pos] != '\r' && s.text[s.pos] != '\n' {
				s.pos++
			}
		default:
			return
		}
	}
}

func (s *scanner) skipIgnorable() {
	for s.pos < len(s.text) {
		ch := s.text[s.pos]
		switch {
		case strings.ContainsRune(inlineSpaceChars, ch), ch == '\r', ch == '\n':
			s.pos++
		case ch == '#':
			for s.pos < len(s.text) && s.text[s.pos] != '\r' && s.text[s.pos] != '\n' {
				s.pos++
			}
		default:
			return
		}
	}
}

func (s *scanner) scanUntil(terminators string) string {
	start := s.pos
	for s.pos < len(s.text) && !strings.ContainsRune(terminators, s.text[s.pos]) {
		s.pos++
	}
	return string(s.text[start:s.pos])
}

func (s *scanner) scanString() (string, error) {
	start := s.pos
	quote := s.text[start]
	i := start + 1
	literalFrom := i
	var b strings.Builder

	for {
		if i >= len(s.text) {
			return "", s.errorAt("unterminated string literal", start)
		}
		ch := s.text[i]
		if ch == quote {
			b.WriteString(string(s.text[literalFrom:i]))
			s.pos = i + 1
			return b.String(), nil
		}
		if ch == '\r' || ch == '\n' {
			return "", s.errorAt("unterminated string literal (use \\n for a line break)", start)
		}
		if ch != '\\' {
			i++
			continue
		}

		b.WriteString(string(s.text[literalFrom:i]))
		escapeAt := i
		i++
		if i >= len(s.text) {
			return "", s.errorAt("unterminated escape sequence", escapeAt)
		}
		marker := s.text[i]

		if r, ok := simpleEscapes[marker]; ok {
			b.WriteRune(r)
			i++
		} else if _, ok := hexEscapeWidth[marker]; ok {
			decoded, next, err := s.scanHexEscape(i, escapeAt)
			if err != nil {
				return "", err
			}
			b.WriteString(decoded)
			i = next
		} else if marker == '\r' || marker == '\n' {
			i++
			if marker == '\r' && i < len(s.text) && s.text[i] == '\n' {
				i++
			}
			for i < len(s.text) && strings.ContainsRune(inlineSpaceChars, s.text[i]) {
				i++
			}
		} else {
			return "", s.errorAt(fmt.Sprintf("invalid escape sequence '\\%c'", marker), escapeAt)
		}
		literalFrom = i
	}
}

func (s *scanner) scanHexEscape(markerAt, escapeAt int) (string, int, error) {
	marker := s.text[markerAt]
	width := hexEscapeWidth[marker]
	digitsStart := markerAt + 1
	digitsEnd := digitsStart + width

	if digitsEnd > len(s.text) || !allHexDigits(s.text[digitsStart:digitsEnd]) {
		return "", 0, s.errorAt(fmt.Sprintf("'\\%c' escape needs %d hex digits", marker, width), escapeAt)
	}
	digits := s.text[digitsStart:digitsEnd]
	code, _ := strconv.ParseInt(string(digits), 16, 64)
	i := digitsEnd

	if marker == 'u' && code >= 0xD800 && code <= 0xDBFF &&
		i+1 < len(s.text) && s.text[i] == '\\' && s.text[i+1] == 'u' {
		lowStart, lowEnd := i+2, i+6
		if lowEnd <= len(s.text) && allHexDigits(s.text[lowStart:lowEnd]) {
			low, _ := strconv.ParseInt(string(s.text[lowStart:lowEnd]), 16, 64)
			if low >= 0xDC00 && low <= 0xDFFF {
				code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
				i = lowEnd
			}
		}
	}

	if code >= 0xD800 && code <= 0xDFFF {
		return "", 0, s.errorAt(fmt.Sprintf("'\\%c%s' is an unpaired surrogate", marker, string(digits)), escapeAt)
	}
	if code > 0x10FFFF {
		return "", 0, s.errorAt(fmt.Sprintf("'\\%c%s' is outside the Unicode range", marker, string(digits)), escapeAt)
	}
	return string(rune(code)), i, nil
}

func allHexDigits(runes []rune) bool {
	for _, r := range runes {
		if !isHexDigit(r) {
			return false
		}
	}
	return true
}
