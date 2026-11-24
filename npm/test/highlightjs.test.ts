/* npm/test/highlightjs.test.ts */

import { expect, test } from "bun:test";
import hljs from "highlight.js";
import jpcl from "../src/highlightjs.js";

hljs.registerLanguage("jpcl", jpcl);

test("highlights JPCL 2 comments, keys, sections, and nested values", () => {
  const source = [
    "~~ configuration",
    '["guild.name".limits]',
    "config: {items: [1_000, -0xff, False, null, truely, [2]]}",
    'greeting: "hello\\u0021"',
    "empty:",
    "site: http://example.com:8080",
    "colour: #8496AD ~* inline comment *~",
  ].join("\n");

  const html = hljs.highlight(source, { language: "jpcl" }).value;
  expect(html).toContain('<span class="hljs-comment">~~ configuration</span>');
  expect(html).toContain('<span class="hljs-meta">[');
  expect(html).toContain('<span class="hljs-attr">items</span>');
  expect(html).toContain('<span class="hljs-number">1_000</span>');
  expect(html).toContain('<span class="hljs-number">-0xff</span>');
  expect(html).toContain('<span class="hljs-literal">False</span>');
  expect(html).toContain('<span class="hljs-literal">null</span>');
  expect(html).toContain('<span class="hljs-string">truely</span>');
  expect(html).toContain('<span class="hljs-char escape_">\\u0021</span>');
  expect(html).toContain('<span class="hljs-attr">empty</span>:');
  expect(html).toContain('<span class="hljs-string">http://example.com:8080</span>');
  expect(html).toContain('<span class="hljs-string">#8496AD </span>');
  expect(html).toContain('<span class="hljs-comment">~* inline comment *~</span>');
});

test("recognizes the jp alias, escapes source, and recovers from unfinished strings", () => {
  const source = 'bad: "unfinished\nhtml: <script>';
  const html = hljs.highlight(source, { language: "jp" }).value;

  expect(html).toContain('<span class="hljs-attr">html</span>');
  expect(html).toContain("&lt;script&gt;");
  expect(html).not.toContain("<script>");
});

test("a multiline block comment can separate entries", () => {
  const source = "empty: ~* first line\nsecond line *~ next: 2";
  const html = hljs.highlight(source, { language: "jpcl" }).value;

  expect(html).toContain('<span class="hljs-comment">~* first line\nsecond line *~</span>');
  expect(html).toContain('<span class="hljs-attr">next</span>: <span class="hljs-number">2</span>');
});
