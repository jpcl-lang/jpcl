// values.go
package jpcl

type Object struct {
	keys   []string
	values map[string]any
}

func NewObject() *Object {
	return &Object{values: make(map[string]any)}
}

func (o *Object) Len() int {
	return len(o.keys)
}

func (o *Object) Get(key string) (any, bool) {
	v, ok := o.values[key]
	return v, ok
}

func (o *Object) Set(key string, value any) {
	if _, exists := o.values[key]; !exists {
		o.keys = append(o.keys, key)
	}
	o.values[key] = value
}

func (o *Object) Keys() []string {
	out := make([]string, len(o.keys))
	copy(out, o.keys)
	return out
}
