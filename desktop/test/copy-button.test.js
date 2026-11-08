/**
 * Tests for two reports that were the same kind of bug: the app was sure it knew better
 * than the page.
 *
 *  1. "copy button now doesn't work" - arena's own Copy button. A hold on it counted as a
 *     hold on text, so the app put the text AROUND the button on the clipboard and the
 *     button's own handler - the only thing that knows what it is copying - never ran.
 *
 *  2. "i have to click on anything multiple time" - the WebView swallowed every lift that
 *     was not a copy, so a press the page ignored never reached the control under it.
 *
 * Both are checked from the page side, in jsdom, because both are about what the page
 * receives. The Java half (when the retry is allowed to happen) is read out of the source.
 *
 *   cd desktop && node test/copy-button.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the copy-button tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && node test/copy-button.test.js');
  process.exit(0);
}

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena', 'webapp');
const TABS = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
const { constant } = require('./script-source');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `\n      ${detail}`));
}

const COPY = constant(TABS, 'JS_COPY_API');
const CHECKJS = constant(TABS, 'JS_TAP_CHECK');

/** A page with a copy button, a click counter, and the copy script installed. */
function conversation() {
  const dom = new JSDOM('<!doctype html><html><body>'
    + '<div id=pane role="log" style="overflow-y:auto"><div id=column style="display:flex">'
    + '<p id=p1>the command: docker run -d -p 8080:80 nginx</p>'
    + '<button id=copybtn aria-label="Copy code"><svg class="lucide-copy"></svg></button>'
    + '</div></div></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  Object.defineProperty(w.HTMLElement.prototype, 'innerText', {
    get() { return this.textContent; },
    configurable: true,
  });
  w.getComputedStyle = (el) => ({
    display: el.id === 'column' ? 'flex' : 'block',
    overflow: el.id === 'pane' ? 'auto' : 'visible',
    overflowY: el.id === 'pane' ? 'auto' : 'visible',
  });
  let at = 'p1';
  w.document.elementFromPoint = () => w.document.getElementById(at);
  w.document.caretRangeFromPoint = () => {
    const r = w.document.createRange();
    const node = w.document.getElementById('p1').firstChild;
    r.setStart(node, 5);
    r.setEnd(node, 5);
    return r;
  };
  const copied = [];
  w.AgonCopyNative = { text: (t) => copied.push(t) };
  let pressed = 0;
  w.document.getElementById('copybtn').addEventListener('click', () => { pressed++; });
  w.eval(COPY);
  return {
    w,
    copied,
    pressed: () => pressed,
    over: (id) => { at = id; },
    hold: () => w.AgonCopy.under(10, 10, 1),
  };
}

// --- 1. a press on the page's own Copy button is the button's -----------------------------
{
  const page = conversation();
  page.over('copybtn');
  const handled = page.hold();
  check('a hold on the page Copy button copies nothing on the app side', handled === false,
    'the app must not claim text it does not have');
  check('and it puts nothing on the clipboard', page.copied.length === 0,
    JSON.stringify(page.copied));
}

{
  const page = conversation();
  page.over('p1');
  const handled = page.hold();
  check('a hold on ordinary text still copies that text',
    handled === true && page.copied.length === 1
    && /docker run -d -p 8080:80 nginx/.test(page.copied[0]),
    JSON.stringify(page.copied));
}

{
  // The label is what decides it, in any of the three places a page names a control.
  const cases = [
    ['aria-label', (w) => {
      const b = w.document.getElementById('copybtn');
      b.setAttribute('aria-label', 'Copy');
    }],
    ['visible text', (w) => {
      const b = w.document.getElementById('copybtn');
      b.removeAttribute('aria-label');
      b.textContent = 'Copy';
    }],
    ['title', (w) => {
      const b = w.document.getElementById('copybtn');
      b.removeAttribute('aria-label');
      b.textContent = '';
      b.setAttribute('title', 'Copy to clipboard');
    }],
  ];
  for (const [where, prepare] of cases) {
    const page = conversation();
    prepare(page.w);
    page.over('copybtn');
    check(`a Copy control named by its ${where} is left to the page`,
      page.hold() === false && page.copied.length === 0);
  }
}

{
  // A button that merely CONTAINS the word inside a longer sentence is not a copy control,
  // or every reply that mentions copying would stop working.
  const page = conversation();
  page.over('p1');
  const p = page.w.document.getElementById('p1');
  p.id = 'p1';
  check('text that mentions copying is still text',
    page.hold() === true && /docker run/.test(page.copied[0] || ''));
}

// --- 2. nothing of ours stops a press -----------------------------------------------------
//
// There was a guard here for a while that put back an event another capture-phase listener
// had stopped. It was removed: a capture listener that stops an event before it reaches the
// target has already prevented every document-phase listener, so nothing at document level
// can undo it. A press stopped that early is not fixable from the outside - it is covered by
// the retry instead, which does not care WHY the first press did nothing.
{
  check('no script of ours stops a press from reaching the page',
    !/stopImmediatePropagation/.test(TABS) && !/JS_PRESS_GUARD/.test(TABS));
  check('a press that is not a copy is handed to super',
    /pressListener\.onPressFinished\(Math\.round\(downX\), Math\.round\(downY\)/
      .test(TABS) && /return super\.onTouchEvent\(event\);/.test(TABS));
}

// --- 3. the retry is decided by the page, and only for a control --------------------------
{
  const dom = new JSDOM('<!doctype html><html><body>'
    + '<button id=b style="width:200px;height:40px">Send</button>'
    + '<p id=t>a paragraph</p></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  w.document.elementFromPoint = () => w.document.getElementById('b');
  w.eval(CHECKJS);
  const born = Date.now();

  check('a control that produced no click is re-delivered', w.AgonTapCheck.ask(10, 10, 1, born) === true);
  check('a control that produced a click is not',
    (() => {
      w.document.getElementById('b').dispatchEvent(new w.MouseEvent('click',
        { bubbles: true, clientX: 10, clientY: 10 }));
      return w.AgonTapCheck.ask(10, 10, 1, born) === false;
    })());
  check('a click somewhere else does not excuse a dead control',
    w.AgonTapCheck.ask(120, 60, 1, born) === true);
  check('text is never re-delivered',
    (() => {
      w.document.elementFromPoint = () => w.document.getElementById('t');
      return w.AgonTapCheck.ask(10, 10, 1, Date.now()) === false;
    })());
  check('a control that has gone away is not re-delivered',
    (() => {
      w.document.elementFromPoint = () => w.document.getElementById('b');
      w.document.getElementById('b').remove();
      return w.AgonTapCheck.ask(10, 10, 1, Date.now()) === false;
    })());
}

// --- 4. the Java side only retries what it should ----------------------------------------
{
  check('a retry waits long enough for the real click to land first',
    /TAP_RETRY_MS = 450L/.test(TABS));
  check('a finger that travelled is never re-delivered',
    /travel > TAP_SLOP_PX/.test(TABS) && /TAP_SLOP_PX = 24f/.test(TABS));
  check('only the tab on screen is retried', /if \(web != current\(\)\) return;/.test(TABS));
  check('the press is delivered, not synthesised as a click',
    /web\.dispatchTouchEvent\(android\.view\.MotionEvent\.obtain\(/.test(TABS)
    && !/performClick\(\)/.test(TABS.slice(TABS.indexOf('private void retap('))));
}

// --- 5. a hold over a button presses the button -----------------------------------------
//
// The other half of "I have to tap twice": a hold on a button copies nothing (a button is
// not text) and used to press nothing either - the app had already decided the press was a
// copy. Now the page says which of the two happened, and the app presses the control.
{
  const dom = new JSDOM('<!doctype html><html><body>'
    + '<button id=send style="width:200px;height:40px">Send</button>'
    + '<a id=link href="/x">a link</a></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  let at = 'send';
  w.document.elementFromPoint = () => w.document.getElementById(at);
  const acted = [];
  w.AgonCopyNative = {
    text: () => { throw new Error('a button is not text'); },
    acted: (x, y) => acted.push([x, y]),
  };
  w.eval(COPY);

  // The press itself, then the hold 700 ms later.
  w.AgonCopy.press(100, 40, 1);
  check('the hold on a button copies nothing', w.AgonCopy.under(100, 40, 1) === false);
  check('...and asks the app to press the button instead', acted.length === 1,
    JSON.stringify(acted));
  check('with the point in the page\'s own pixels',
    acted[0][0] === 100 && acted[0][1] === 40, JSON.stringify(acted));
}

{
  // A press the page DID deliver must never be pressed again: that would be a double tap.
  const dom = new JSDOM('<!doctype html><html><body>'
    + '<button id=send style="width:200px;height:40px">Send</button></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  w.document.elementFromPoint = () => w.document.getElementById('send');
  const acted = [];
  w.AgonCopyNative = { text: () => {}, acted: () => acted.push(1) };
  w.eval(COPY);
  w.AgonCopy.press(100, 40, 1);
  w.AgonCopy.hit(100, 40, 1);          // the click landed
  w.AgonCopy.under(100, 40, 1);
  check('a button that already received the press is not pressed again', acted.length === 0);
}

{
  // A link is the one control that is never re-pressed: it navigates.
  const dom = new JSDOM('<!doctype html><html><body>'
    + '<a id=link href="/somewhere" style="display:block;width:200px">a link</a></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  w.document.elementFromPoint = () => w.document.getElementById('link');
  const acted = [];
  w.AgonCopyNative = { text: () => {}, acted: () => acted.push(1) };
  w.eval(COPY);
  w.AgonCopy.press(100, 10, 1);
  w.AgonCopy.under(100, 10, 1);
  check('a hold on a link copies nothing and navigates nowhere', acted.length === 0);
}

// The Java half: the app only ever presses a point on the tab that is on screen, and only
// through a path that cannot itself be re-watched.
{
  check('the bridge the page calls exists',
    /@JavascriptInterface\s+public void acted\(final float cssX, final float cssY\)/.test(
      fs.readFileSync(path.join(NAV, 'WebBridge.java'), 'utf8')));
  check('the activity converts the page\'s CSS pixels to device pixels',
    /tabs\.tapAt\(Math\.round\(cssX \* density\), Math\.round\(cssY \* density\)\)/.test(
      fs.readFileSync(path.join(NAV, 'MainActivity.java'), 'utf8')));
  check('a re-press is never itself re-watched',
    /public void tapAt\(int xPx, int yPx\)[\s\S]{0,200}retap\(w, xPx, yPx\)/.test(TABS));
}

console.log(`\n${bad === 0 ? 'all copy-button tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
