#!/usr/bin/env node
/**
 * Every script the app injects into the page must be valid JavaScript, and every CSS
 * selector inside it must actually parse.
 *
 * This exists because of a bug that no Java check could see: the scripts are Java string
 * literals, so a CSS attribute selector needs THREE backslashes before the quote -
 * `[role=\\"button\\"]` in the file, `[role="button"]` in the page. Get the level wrong and
 * the page gets `[role=\\"]`, which is not a selector at all: closest() throws, the guard
 * silently stops guarding, and the app compiles perfectly.
 *
 * It is also the only check for the scripts' own syntax without a device: a stray `+` or an
 * unbalanced brace inside a literal is a Java compile error at worst and a dead script at
 * best, and a dead script is silent.
 *
 *   node tools/check-page-scripts.js
 */
const fs = require('fs');
const path = require('path');

const SRC_DIR = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'ai', 'arena',
  'webapp');
const FILES = ['TabWebViews.java', 'Pip.java'];
const src = FILES.map((f) => fs.readFileSync(path.join(SRC_DIR, f), 'utf8')).join('\n');

/** Every `private static final String NAME = ...;`, as the text it produces. */
function constants() {
  const out = {};
  const lines = src.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const m = /^\s*private static final String (\w+) =\s*(.*)$/.exec(lines[i]);
    if (!m) continue;
    let block = m[2];
    let j = i;
    while (!/;\s*$/.test(lines[j]) && j + 1 < lines.length) {
      j++;
      block += '\n' + lines[j];
    }
    block = block.split('\n').filter((line) => !/^\s*\/\//.test(line)).join('\n');
    let js = '';
    for (const lit of block.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += lit[1];
    out[m[1]] = js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
  }
  return out;
}

/** The string-building methods, collected the same way (`selectCss`, `selectGuard`). */
function methods() {
  const out = {};
  // `private static` (TabWebViews) or plain `static` (Pip) - both build page scripts.
  const re = /(?:private )?static String (\w+)\(([^)]*)\)\s*\{/g;
  let m;
  while ((m = re.exec(src))) {
    const start = m.index + m[0].length;
    let depth = 1;
    let i = start;
    while (i < src.length && depth > 0) {
      if (src[i] === '{') depth++;
      else if (src[i] === '}') depth--;
      i++;
    }
    const body = src.slice(start, i - 1).split('\n')
      .filter((line) => !/^\s*\/\//.test(line)).join('\n');
    let js = '';
    for (const lit of body.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += lit[1];
    out[m[1]] = js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
  }
  return out;
}

let JSDOM = null;
try {
  ({ JSDOM } = require('jsdom'));
} catch (e) {
  JSDOM = null;
}

const dom = JSDOM
  ? new JSDOM('<!doctype html><html><body><button>x</button></body></html>').window.document
  : null;

let bad = 0;
function fail(what, detail) {
  bad++;
  console.log(`FAIL  ${what}\n      ${detail}`);
}

// Only the names that are page JavaScript. CSS, a quoted literal and a script template
// with Java interpolation in it (pointJs) are not scripts, and parsing them as one is noise.
const JS_NAMES = [
  'JS_COPY_API', 'JS_TAP_CHECK', 'JS_TAP_WATCH', 'JS_POPUP_FIT', 'JS_NEW_CHAT_LISTENER',
  'JS_UNSTICK', 'JS_ENTER_SEND', 'JS_TAB_TITLE', 'JS_NEW_CHAT', 'JS_SIDEBAR',
  'JS_SHOW_PANELS', 'JS_HEADER', 'JS_ESCAPE',
  // Pip.java: the floating-window scripts. `videoPlayingJs` answers whether anything is
  // playing, `pipCssJs` is two branches (shape / put back) and is checked as two, and
  // `videoActionJs` is one script with the button's work patched into it.
  'videoPlayingJs', 'pipCssJs:0', 'pipCssJs:1', 'videoActionJs',
];
const scripts = {};
const all = { ...constants(), ...methods() };
for (const name of Object.keys(all)) {
  if (JS_NAMES.includes(name)) scripts[name] = all[name];
}
// A method with two scripts in it (Pip.pipCssJs) is checked one branch at a time: glued
// together they are not the thing that runs, and a break in either branch must be reported
// against the branch it is in.
const pip = fs.readFileSync(path.join(SRC_DIR, 'Pip.java'), 'utf8');
function branch(fnName, which) {
  const at = pip.indexOf('static String ' + fnName + '(');
  if (at < 0) return '';
  let i = pip.indexOf('{', at), depth = 0, body = '';
  for (let j = i; j < pip.length; j++) {
    if (pip[j] === '{') depth++;
    else if (pip[j] === '}') {
      depth--;
      if (depth === 0) { body = pip.slice(i, j + 1); break; }
    }
  }
  const starts = [];
  const re = /(?:\breturn|\bbody\s*=)\s*"/g;
  let m;
  while ((m = re.exec(body))) starts.push(m.index + m[0].lastIndexOf('"'));
  if (starts[which] === undefined) return '';
  const lit = /"((?:[^"\\]|\\.)*)"/g;
  lit.lastIndex = starts[which];
  let js = '';
  let first = lit.exec(body);
  if (!first) return '';
  js += first[1];
  let end = first.index + first[0].length;
  for (;;) {
    const gap = body.slice(end).match(/^\s*\+\s*/);
    if (!gap) break;
    lit.lastIndex = end + gap[0].length;
    const next = lit.exec(body);
    if (!next || next.index !== end + gap[0].length) break;
    js += next[1];
    end = next.index + next[0].length;
  }
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}
for (const name of Object.keys(scripts)) {
  builder: for (const [fnName, which] of [['pipCssJs', 0], ['pipCssJs', 1]]) {
    if (name === fnName + ':' + which) {
      scripts[name] = branch(fnName, which);
      break builder;
    }
  }
}
const html = {};
for (const [name, value] of [...Object.entries(scripts), ...Object.entries(html)]) {
  if (!value) {
    // A method that returns "" for one branch is fine; a constant that produces nothing is
    // not, because it is installed on every page and would do nothing at all.
    if (name in scripts) fail(`${name} produces no script`, 'the constant is empty');
    continue;
  }
  if (name === 'JS_POPUP_FIT' || name === 'JS_COPY_API') {
    // fragments, evaluated inside an IIFE by their callers - still parsed here
  }
  try {
    // eslint-disable-next-line no-new-func
    new Function(value);
  } catch (e) {
    fail(`${name} is not valid JavaScript`, e.message);
    continue;
  }
  if (!dom) continue;
  // Every selector-shaped literal must parse. This is what a wrongly escaped quote breaks.
  const selectors = [
    ...value.matchAll(/closest\((?:'([^']+)')/g),
    ...value.matchAll(/querySelector(?:All)?\('([^']+)'\)/g),
  ];
  for (const m of selectors) {
    for (const part of m[1].split(',')) {
      try {
        dom.querySelector(part.trim());
      } catch (e) {
        fail(`${name} builds a selector the page cannot parse`, part.trim());
      }
    }
  }
  // No over-escaped quote may survive into a selector: `[role=\"button\"]`.
  if (/\\"/.test(value) && /role=\\*"/.test(value)) {
    fail(`${name} has an over-escaped quote in a selector`, /\[role=[^\]]*/.exec(value)[0]);
  }
}

console.log(bad === 0
  ? `page scripts OK (${Object.keys(scripts).length + Object.keys(html).length} checked)`
  : `${bad} page-script problem(s)`);
process.exit(bad === 0 ? 0 : 1);
