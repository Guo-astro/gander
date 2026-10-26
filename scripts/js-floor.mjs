#!/usr/bin/env node
// The oldest Chromium that can run a vendored script, measured rather than taken
// on trust. docx-preview and marked publish no minimum, so the floors for .docx
// and .md in WebViewFloor.kt come from this, and VendoredLibsTest pins the files
// they were measured on. When that test fails after a refetch, run this on the
// new file before touching the fingerprint. docs/VENDORED.md has the rest.
//
//   node scripts/js-floor.mjs app/src/main/assets/viewer/lib/marked.umd.js
//
// Two lists. Syntax is exact: every construct newer than ES2015 is found by
// walking acorn's tree, and an engine that lacks one cannot parse the file at
// all. Runtime names are only candidates: a name in the file is not a call every
// document makes, so read where each newer one is used before letting it set the
// floor. marked's Array.prototype.at is one that does, on a single line of prose.
//
// Versions are the Chrome release that shipped each feature, which WebView
// follows. acorn is fetched into a temporary directory on first use, so the repo
// carries no npm dependencies for the sake of this.

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";

const DEPS = join(tmpdir(), "gander-js-floor");
if (!existsSync(join(DEPS, "node_modules", "acorn-walk"))) {
  execFileSync("npm", ["install", "--silent", "--no-save", "--no-package-lock",
    "--prefix", DEPS, "acorn@8", "acorn-walk@8"], { stdio: "inherit" });
}
const require = createRequire(join(DEPS, "node_modules", "_"));
const acorn = require("acorn");
const walk = require("acorn-walk");

const RUNTIME = [
  [/\.at\(/, 92, "Array or String .at()"],
  [/\.findLast(Index)?\(/, 97, ".findLast()"],
  [/\.(toSorted|toReversed|toSpliced)\(/, 110, "change-by-copy arrays"],
  [/Object\.hasOwn\(/, 93, "Object.hasOwn"],
  [/(Object|Map)\.groupBy/, 117, "groupBy"],
  [/\.replaceAll\(/, 85, ".replaceAll()"],
  [/\.matchAll\(/, 73, ".matchAll()"],
  [/Object\.fromEntries/, 73, "Object.fromEntries"],
  [/\bstructuredClone\(/, 98, "structuredClone"],
  [/Promise\.withResolvers/, 119, "Promise.withResolvers"],
  [/Promise\.any\(/, 85, "Promise.any"],
  [/Promise\.allSettled/, 76, "Promise.allSettled"],
  [/\b(WeakRef|FinalizationRegistry)\b/, 84, "WeakRef"],
  [/\bAggregateError\b/, 85, "AggregateError"],
  [/Intl\.Segmenter/, 87, "Intl.Segmenter"],
  [/crypto\.randomUUID/, 92, "crypto.randomUUID"],
  [/\.replaceChildren\(/, 86, ".replaceChildren()"],
  [/\b(De)?CompressionStream\b/, 80, "CompressionStream"],
  [/\bglobalThis\b/, 71, "globalThis"],
  [/\bqueueMicrotask\(/, 71, "queueMicrotask"],
  [/\.flat(Map)?\(/, 69, ".flat() and .flatMap()"],
  [/\.trim(Start|End)\(/, 66, ".trimStart() and .trimEnd()"],
];

// The text after each "(" in a regex that opens a group: an escaped paren or one
// inside a character class is only a character. Classes nest under the v flag alone.
const groupHeads = (pattern, nests) => {
  const heads = [];
  let depth = 0;
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i];
    if (c === "\\") i++;
    else if (c === "[" && (depth === 0 || nests)) depth++;
    else if (c === "]" && depth > 0) depth--;
    else if (c === "(" && depth === 0) heads.push(pattern.slice(i + 1));
  }
  return heads;
};

for (const file of process.argv.slice(2)) {
  const src = readFileSync(file, "utf8");
  const found = new Map();
  const note = (what, chrome) => found.set(what, chrome);

  const onToken = (t) => {
    const label = t.type.label;
    if (label === "?.") note("optional chaining ?.", 80);
    if (label === "??") note("nullish coalescing ??", 80);
    if (label === "_=" && /^(\?\?|\|\||&&)=$/.test(t.value)) note(`logical assignment ${t.value}`, 85);
    if (label === "privateId") note("private name #x", 74);
    if (label === "num" && src.slice(t.start, t.end).includes("_")) note("numeric separator", 75);
    if (label === "regexp") {
      const { flags, pattern } = t.value;
      if (flags.includes("d")) note("regex d flag", 90);
      if (flags.includes("v")) note("regex v flag", 112);
      if (flags.includes("s")) note("regex s flag", 62);
      if (/\(\?<[=!]/.test(pattern)) note("regex lookbehind", 62);
      if (/\(\?<[A-Za-z_$]/.test(pattern)) note("regex named group", 64);
      if (flags.includes("u") && /\\[pP]\{/.test(pattern)) note("regex \\p{} escape", 64);
      const heads = groupHeads(pattern, flags.includes("v"));
      if (heads.some((h) => /^\?(?:[ims]+(?:-[ims]*)?|-[ims]+):/.test(h))) note("regex modifiers (?i:)", 125);
      const names = heads.map((h) => /^\?<([^=!>][^>]*)>/.exec(h)?.[1]).filter(Boolean);
      if (new Set(names).size < names.length) note("regex duplicate named groups", 125);
    }
  };
  const sourceType = file.endsWith(".mjs") ? "module" : "script";
  const ast = acorn.parse(src, { ecmaVersion: "latest", sourceType, onToken, allowHashBang: true });
  walk.full(ast, (n) => {
    if (n.type === "CatchClause" && !n.param) note("optional catch binding", 66);
    if (n.type === "PropertyDefinition") note("class field", n.key.type === "PrivateIdentifier" ? 74 : 72);
    if (n.type === "MethodDefinition" && n.key.type === "PrivateIdentifier") note("private method", 84);
    if (n.type === "StaticBlock") note("class static block", 94);
    if (n.type === "BinaryExpression" && n.left.type === "PrivateIdentifier") note("#x in obj", 91);
    if (n.type === "Literal" && typeof n.bigint === "string") note("BigInt literal", 67);
    if (n.type === "ImportExpression") note("dynamic import()", 63);
    if (n.type === "ForOfStatement" && n.await) note("for await", 63);
    if (n.type === "ObjectExpression" && n.properties.some((p) => p.type === "SpreadElement")) note("object spread", 60);
    if (/Function/.test(n.type) && n.async) note(n.generator ? "async generator" : "async function", n.generator ? 63 : 55);
  });

  console.log(file);
  let floor = 0;
  for (const [what, chrome] of [...found].sort((a, b) => b[1] - a[1])) {
    floor = Math.max(floor, chrome);
    console.log(`  syntax   ${String(chrome).padStart(3)}  ${what}`);
  }
  for (const [re, chrome, what] of RUNTIME.sort((a, b) => b[1] - a[1])) {
    const at = src.search(re);
    if (at < 0) continue;
    const around = src.slice(Math.max(0, at - 40), at + 40).replace(/\s+/g, " ");
    console.log(`  runtime  ${String(chrome).padStart(3)}  ${what}   ...${around}...`);
  }
  console.log(`  parses from Chromium ${floor || "51 or earlier"}; the floor is that or the newest runtime name a document actually reaches\n`);
}
