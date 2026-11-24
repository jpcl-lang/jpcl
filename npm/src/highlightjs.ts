/* npm/src/highlightjs.ts */

interface HighlightMode {
  scope?: string;
  begin?: RegExp | RegExp[];
  beginScope?: Record<number, string>;
  end?: RegExp;
  match?: RegExp;
  contains?: HighlightMode[];
  endsParent?: boolean;
  relevance?: number;
}

interface HighlightLanguage {
  name: string;
  aliases: string[];
  disableAutodetect: boolean;
  contains: HighlightMode[];
}

const VALUE_END = String.raw`(?=[ \t\f\v]*(?:[,}\]\r\n]|~[~*]|$))`;
const DECIMAL_DIGITS = String.raw`\d+(?:_\d+)*`;
const DECIMAL = String.raw`(?:${DECIMAL_DIGITS}(?:\.(?:${DECIMAL_DIGITS})?)?|\.${DECIMAL_DIGITS})(?:[eE][+-]?${DECIMAL_DIGITS})?`;
const RADIX = String.raw`0(?:[xX](?:_?[0-9a-fA-F])+|[oO](?:_?[0-7])+|[bB](?:_?[01])+)`;

/** Registers JPCL 2 syntax when passed to Highlight.js registerLanguage. */
export default function jpcl(): HighlightLanguage {
  const lineComment: HighlightMode = {
    scope: "comment",
    begin: /~~/,
    end: /$/,
  };
  const blockComment: HighlightMode = {
    scope: "comment",
    begin: /~\*/,
    end: /\*~/,
  };
  const comments = [lineComment, blockComment];

  const escape: HighlightMode = {
    scope: "char.escape",
    match: /\\(?:x[0-9a-fA-F]{2}|u[0-9a-fA-F]{4}|U[0-9a-fA-F]{8}|\r\n|[\s\S])/,
    relevance: 0,
  };
  const doubleQuote: HighlightMode = {
    scope: "string",
    begin: /"/,
    end: /"|(?=[\r\n])|$/,
    contains: [escape],
  };
  const singleQuote: HighlightMode = {
    ...doubleQuote,
    begin: /'/,
    end: /'|(?=[\r\n])|$/,
  };
  const strings = [doubleQuote, singleQuote];

  const section: HighlightMode = {
    scope: "meta",
    begin: /(?<=^|\*~)[\t \f\v\uFEFF]*\[/,
    end: /\]|(?=~[~*]|[\r\n])|$/,
    contains: strings,
  };

  const object: HighlightMode = {
    begin: /\{/,
    end: /\}/,
    relevance: 0,
  };
  const array: HighlightMode = {
    begin: /\[/,
    end: /\]/,
    relevance: 0,
  };
  const values: HighlightMode[] = [
    ...comments,
    object,
    array,
    ...strings,
    {
      scope: "number",
      match: new RegExp(`[+-]?(?:${RADIX}|${DECIMAL}|[iI][nN][fF](?:[iI][nN][iI][tT][yY])?|[nN][aA][nN])${VALUE_END}`),
    },
    {
      scope: "literal",
      match: new RegExp(`(?:[tT][rR][uU][eE]|[fF][aA][lL][sS][eE]|[nN](?:[uU][lL][lL]|[oO][nN][eE]|[iI][lL]))${VALUE_END}`),
    },
    {
      scope: "string",
      match: /(?!~[~*])[^\s,{}\[\]"'](?:(?!~[~*])[^,}\]\r\n])*/,
      relevance: 0,
    },
  ];

  // A block comment that crosses a line also ends the current entry.
  const multilineEntryComment: HighlightMode = {
    ...blockComment,
    begin: /~\*(?=(?:(?!\*~)[^\r\n])*[\r\n])/,
    endsParent: true,
  };
  const entry: HighlightMode = {
    begin: [
      /(?<=^|[,{]|\*~)[ \t\f\v\uFEFF]*/,
      /"(?:[^"\\\r\n]|\\(?:\r\n|[\s\S]))*"|'(?:[^'\\\r\n]|\\(?:\r\n|[\s\S]))*'|(?!~[~*])[^\s:,{}\[\]"'](?:(?!~[~*])[^:,{}\[\]"'\r\n])*?(?<![ \t\f\v])/,
      /[ \t\f\v]*(?:~\*(?:(?!\*~)[^\r\n])*\*~[ \t\f\v]*)*/,
      /:/,
    ],
    beginScope: {
      2: "attr",
    },
    end: /(?=[,}\]\r\n])|$/,
    contains: [multilineEntryComment, ...values],
    relevance: 0,
  };

  object.contains = [...comments, entry];
  array.contains = values;

  return {
    name: "JPCL",
    aliases: ["jp"],
    disableAutodetect: true,
    contains: [...comments, section, entry],
  };
}
