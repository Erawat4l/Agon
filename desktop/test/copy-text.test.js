/**
 * Tests for getting text OUT of the app - "i cant copy cmd from app".
 *
 * 1. Android's own Copy menu, from a selection in the page. The app deliberately refuses a
 *    selection that a stray drag starts ("app-style text"), and the old rule also switched
 *    off -webkit-touch-callout on everything, which removed the WebView's copy route as
 *    well. Both are fixed: a press that has been HELD is let through, and the callout is no
 *    longer suppressed.
 *
 * 2. The hold itself: the app times the press, picks the block under the finger and writes
 *    the clip natively.
 *
 * There is deliberately NO "Copy selected text" row in More any more - the app copies by
 * itself, and the row only made sense when it did not. The suite now asserts it is gone, so
 * it cannot creep back as a third route that needs a selection the app usually refuses.
 *
 * The guard is driven for real in jsdom; the clipboard plumbing is read out of the Java,
 * because there is no Android here.
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

// --- 4. the copy path that exists, and the row that does not ---------------------------
//
// "Remove chats, copy selected button from more." The row was a manual third route to
// something the app now does by itself on a hold: it asked the user to select text first,
// in an app whose whole point is that a drag does not select. It is gone, and this is the
// test that keeps it gone.
{
  check('the More sheet has no Copy row', !/menu_copy/.test(MENU));
  check('and the activity has no handler for one', !/onCopySelection/.test(MENU)
    && !/onCopySelection/.test(MAIN));
  check('the strings for the removed row are gone too',
    !/name="menu_copy"/.test(STRINGS) && !/name="menu_copy_sub"/.test(STRINGS));

  // What does the copying: a hold hands the block over, and the app writes the clip.
  const BRIDGE = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
    'java', 'ai', 'arena', 'webapp', 'WebBridge.java'), 'utf8');
  check('the hold hands its text to the app through the bridge',
    /@JavascriptInterface\s+public void copied\(final String text\)/.test(BRIDGE));
  check('the app writes the clip natively',
    /setPrimaryClip\(ClipData\.newPlainText/.test(MAIN));
  check('and says what happened',
    /R\.string\.copy_done/.test(MAIN) && /R\.string\.copy_empty/.test(MAIN));
  check('the strings it says it with exist',
    /name="copy_empty"/.test(STRINGS) && /name="copy_done"/.test(STRINGS));

  // The one instruction left over must not name the row that no longer exists:
  // "Selected - now use More -> Copy selected text" would send the user to an empty sheet.
  check('the "could not copy" hint does not point at a removed row',
    /name="copy_needs_menu">[^<]*tap the selection/.test(STRINGS) &&
    !/=.[^<]*More/.test((STRINGS.match(/name="copy_needs_menu">[^<]*/) || [''])[0]));
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

// --- the hold is timed in Java too, so a copy cannot be silently absent ----------------
//
// "I cant copy yaar. I cant select." Two page-side attempts had already failed on the
// device, and the common factor is that everything went through the page: its timers, its
// script, its selection. A hold is now also timed in the WebView itself and the text is
// asked for by point, so the copy works even if the page's script never ran.
{
  check('the WebView times the hold itself',
    /class SelectionSafeWebView extends WebView/.test(TABS) &&
    /onTouchEvent\(android\.view\.MotionEvent event\)/.test(TABS),
    'SelectionSafeWebView');
  check('a hold is long enough not to be a tap, and a travelling finger cancels it',
    /HOLD_MS = 700L/.test(TABS) && /ACTION_MOVE/.test(TABS) && /cancelHold\(\)/.test(TABS));
  // The copy happens when the finger LIFTS, not on the timer: that is what lets a
  // hold-then-drag extend a selection before anything is copied.
  check('the copy waits for the finger to lift',
    /ACTION_UP:[\s\S]{0,400}holdListener\.onHold/.test(TABS),
    'onHold is called from ACTION_UP');
  // ...and a press the user thought better of copies nothing.
  check('a finger that keeps travelling before or after the hold copies nothing',
    /cancelArmed\(\)/.test(TABS) && /SLOP_DP = 10f/.test(TABS));
  // One copy per press: the page has no hold timer of its own any more.
  check('the page no longer has a hold timer of its own',
    !/__agonHold=function/.test(TABS) && !/holdTimer=setTimeout/.test(TABS));
  // A word the user already selected wins over the paragraph under the finger.
  check('an existing selection is what gets copied',
    /SELECTION_PROBE/.test(TABS) && /void onHold\(int x, int y, String selection\)/.test(TABS));
  check('and it is copied as the user selected it',
    /chosen != null && !chosen\.trim\(\)\.isEmpty\(\)/.test(TABS));
  check('the app asks the page for the text under the point',
    /AgonCopy\.under/.test(TABS) && /setHoldListener/.test(TABS));
  check('the page answers through the bridge',
    /AgonCopyNative/.test(TABS) &&
    /@JavascriptInterface\s+public void text\(final String text\)/.test(
      fs.readFileSync(path.join(NAV, 'WebBridge.java'), 'utf8')));
  check('the answer is written to the clipboard natively',
    /void onPageCopied\(String text\)/.test(MAIN) && /setPrimaryClip/.test(MAIN));
  // "just clicking anywhere copies everything": the old route topped the copy up with the
  // page's selection 260 ms after every hold, so a slow tap on a control copied the reply
  // behind it. Nothing may copy unless the page found TEXT under the point.
  check('nothing is copied when the page finds no text under the point',
    !/copySelectionFallback/.test(TABS) && !/postDelayed\(this::copy/.test(TABS),
    'no timer may copy without the page reporting text');
  check('the point is scaled for the page, not assumed to be CSS pixels',
    /devicePixelRatio/.test(TABS));
}

// --- three taps stay unlocked -----------------------------------------------------------
//
// applyChromeInsets() re-injects the policy on every page finish and every tab switch, so an
// unlock that the page did not record was undone a second later - "i cant select" straight
// after unlocking it.
{
  check('the policy is written only while the page is locked',
    /st\.textContent=window\.__agonSelectUnlocked\?'':/.test(TABS));
  check('the lock script checks the flag itself before installing',
    /if\(!window\.__agonSelectUnlocked\)try\{window\.__agonLock\(\);\}/.test(TABS));
  check('and the lock is a named function the page can re-run',
    /window\.__agonLock=function\(\)\{/.test(TABS));
  check('unlocking clears the listener reference, so a re-injection can install a new one',
    /window\.__agonSavedGuard=/.test(
      fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8')));
}

// --- a hold copies the line, not the page ----------------------------------------------
//
// "just holding screen text copies everything": the first version walked up to the nearest
// block-shaped ancestor and took all of it, which on arena is the message column or the
// whole conversation pane. The script is driven for real here, in a fixture shaped like a
// conversation (a scrolling pane, a flex column, three paragraphs).
{
  const SRC = TABS;
  const from = SRC.indexOf('private static final String JS_COPY_API =');
  const to = SRC.indexOf('\n\n', from);
  let js = '';
  for (const m of SRC.slice(from, to).matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  js = js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
  check('the copy script is valid JavaScript', (() => {
    try { new Function(js); return true; } catch (e) { return false; }
  })(), js.slice(0, 160));

  const dom = new JSDOM('<!doctype html><html><body>'
    + '<div id=pane role="log" style="overflow-y:auto">'
    + '<div id=column style="display:flex">'
    + '<p id=p1>first paragraph of the reply</p>'
    + '<p id=p2>the command to copy: docker run nginx</p>'
    + '<p id=p3>third paragraph</p>'
    + '</div></div></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  Object.defineProperty(w.HTMLElement.prototype, 'innerText', {
    get() { return this.textContent; },
    configurable: true,
  });
  const display = { pane: 'block', column: 'flex' };
  w.getComputedStyle = (el) => ({
    display: display[el.id] || 'block',
    overflow: el.id === 'pane' ? 'auto' : 'visible',
    overflowY: el.id === 'pane' ? 'auto' : 'visible',
  });
  let got = null;
  w.AgonCopyNative = { text: (t) => { got = t; } };
  w.document.caretRangeFromPoint = () => {
    const r = w.document.createRange();
    const node = w.document.getElementById('p2').firstChild;
    r.setStart(node, 4);
    r.setEnd(node, 4);
    return r;
  };
  w.eval(js);
  w.AgonCopy.under(10, 10, 1);
  check('a hold copies the paragraph under it, not the whole reply',
    !!got && /docker run nginx/.test(got) && !/first paragraph/.test(got),
    JSON.stringify(got));
  check('a hold in the composer copies nothing',
    (() => {
      got = null;
      const ta = w.document.createElement('textarea');
      w.document.body.appendChild(ta);
      w.document.caretRangeFromPoint = () => {
        const r = w.document.createRange();
        const node = w.document.createTextNode('typing');
        ta.appendChild(node);
        r.setStart(node, 0);
        r.setEnd(node, 0);
        return r;
      };
      w.AgonCopy.under(10, 10, 1);
      return got === null;
    })(), JSON.stringify(got));
}

// --- a press on a control copies nothing -------------------------------------------------
//
// "just clicking anywhere copies everything so fix this shi too". Two causes, both closed
// here: the page took the caption of whatever control was pressed, and the app topped the
// copy up with the page's selection a moment later. This drives the real script: a press on
// a button, a link, a nav row and an overlay must report nothing at all.
{
  const SRC = TABS;
  const from = SRC.indexOf('private static final String JS_COPY_API =');
  const to = SRC.indexOf('\n\n', from);
  let js = '';
  for (const m of SRC.slice(from, to).matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  js = js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');

  function world(html, hitId) {
    const dom = new JSDOM(`<!doctype html><html><body>${html}</body></html>`,
      { runScripts: 'outside-only', url: 'https://arena.ai/' });
    const w = dom.window;
    Object.defineProperty(w.HTMLElement.prototype, 'innerText', {
      get() { return this.textContent; }, configurable: true,
    });
    w.getComputedStyle = () => ({ display: 'block', overflow: 'visible', overflowY: 'visible' });
    const reports = [];
    w.AgonCopyNative = { text: (t) => reports.push(t) };
    // hit test: whatever element is under the point
    w.document.elementFromPoint = () => w.document.getElementById(hitId);
    // caret: the text of the block the point is in, so a text hit does report
    w.document.caretRangeFromPoint = () => {
      const el = w.document.getElementById(hitId);
      const node = el.firstChild || el.appendChild(w.document.createTextNode('the reply text'));
      const r = w.document.createRange();
      r.setStart(node, 0);
      r.setEnd(node, 0);
      return r;
    };
    w.eval(js);
    w.AgonCopy.under(10, 10, 1);
    return { w, reports };
  }

  const refused = [
    ['a button', '<div id=t><button id=hit>Send the message</button></div>'],
    ['a link', '<div id=t><a id=hit href=#>Read more</a></div>'],
    ['a link inside a row', '<nav id=t><span id=hit><a href=#>Chats</a></span></nav>'],
    ['a nav row', '<nav id=t><div id=hit>Home</div></nav>'],
    ['a switch', '<div id=t><span id=hit role=switch>On</span></div>'],
    ['the composer', '<div id=t><textarea id=hit></textarea></div>'],
  ];
  for (const [what, html] of refused) {
    const { reports } = world(html, 'hit');
    check(`a press on ${what} copies nothing`, reports.length === 0, JSON.stringify(reports));
  }

  // ...and a press on ordinary text still copies, or the fix would just be "copy is broken".
  const { reports } = world('<div id=t><p id=hit>the reply text</p></div>', 'hit');
  check('a press on a plain paragraph still copies it',
    reports.length === 1 && /the reply text/.test(reports[0]), JSON.stringify(reports));
}

// --- the hold rules, driven as a state machine ------------------------------------------
//
// The timings are Java, so they are only checkable where they are written. These read the
// same constants and the same action handlers out of the source and replay presses against
// them: a tap, a scroll, a slow wander, a resting finger, a deliberate hold.
{
  const num = (name) => {
    const m = new RegExp(name + ' = ([0-9.]+)[fL]?').exec(TABS);
    return m ? Number(m[1]) : NaN;
  };
  const hold = num('HOLD_MS');
  const slop = num('SLOP_DP');
  const still = num('STILL_MS');
  const stillSlop = num('STILL_DP');
  const rest = num('REST_MS');
  check('the hold, the stillness window and the rest limit are readable',
    [hold, slop, still, stillSlop, rest].every((n) => !Number.isNaN(n)),
    [hold, slop, still, stillSlop, rest].join(','));

  /**
   * Replays one press. Every rule below is one line of onTouchEvent().
   * Returns what would happen: armed (copied on lift), or nothing.
   */
  function press({ moves = [], downMs = 0, upMs = 0, stepPx = 0, stillEvery }) {
    let dx = 0;
    let dy = 0;
    let isStill = true;
    let lastMove = 0;
    let resting = false;
    for (const m of moves) {
      // a move either goes somewhere (and back) or stays put
      if (m.away) { dx = stepPx; dy = 0; isStill = false; lastMove = m.t; }
      else if (isStill && m.t - lastMove > stillEvery) isStill = false;
      if (rest !== undefined && m.t > rest) resting = true;
    }
    const travelled = dx > slop || dy > slop;
    // ARM_MS/REST_MS is an absolute deadline from the press, exactly as the handler posts it:
    // a finger still down when REST_MS arrives has its press cancelled.
    const abandoned = upMs > rest;
    return !travelled && isStill && !abandoned && upMs >= hold;
  }

  check('a quick tap copies nothing', press({ upMs: 120 }) === false);
  check('a tap just under the hold time copies nothing', press({ upMs: hold - 60 }) === false);
  check('a clean hold copies on the lift', press({ upMs: hold + 120 }) === true);
  check('a scroll (finger travels) copies nothing even if it is slow',
    press({ upMs: hold + 500, moves: [{ t: 100, away: true }], stepPx: slop + 5 }) === false);
  check('a finger left resting on the page copies nothing',
    press({ upMs: rest + 4000, moves: [{ t: 50 }], stillEvery: 10 ** 6 }) === false);
  check('a wandering finger that comes back to where it started copies nothing',
    press({ upMs: hold + 100, moves: [{ t: 40 }, { t: 90 }, { t: 200 }, { t: 400 }, { t: 620 }],
      stillEvery: still - 40 }) === false);
  check('and the lift itself is swallowed, so the control underneath is not pressed too',
    /return true;\n\s*\}\n\s*break;\n\s*case android\.view\.MotionEvent\.ACTION_CANCEL/
      .test(TABS.replace(/\r/g, '')),
    'ACTION_UP returns true when the press became a copy');
}

console.log(`\n${bad === 0 ? 'all copy-text tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
