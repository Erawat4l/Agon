/**
 * Tests for "there are so many random touches in app".
 *
 * A press that visibly reacts somewhere other than where the finger is can only come from
 * four places, and three of them are the app itself:
 *
 *  1. `JS_NEW_CHAT` — "start a new chat" used to click the first element matching
 *     `a[href="/"]`, which is the site logo, the sidebar's home row, or anything else that
 *     links to the front page;
 *  2. `JS_UNSTICK` — Back sent a real pointer press at a fixed point on the left edge
 *     without looking at what was there, so it could press a control on the way past;
 *  3. `closeChatTab()` — fired `startNewChat()` 900 ms after navigating, i.e. clicked
 *     "new chat" into a half-loaded document;
 *  4. the user's own finger.
 *
 * The fourth one is why there is a switch: with "Show what a tap lands on" enabled every
 * press names the control it reached, so what actually happened is visible instead of
 * guessed.
 *
 *   cd desktop && node test/random-taps.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the random-taps tests.');
  process.exit(0);
}

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena',
  'webapp');
const TABS = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
const MAIN = fs.readFileSync(path.join(NAV, 'MainActivity.java'), 'utf8');
const BRIDGE = fs.readFileSync(path.join(NAV, 'WebBridge.java'), 'utf8');
const SETTINGS = fs.readFileSync(path.join(NAV, 'Settings.java'), 'utf8');
const SHEET = fs.readFileSync(path.join(NAV, 'SettingsSheet.java'), 'utf8');
const STRINGS = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
  'res', 'values', 'strings.xml'), 'utf8');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `  ${detail}`));
}

/** One `private static final String NAME = ...;` constant, rebuilt as the script it makes. */
function constant(name) {
  const start = TABS.indexOf('private static final String ' + name + ' =');
  if (start < 0) throw new Error(name + ' not found');
  const end = TABS.indexOf('\n\n', start);
  let js = '';
  for (const line of TABS.slice(start, end).split('\n')) {
    if (/^\s*\/\//.test(line)) continue;
    for (const m of line.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  }
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const NEW_CHAT = constant('JS_NEW_CHAT');
const UNSTICK = constant('JS_UNSTICK');
const WATCH = constant('JS_TAP_WATCH');

// --- 1. a new chat never clicks a link ------------------------------------------------
{
  check('the new-chat script is valid JavaScript',
    (() => { try { new Function(NEW_CHAT); return true; } catch (e) { return false; } })());
  check('it no longer targets a plain link to the front page',
    !/a\[href=/ .test(NEW_CHAT) || !/href="?\/"?/.test(NEW_CHAT),
    NEW_CHAT.slice(0, 240));
  check('it never clicks an anchor at all',
    /if\(el\.tagName==='A'\)continue;/.test(NEW_CHAT));

  // Driven for real: a page whose only "front page" link is the logo.
  const run = (html) => {
    const dom = new JSDOM('<!doctype html><html><body>' + html + '</body></html>',
      { runScripts: 'outside-only', url: 'https://arena.ai/c/abc123' });
    const w = dom.window;
    const clicked = [];
    w.Element.prototype.offsetParent = null;                 // jsdom has no layout
    w.document.querySelectorAll('a,button').forEach((el) => {
      el.addEventListener('click', () => clicked.push(el.id || el.tagName));
    });
    Object.defineProperty(w.HTMLElement.prototype, 'offsetParent', {
      get() { return this.id === 'hidden' ? null : this.parentElement || w.document.body; },
      configurable: true,
    });
    let state = null;
    w.AgonNewChat = { state: (v) => { state = v; } };
    w.eval(NEW_CHAT);
    return { clicked, state };
  };

  const logo = run('<a id=logo href="/">Arena</a><button id=go>Go</button>');
  check('a page whose only front-page link is the logo is not clicked',
    logo.clicked.length === 0, JSON.stringify(logo.clicked));
  check('and the route is changed instead', logo.state === 'routed', String(logo.state));

  const real = run('<button id=newchat aria-label="New chat">+</button>'
    + '<a id=logo href="/">Arena</a>');
  check("arena's own New chat button IS clicked",
    JSON.stringify(real.clicked) === '["newchat"]', JSON.stringify(real.clicked));
}

// --- 2. Back never presses a control on its way past ----------------------------------
{
  check('the unstick script is valid JavaScript',
    (() => { try { new Function(UNSTICK); return true; } catch (e) { return false; } })());
  check('it checks whether the point is inert before pressing it',
    /function inert\(el\)/.test(UNSTICK) && /if\(inert\(cand\)\)target=cand;/.test(UNSTICK));
  check('nothing is pressed when every candidate is a control',
    /if\(target\)\{/.test(UNSTICK));

  // A page where the left edge is a row of buttons: the press must not land on one.
  const dom = new JSDOM('<!doctype html><html><body>'
    + '<div id=list><button id=b1>Row one</button><button id=b2>Row two</button></div>'
    + '</body></html>', { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  const pressed = [];
  w.document.addEventListener('mousedown', (e) => {
    pressed.push((e.target.id || e.target.tagName));
  });
  w.document.addEventListener('pointerdown', (e) => {
    pressed.push('pointer:' + (e.target.id || e.target.tagName));
  });
  w.document.elementFromPoint = () => w.document.getElementById('b1');
  w.AgonEscape = { stuck: () => {} };
  w.eval(UNSTICK);
  check('a left-edge press over a button reaches no button',
    pressed.filter((p) => /b1/.test(p)).length === 0, JSON.stringify(pressed));
}

// --- 3. closing the chat tab does not click into a half-loaded page --------------------
{
  const close = /private void closeChatTab\(\)\s*\{[\s\S]*?\n    \}/.exec(MAIN);
  check('there is one method that closes the chat tab', !!close);
  const code = close ? close[0].split('\n').filter((l) => !/^\s*\/\//.test(l)).join('\n') : '';
  check('it only loads the root, and asks the page for nothing',
    /loadUrl\(TabWebViews\.urlFor\(AppUi\.TAB_CHAT\)\)/.test(code) &&
    !/startNewChat/.test(code) && !/postDelayed/.test(code),
    code.replace(/\s+/g, ' ').slice(0, 260));
}

// --- 4. ...so the remaining presses can be named --------------------------------------
{
  check('the watcher is valid JavaScript',
    (() => { try { new Function(WATCH); return true; } catch (e) { return false; } })());
  const watchCode = WATCH.split(';').filter((part) => !/^\s*\/\*/.test(part)).join(';');
  check('it never changes the page (no click, no focus, no preventDefault)',
    !/\.click\(\)/.test(watchCode) && !/preventDefault/.test(watchCode) &&
    !/\.focus\(/.test(watchCode), watchCode.slice(0, 200));

  const dom = new JSDOM('<!doctype html><html><body>'
    + '<div id=row><span id=inner>some text</span></div>'
    + '<button id=btn aria-label="Send the message"><span id=lab>Send</span></button>'
    + '</body></html>', { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  Object.defineProperty(w.HTMLElement.prototype, 'innerText', {
    get() { return this.textContent; }, configurable: true,
  });
  const boxes = {
    btn: { left: 10, top: 200, width: 80, height: 40 },
    inner: { left: 0, top: 0, width: 60, height: 20 },
  };
  w.Element.prototype.getBoundingClientRect = function () {
    const b = boxes[this.id] || { left: 0, top: 0, width: 100, height: 20 };
    return { ...b, right: b.left + b.width, bottom: b.top + b.height,
      x: b.left, y: b.top };
  };
  let seen = null;
  w.AgonTapWatch = { tap: (json) => { seen = JSON.parse(json); } };
  w.document.elementFromPoint = () => w.document.getElementById('lab');
  w.eval(WATCH);
  const ev = new w.Event('touchstart', { bubbles: true });
  ev.touches = [{ clientX: 20, clientY: 210 }];
  w.document.getElementById('lab').dispatchEvent(ev);

  check('a press inside a button is reported as that button',
    !!seen && seen.hit === 'button' && /Send the message/.test(seen.hitName || ''),
    JSON.stringify(seen));
  check('the element actually under the finger is reported too',
    !!seen && seen.tag === 'span', JSON.stringify(seen));
  check('and the box says where it is, so an overlay can be recognised',
    !!seen && seen.x === 50 && seen.y === 220 && seen.w === 80 && seen.h === 40,
    JSON.stringify(seen));
}

// --- the switch ------------------------------------------------------------------------
{
  check('the setting exists and is off by default',
    /K_TAP_WATCH = "tap_watch"/.test(SETTINGS) &&
    /return get\(c, K_TAP_WATCH, false\)/.test(SETTINGS));
  check('it has a row in Settings',
    /toggle\(Settings\.K_TAP_WATCH/.test(SHEET));
  check('with a description that says what it is for',
    /name="settings_tap_watch"/.test(STRINGS) &&
    /name="settings_tap_watch_sub"/.test(STRINGS));
  check('the page reports presses through the bridge',
    /@JavascriptInterface\s+public void tap\(final String json\)/.test(BRIDGE));
  check('the activity reads the name and the box, and shows both',
    /void onPageTap\(String json\)/.test(MAIN) && /hitName/.test(MAIN) &&
    /toastText\(text\)/.test(MAIN));
    /void onPageTap\(String json\)/.test(MAIN) &&
    /optString\("hitName"\)/.test(MAIN.replace(/\s+/g, '')) === false
  check('and it draws nothing unless the switch is on',
    /if \(!Settings\.tapWatch\(this\)\) return;/.test(MAIN));
  check('the watcher is installed on every page',
    /evaluateJavascript\(JS_TAP_WATCH/.test(TABS));
}

console.log(`\n${bad === 0 ? 'all random-tap tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
