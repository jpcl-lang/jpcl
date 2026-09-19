// errors.go
package jpcl

import (
	"fmt"
	"strings"
)

type DecodeError struct {
	Message  string
	Doc      string
	Pos      int
	Line     int
	Col      int
	Filename string
}

func newDecodeError(message, doc string, pos int, filename string) *DecodeError {
	runes := []rune(doc)
	if pos < 0 {
		pos = 0
	}
	if pos > len(runes) {
		pos = len(runes)
	}
	line, col := lineCol(runes, pos)
	return &DecodeError{
		Message:  message,
		Doc:      doc,
		Pos:      pos,
		Line:     line,
		Col:      col,
		Filename: filename,
	}
}

func (e *DecodeError) Error() string {
	filename := e.Filename
	if filename == "" {
		filename = "<string>"
	}
	parts := []string{fmt.Sprintf("%s:%d:%d: %s", filename, e.Line, e.Col, e.Message)}

	lines := strings.Split(e.Doc, "\n")
	if e.Line > 0 && e.Line <= len(lines) {
		source := strings.TrimRight(lines[e.Line-1], "\r")
		prefix := caretPrefix(source, e.Col-1)
		parts = append(parts, "    "+source, "    "+prefix+"^")
	}
	return strings.Join(parts, "\n")
}

func lineCol(runes []rune, pos int) (line, col int) {
	line = 1
	last := -1
	for i := 0; i < pos; i++ {
		if runes[i] == '\n' {
			line++
			last = i
		}
	}
	col = pos - last
	return line, col
}

func caretPrefix(source string, width int) string {
	runes := []rune(source)
	if width > len(runes) {
		width = len(runes)
	}
	out := make([]rune, width)
	for i := 0; i < width; i++ {
		if runes[i] == '\t' {
			out[i] = '\t'
		} else {
			out[i] = ' '
		}
	}
	return string(out)
}

type EncodeError struct {
	Message string
}

func newEncodeError(format string, args ...any) *EncodeError {
	return &EncodeError{Message: fmt.Sprintf(format, args...)}
}

func (e *EncodeError) Error() string {
	return e.Message
}
