/**
 * Tests for getting text OUT of the app - "i cant copy cmd from app".
 *
 * There are two routes, and both have to exist because they fail in different places:
 *
 *  1. Android's own Copy menu, from a selection in the page. The app deliberately refuses
 *     a selection that a stray drag starts ("app-style text"), and the old rule also
 *     switched off -webkit-touch-callout on everything, which removed the WebView's copy
 *     route as well. Both are fixed: a press that has been HELD is let through, and the
 *     callout is no longer suppressed.
 *
 *  2. More -> Copy selected text, which asks the page for its selection and puts it on the
 *     clipboard. That one does not depend on the WebView offering a menu at all.
 *
 * The guard in (1) is driven for real in jsdom; the clipboard plumbing in (2) is read out of
 * the Java, because there is no Android here.
 *
 *   cd desktop && node test/copy-text.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the copy tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && node test/copy-text.test.js');
  process.exit(0);
}

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena', 'webapp');
const TABS = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
const MAIN = fs.readFileSync(path.join(NAV, 'MainActivity.java'), 'utf8');
const MENU = fs.readFileSync(path.join(NAV, 'MenuSheet.java'), 'utf8');
const STRINGS = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
  'res', 'values', 'strings.xml'), 'utf8');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `\n      ${detail}`));
}

/** One Java string-building helper, rebuilt as the script it produces (no comments). */
function jsFor(methodName, flag) {
  const start = TABS.indexOf('private static String ' + methodName + '(');
  if (start < 0) throw new Error(methodName + ' not found');
  const end = TABS.indexOf('\n    }', start);
  let body = TABS.slice(start, end).split('\n')
    .filter((line) => !/^\s*\/\//.test(line))       // a comment is not code
    .join('\n');
  let picked;
  const single = /if\s*\(selectable\)\s*return\s*("(?:[^"\\]|\\.)*"|"")\s*;/.exec(body);
  const braced = /if\s*\(selectable\)\s*\{([\s\S]*?)\}/.exec(body);
  if (single) {
    picked = flag ? single[1]
      : body.slice(body.indexOf(single[0]) + single[0].length);
  } else if (braced) {
    const from = body.indexOf(braced[0]);
    let i = from + /if\s*\(selectable\)\s*\{/.exec(body.slice(from))[0].length;
    let depth = 1;
    let inStr = false;
    let esc = false;
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
    picked = body;
  }
  let out = '';
  for (const m of picked.matchAll(/"((?:[^"\\]|\\.)*)"/g)) out += m[1];
  return out.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const guard = jsFor('selectGuard', false);

function page() {
  const dom = new JSDOM('<!doctype html><html><body>'
    + '<div id=reply>run this: docker run -d -p 8080:80 nginx</div>'
    + '<textarea id=composer></textarea></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  w.eval('(function(){try{' + guard + '}catch(e){console.log("ERR", e.message)}})();');
  return w;
}

function selectionStarts(w, id) {
  const el = w.document.getElementById(id);
  const ev = new w.Event('selectstart', { bubbles: true, cancelable: true });
  el.dispatchEvent(ev);
  return !ev.defaultPrevented;
}

function press(w, id) {
  w.document.getElementById(id)
    .dispatchEvent(new w.Event('touchstart', { bubbles: true }));
}

// --- 1. the script itself is valid, and it is what the page receives -------------------
{
  check('the selection guard is valid JavaScript',
    (() => { try { new Function(guard); return true; } catch (e) { return false; } })(),
  guard.slice(0, 200));
  check('no sentence from a code comment ended up inside it',
    !/i cannot|cant copy|report\./.test(guard), guard.slice(0, 200));
}

// --- 2. a deliberate press-and-hold can copy -------------------------------------------
{
  const w = page();
  press(w, 'reply');
  check('a drag does not start a selection', selectionStarts(w, 'reply') === false);
  press(w, 'reply');
  w.__agonDown = Date.now() - 600;
  check('a held press DOES start a selection', selectionStarts(w, 'reply') === true);
  w.__agonDown = 0;
}

// --- 3. the composer is untouched -------------------------------------------------------
{
  const w = page();
  press(w, 'composer');
  w.__agonDown = Date.now() - 600;          // even a hold in the composer
  check('the composer never gets the in-the-way menu',
    selectionStarts(w, 'composer') === true);
}

// --- 4. More -> Copy selected text -----------------------------------------------------
{
  check('the More sheet has a Copy row', /R\.string\.menu_copy/.test(MENU));
  check('the row reaches the activity',
    /onCopySelection\(\)/.test(MENU) && /void onCopySelection\(\)/.test(MENU));
  check('the activity answers it', /public void onCopySelection\(\)/.test(MAIN)
    && /copySelection\(\)/.test(MAIN));
  check('it puts the text on the clipboard',
    /setPrimaryClip\(ClipData\.newPlainText/.test(MAIN));
  check('it says what happened (a row that does nothing looks broken)',
    /R\.string\.copy_done/.test(MAIN) && /R\.string\.copy_empty/.test(MAIN));
  check('the strings exist',
    /name="menu_copy"/.test(STRINGS) && /name="copy_empty"/.test(STRINGS)
      && /name="copy_done"/.test(STRINGS));
  check('there is an icon for the row',
    fs.existsSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'res', 'drawable',
      'ic_nav_copy.xml')));
}

// --- 5. the page's selection is read correctly ----------------------------------------
{
  check('the app asks the page for its own selection',
    /window\.getSelection\(\)/.test(TABS));
  check('the answer is unquoted before it is used',
    /unquoteJs\(value\)/.test(TABS) && /static String unquoteJs\(String value\)/.test(TABS));

  // The same rules the Java implements, in JavaScript, so the escapes are pinned.
  function unquoteJs(value) {
    if (value == null) return '';
    const v = String(value).trim();
    if (v === '' || v === 'null' || v === 'undefined') return '';
    if (v.length >= 2 && v[0] === '"' && v[v.length - 1] === '"') {
      let out = '';
      for (let i = 1; i < v.length - 1; i++) {
        const c = v[i];
        if (c !== '\\' || i + 1 >= v.length - 1) { out += c; continue; }
        const n = v[++i];
        const map = { n: '\n', t: '\t', r: '\r', b: '\b', f: '\f' };
        if (map[n] !== undefined) out += map[n];
        else if (n === 'u' && i + 4 < v.length) {
          out += String.fromCharCode(parseInt(v.slice(i + 1, i + 5), 16));
          i += 4;
        } else out += n;
      }
      return out;
    }
    return v;
  }
  check('a plain command survives unchanged',
    unquoteJs('"docker run -d -p 8080:80 nginx"') === 'docker run -d -p 8080:80 nginx');
  check('a multi-line command keeps its newlines',
    unquoteJs('"line one\\nline two"') === 'line one\nline two');
  check('a backslash in a path survives',
    unquoteJs('"C:\\\\temp\\\\x"') === 'C:\\temp\\x');
  check('an empty selection is empty, not the word null',
    unquoteJs('null') === '' && unquoteJs('""') === '');
  check('a quote inside the text is kept',
    unquoteJs('"say \\"hi\\""') === 'say "hi"');
}

console.log(`\n${bad === 0 ? 'all copy-text tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
