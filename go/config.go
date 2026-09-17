// config.go
package jpml

import (
	"errors"
	"fmt"
	"os"
	"strings"
)

type ConfigOptions struct {
	DuplicateKeys DuplicateKeys
	Write         StringifyOptions
}

type JPConfig struct {
	Data          *Object
	path          *string
	duplicateKeys DuplicateKeys
	write         StringifyOptions
}

func NewJPConfig(data *Object, opts ConfigOptions) *JPConfig {
	if data == nil {
		data = NewObject()
	}
	return &JPConfig{
		Data:          data,
		duplicateKeys: opts.DuplicateKeys,
		write:         opts.Write,
	}
}

func LoadJPConfig(path string, missingOK bool, opts ConfigOptions) (*JPConfig, error) {
	if missingOK {
		if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
			cfg := NewJPConfig(nil, opts)
			cfg.path = &path
			return cfg, nil
		} else if err != nil {
			return nil, err
		}
	}
	data, err := Load(path, ParseOptions{DuplicateKeys: opts.DuplicateKeys})
	if err != nil {
		return nil, err
	}
	cfg := NewJPConfig(data, opts)
	cfg.path = &path
	return cfg, nil
}

func LoadsJPConfig(text, path string, opts ConfigOptions) (*JPConfig, error) {
	data, err := Parse(text, ParseOptions{DuplicateKeys: opts.DuplicateKeys, Filename: path})
	if err != nil {
		return nil, err
	}
	cfg := NewJPConfig(data, opts)
	if path != "" {
		cfg.path = &path
	}
	return cfg, nil
}

func (c *JPConfig) FilePath() (string, bool) {
	if c.path == nil {
		return "", false
	}
	return *c.path, true
}

func (c *JPConfig) SetFilePath(path string) {
	c.path = &path
}

// -- dotted paths ---------------------------------------------------------

func (c *JPConfig) GetPath(path string, def any) any {
	var node any = c.Data
	for _, part := range strings.Split(path, ".") {
		obj, ok := node.(*Object)
		if !ok {
			return def
		}
		v, exists := obj.Get(part)
		if !exists {
			return def
		}
		node = v
	}
	return node
}

func (c *JPConfig) SetPath(path string, value any) error {
	parts := strings.Split(path, ".")
	node := c.Data
	for i, part := range parts[:len(parts)-1] {
		existing, ok := node.Get(part)
		if !ok || existing == nil {
			child := NewObject()
			node.Set(part, child)
			node = child
			continue
		}
		child, isObject := existing.(*Object)
		if !isObject {
			blocked := strings.Join(parts[:i+1], ".")
			return fmt.Errorf("cannot descend into %q: it holds %T, not a section", blocked, existing)
		}
		node = child
	}
	node.Set(parts[len(parts)-1], value)
	return nil
}

func (c *JPConfig) HasPath(path string) bool {
	sentinel := &struct{}{}
	return c.GetPath(path, sentinel) != any(sentinel)
}

func (c *JPConfig) Section(name string, create bool) (*Object, error) {
	v, ok := c.Data.Get(name)
	if !ok {
		if !create {
			return nil, fmt.Errorf("no such section: %q", name)
		}
		obj := NewObject()
		c.Data.Set(name, obj)
		return obj, nil
	}
	obj, isObject := v.(*Object)
	if !isObject {
		return nil, fmt.Errorf("section %q holds %T, not a section", name, v)
	}
	return obj, nil
}

// -- whole-document operations ---------------------------------------------

func (c *JPConfig) Merge(other *Object, deep bool) {
	mergeInto(c.Data, other, deep)
}

func mergeInto(target, source *Object, deep bool) {
	for _, k := range source.Keys() {
		v, _ := source.Get(k)
		if deep {
			if cur, ok := target.Get(k); ok {
				if curObj, isCurObject := cur.(*Object); isCurObject {
					if srcObj, isSrcObject := v.(*Object); isSrcObject {
						mergeInto(curObj, srcObj, true)
						continue
					}
				}
			}
		}
		target.Set(k, v)
	}
}

func (c *JPConfig) ToDict() *Object {
	return deepCopy(c.Data)
}

func deepCopy(obj *Object) *Object {
	out := NewObject()
	for _, k := range obj.Keys() {
		v, _ := obj.Get(k)
		out.Set(k, deepCopyValue(v))
	}
	return out
}

func deepCopyValue(v any) any {
	switch t := v.(type) {
	case *Object:
		return deepCopy(t)
	case []any:
		out := make([]any, len(t))
		for i, item := range t {
			out[i] = deepCopyValue(item)
		}
		return out
	default:
		return v
	}
}

func (c *JPConfig) Dumps() (string, error) {
	return Dumps(c.Data, c.write)
}

func (c *JPConfig) Save(path string) (string, error) {
	target := path
	if target == "" {
		if c.path == nil {
			return "", errors.New("this config has no path; call Save with a path or SetFilePath first")
		}
		target = *c.path
	}
	if err := Dump(c.Data, target, true, c.write); err != nil {
		return "", err
	}
	c.path = &target
	return target, nil
}

func (c *JPConfig) Reload() error {
	if c.path == nil {
		return errors.New("this config has no path to reload from")
	}
	data, err := Load(*c.path, ParseOptions{DuplicateKeys: c.duplicateKeys})
	if err != nil {
		return err
	}
	c.Data = data
	return nil
}
