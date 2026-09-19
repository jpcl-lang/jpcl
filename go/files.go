// files.go
package jpcl

import (
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const Suffix = ".jp"

func Load(path string, opts ParseOptions) (*Object, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if opts.Filename == "" {
		opts.Filename = path
	}
	return Parse(string(data), opts)
}

func Dump(obj *Object, path string, atomic bool, opts StringifyOptions) error {
	text, err := Dumps(obj, opts)
	if err != nil {
		return err
	}
	if atomic {
		return atomicWrite(path, text)
	}
	return os.WriteFile(path, []byte(text), 0o644)
}

func atomicWrite(path, text string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()

	if _, err := tmp.WriteString(text); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

func LoadDir(dir, pattern string, recursive bool, opts ParseOptions) (map[string]*Object, error) {
	info, err := os.Stat(dir)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("no such config directory: %s", dir)
	}
	if pattern == "" {
		pattern = "*" + Suffix
	}

	var paths []string
	if recursive {
		err = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if d.IsDir() {
				return nil
			}
			matched, err := filepath.Match(pattern, d.Name())
			if err != nil {
				return err
			}
			if matched {
				paths = append(paths, p)
			}
			return nil
		})
		if err != nil {
			return nil, err
		}
	} else {
		matches, err := filepath.Glob(filepath.Join(dir, pattern))
		if err != nil {
			return nil, err
		}
		for _, m := range matches {
			if info, err := os.Stat(m); err == nil && !info.IsDir() {
				paths = append(paths, m)
			}
		}
	}
	sort.Strings(paths)

	result := make(map[string]*Object, len(paths))
	for _, p := range paths {
		rel, err := filepath.Rel(dir, p)
		if err != nil {
			return nil, err
		}
		key := filepath.ToSlash(strings.TrimSuffix(rel, filepath.Ext(rel)))
		obj, err := Load(p, opts)
		if err != nil {
			return nil, err
		}
		result[key] = obj
	}
	return result, nil
}
