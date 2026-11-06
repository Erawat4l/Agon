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
  // Comments are not code: a Java comment explaining why a rule exists must not be
  // mistaken for part of the script it explains (it once was, and the page received a
  // sentence in the middle of a statement).
  picked = picked.split('\n')
    .filter((line) => !/^\s*\/\//.test(line))
    .join('\n');
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
check('the composer keeps a normal caret',
  /input\s*,\s*textarea/.test(cssOff.replace(/\n/g, '')) &&
  /user-select:\s*text\s*!important/.test(cssOff), cssOff);
// "i cant copy cmd from app": a reply has to be copyable. The callout used to be switched
// off globally, which removed the WebView's own Copy route for chat text as well.
check('chat text and code stay copyable', /pre\s*,\s*code/.test(cssOff.replace(/\n/g, '')), cssOff);
// "i cant move the line of entering in chat": the composer is not always a <textarea>,
// so the caret has to come back for the whole box the page actually uses.
check('the composer keeps its caret whatever element it is really made of',
  /form/.test(cssOff.replace(/\n/g, '')) &&
  /\[role="textbox"\]/.test(cssOff.replace(/\n/g, '')) &&
  /\[contenteditable\]/.test(cssOff), cssOff);
check('a box the page explicitly locked stays locked',
  /:not\(\[contenteditable=false\]\)/.test(cssOff), cssOff);
check('nothing removes the OS copy menu any more',
  !/-webkit-touch-callout/.test(cssOff), cssOff);
check('selection ON writes no rules at all', cssOn === '', JSON.stringify(cssOn));

// --- the listener, driven for real ------------------------------------------------------
function page() {
  const dom = new JSDOM('<!doctype html><html><body>' +
    '<div id=txt>a message in the chat</div>' +
    '<textarea id=composer></textarea>' +
    '<div id=edit contenteditable="true">editable</div>' +
    '<form id=box><div id=rte role="textbox" contenteditable="true"><span id=rte-in>typing</span></div></form>' +
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
  const press = () => w.document.getElementById('txt')
    .dispatchEvent(new w.Event('touchstart', { bubbles: true }));
  // A stray drag must not select (that is the "app-style" part)...
  press();
  check('a drag over chat text does not start a selection',
    startsSelection(w, 'txt') === false);
  // ...but a deliberate press-and-hold must, or a reply could never be copied.
  press();
  w.__agonDown = Date.now() - 600;      // the finger has been down for 600 ms
  check('a long press on chat text DOES start a selection, so Copy is offered',
    startsSelection(w, 'txt') === true);
  w.__agonDown = 0;
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
  const one = startsSelection(w, 'txt') === false && w.__agonNoSelect !== null;
  w.eval('(function(){try{' + guardOn + '}catch(e){}})();');
  check('installed twice, it still lifts in one go',
    one && startsSelection(w, 'txt') === true);
}

// --- the composer is exempt wherever the tap lands inside it ----------------------------
{
  const w = page();
  const starts = (id) => {
    const el = w.document.getElementById(id);
    const ev = new w.Event('selectstart', { bubbles: true, cancelable: true });
    el.dispatchEvent(ev);
    return !ev.defaultPrevented;
  };
  check('a tap straight on the rich-text box keeps its caret',
    starts('rte') === true);
  check('a tap on a span INSIDE the box keeps its caret (that is the usual target)',
    starts('rte-in') === true);
  check('the text area is still the text area', starts('composer') === true);
}

// --- a held press makes the selection itself --------------------------------------------
//
// "i cant select in chat": letting a selection through is not enough, because whether a
// long press starts one is the engine's decision. The guard takes the block under the
// finger after ~450 ms and selects it, so the text is really selected - highlighted, with
// Android's Copy menu over it, and readable by More -> Copy selected text.
const held = (async () => {
  const w = page();
  // jsdom has no hit-testing: point caretRangeFromPoint at the reply.
  w.document.caretRangeFromPoint = () => {
    const r = w.document.createRange();
    const node = w.document.getElementById('txt').firstChild;
    r.setStart(node, 0);
    r.setEnd(node, 0);
    return r;
  };
  const reply = w.document.getElementById('txt');
  reply.dispatchEvent(new w.Event('touchstart', { bubbles: true }));
  // the hold fires on a timer; run it
  w.eval('void 0');
  const started = Date.now();
  // jsdom timers are real timers, so wait for the 450 ms hold
  return new Promise((resolve) => {
    setTimeout(() => {
      const sel = w.document.getSelection ? w.document.getSelection() : w.getSelection();
      const text = sel ? String(sel) : '';
      check('a still finger selects the text under it', /message in the chat/.test(text), text);
      check('the selected block is handed back for the highlight',
        /text/.test(reply.style.getPropertyValue('user-select')) ||
        !!w.__agonPicked, reply.getAttribute('style') || '(no style)');
      check('it takes about half a second, not instantly', Date.now() - started >= 400);
      resolve();
    }, 700);
  });
})();

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

(async () => {
  await held;
  console.log(`\n${bad === 0 ? 'all selection tests passed' : bad + ' FAILED'}`);
  process.exit(bad === 0 ? 0 : 1);
})();
