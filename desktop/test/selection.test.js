/**
 * Tests for "turn off selection": text in the app must not be selectable.
 *
 * Reported on the device as "i can select text on app still". The CSS rule existed, but
 * it was only ever written into the frame stylesheet, and that stylesheet is written on
 * one branch of applyChromeInsets() only - the branch that reserves space for the app's
 * bars. The app reserves none, so applyChromeInsets() took its early-return branch, which
 * clears the injected <style> and never rewrote the rule. The setting was therefore
 * honoured nowhere.
 *
 * The script is EXTRACTED FROM TabWebViews.java, so the Java cannot drift away from this
 * test: both helpers are read out of the source, the CSS is checked for the rules that
 * actually stop a selection, and the listener is driven in jsdom.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/selection.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the selection tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && ' +
    'node test/selection.test.js');
  process.exit(0);
}

const JAVA = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
  'ai', 'arena', 'webapp', 'TabWebViews.java');
const SRC = fs.readFileSync(JAVA, 'utf8');

/**
 * Reads one Java helper out of the source and returns the JavaScript it builds.
 *
 * The helpers are written as `return "..." + (flag ? "..." : "") + ...;`, so the literal
 * pieces are collected in order and each ternary is replaced by the branch that applies
 * to the flag value being asked for.
 */
function jsFor(methodName, flag) {
  const start = SRC.indexOf('private static String ' + methodName + '(');
  if (start < 0) throw new Error(methodName + ' not found');
  const end = SRC.indexOf('\n    }', start);
  const body = SRC.slice(start, end);
  // The helpers come in two shapes: `if (selectable) return "..."; return "...";` and
  // `if (selectable) { return "..."; } return "...";`. Pick the statement the flag
  // selects, then collect the literals inside it.
  let picked;
  const single = /if\s*\(selectable\)\s*return\s*("(?:[^"\\]|\\.)*"|"")\s*;/.exec(body);
  const braced = /if\s*\(selectable\)\s*\{([\s\S]*?)\}/.exec(body);
  if (single) {
    const first = single[1];
    picked = flag ? first
      : body.slice(body.indexOf(single[0]) + single[0].length);
  } else if (braced) {
    // The `if` block ends at the first `}` IN CODE, not at the first `}` in a string
    // (the guarded script is full of braces). Track quotes and escapes.
    const from = body.indexOf(braced[0]);
    let i = from + /if\s*\(selectable\)\s*\{/.exec(body.slice(from))[0].length;
    let depth = 1, inStr = false, esc = false;
    for (; i < body.length && depth > 0; i++) {
      const c = body[i];
      if (inStr) {
        if (esc) esc = false;
        else if (c === '\\') esc = true;
        else if (c === '"') inStr = false;
        continue;
      }
      if (c === '"') inStr = true;
      else if (c === '{') depth++;
      else if (c === '}') depth--;
    }
    picked = flag ? body.slice(from, i) : body.slice(i);
  } else {
    picked = body;                      // no branch: the same script either way
  }
  let out = '';
  for (const m of picked.matchAll(/"((?:[^"\\]|\\.)*)"/g)) out += m[1];
  return out.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const cssOff = jsFor('selectCss', false);
const cssOn = jsFor('selectCss', true);
const guardOff = jsFor('selectGuard', false);
const guardOn = jsFor('selectGuard', true);

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `  ${detail}`));
}

// --- the CSS the page receives ----------------------------------------------------------
check('selection off puts user-select:none on everything',
  /user-select:\s*none\s*!important/.test(cssOff) &&
  /-webkit-user-select:\s*none\s*!important/.test(cssOff), cssOff);
check('the long-press callout is switched off too',
  /-webkit-touch-callout:\s*none\s*!important/.test(cssOff), cssOff);
check('the composer keeps a normal caret',
  /input\s*,\s*textarea/.test(cssOff.replace(/\n/g, '')) &&
  /user-select:\s*text\s*!important/.test(cssOff), cssOff);
check('selection ON writes no rules at all', cssOn === '', JSON.stringify(cssOn));

// --- the listener, driven for real ------------------------------------------------------
function page() {
  const dom = new JSDOM('<!doctype html><html><body>' +
    '<div id=txt>a message in the chat</div>' +
    '<textarea id=composer></textarea>' +
    '<div id=edit contenteditable="true">editable</div>' +
    '</body></html>', { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  // jsdom does not implement selectstart; any cancellable event does the same job here.
  w.eval('(function(){try{' + guardOff + '}catch(e){console.log("ERR",e.message)}})();');
  return w;
}

function startsSelection(w, id) {
  const el = w.document.getElementById(id);
  const ev = new w.Event('selectstart', { bubbles: true, cancelable: true });
  el.dispatchEvent(ev);
  return !ev.defaultPrevented;      // true = a selection would begin
}

{
  const w = page();
  check('a drag or long press over chat text cannot start a selection',
    startsSelection(w, 'txt') === false);
  check('the composer still accepts a caret and a selection',
    startsSelection(w, 'composer') === true);
  check('an editable area keeps normal behaviour',
    startsSelection(w, 'edit') === true);

  // turning the setting back on must remove the listener, not just stop adding it
  w.eval('(function(){try{' + guardOn + '}catch(e){}})();');
  check('switching the setting off removes the listener again',
    startsSelection(w, 'txt') === true && w.__agonNoSelect === null);

  // and re-installing it must not stack listeners (each pass would add one otherwise)
  w.eval('(function(){try{' + guardOff + guardOff + '}catch(e){}})();');
  const one = startsSelection(w, 'txt') === false;
  w.eval('(function(){try{' + guardOn + '}catch(e){}})();');
  check('installed twice, it still lifts in one go',
    one && startsSelection(w, 'txt') === true);
}

// --- the fix has to be in both branches of applyChromeInsets() --------------------------
{
  // The early-return branch clears the frame stylesheet. If the selection rule is written
  // only into that stylesheet, the setting does nothing - which is the bug this test is
  // named after. So the rule has to be written independently, before the branch.
  const branch = SRC.slice(SRC.indexOf('if (cssTop <= 0 && cssBottom <= 0)'),
    SRC.indexOf('String css = buildFrameCss'));
  const inline = SRC.slice(SRC.indexOf('String css = buildFrameCss'),
    SRC.indexOf('web.evaluateJavascript(js, null)', SRC.indexOf('String css = buildFrameCss')));
  check('the no-insets branch writes the selection rule itself',
    /arena-select-policy/.test(branch) && /selectCss\(selectable\)/.test(branch));
  check('the insets branch installs the guard too',
    /selectGuard\(selectable\)/.test(inline));
}

console.log(`\n${bad === 0 ? 'all selection tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
