/**
 * Tests for the two keyboard shortcuts and the tap gesture that replace them on a phone.
 *
 * From the device: "ctrl + tab to switch tab, ctrl + w to close and clicking three times
 * would unlock select (also can be toggled from settings)". Three things, and each one is
 * a thin page-side listener over something the app already does - which is exactly why they
 * are worth pinning: a listener that calls a bridge method nobody implemented is silent.
 *
 * Both page scripts are EXTRACTED FROM TabWebViews.java, so the Java cannot drift away from
 * these tests, and the handlers are read out of the activity and the bridge.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/shortcuts.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the shortcut tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && ' +
    'node test/shortcuts.test.js');
  process.exit(0);
}

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena',
  'webapp');
const TABS = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
const MAIN = fs.readFileSync(path.join(NAV, 'MainActivity.java'), 'utf8');
const BRIDGE = fs.readFileSync(path.join(NAV, 'WebBridge.java'), 'utf8');
const STRINGS = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
  'res', 'values', 'strings.xml'), 'utf8');

/** One `private static final String NAME = ...;` constant, rebuilt as the script it makes. */
function constant(name) {
  const start = TABS.indexOf('private static final String ' + name + ' =');
  if (start < 0) throw new Error(name + ' not found');
  const end = TABS.indexOf(';\n', start);
  let js = '';
  for (const line of TABS.slice(start, end).split('\n')) {
    if (/^\s*\/\//.test(line)) continue;
    for (const m of line.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  }
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const KEYS = constant('JS_NEW_CHAT_LISTENER');
const TRIPLE = constant('JS_TRIPLE_TAP_SELECT');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `  ${detail}`));
}

// --- both scripts are real JavaScript ---------------------------------------------------
check('the shortcut listener is valid JavaScript',
  (() => { try { new Function(KEYS); return true; } catch (e) { return false; } })(),
  KEYS.slice(0, 160));
check('the triple-tap script is valid JavaScript',
  (() => { try { new Function(TRIPLE); return true; } catch (e) { return false; } })(),
  TRIPLE.slice(0, 160));

// --- Ctrl+Tab / Ctrl+W / Ctrl+N ---------------------------------------------------------
/** Runs the listener in a page and reports which bridge calls each key press produced. */
function key(call) {
  const dom = new JSDOM('<!doctype html><html><body><div id=x>hi</div></body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  const calls = [];
  w.ArenaNative = {
    newChat: () => calls.push('newChat'),
    cycleTab: (d) => calls.push('cycleTab:' + d),
    closeTab: () => calls.push('closeTab'),
  };
  w.eval(KEYS);
  const ev = new w.KeyboardEvent('keydown', call);
  let prevented = false;
  w.document.addEventListener('keydown', () => {});
  w.document.dispatchEvent(ev);
  prevented = ev.defaultPrevented;
  return { calls, prevented, w };
}

{
  const fwd = key({ key: 'Tab', ctrlKey: true, bubbles: true, cancelable: true });
  check('Ctrl+Tab asks for the next tab', JSON.stringify(fwd.calls) === '["cycleTab:1"]',
    JSON.stringify(fwd.calls));
  check('and the page does not also handle the key', fwd.prevented === true);

  const back = key({ key: 'Tab', ctrlKey: true, shiftKey: true, bubbles: true,
    cancelable: true });
  check('Ctrl+Shift+Tab asks for the previous tab',
    JSON.stringify(back.calls) === '["cycleTab:-1"]', JSON.stringify(back.calls));

  const meta = key({ key: 'Tab', metaKey: true, bubbles: true, cancelable: true });
  check('Cmd+Tab works the same way, for a keyboard on a Mac layout',
    JSON.stringify(meta.calls) === '["cycleTab:1"]', JSON.stringify(meta.calls));

  const close = key({ key: 'w', ctrlKey: true, bubbles: true, cancelable: true });
  check('Ctrl+W closes the tab on screen', JSON.stringify(close.calls) === '["closeTab"]',
    JSON.stringify(close.calls));
  check('the page does not navigate away on Ctrl+W', close.prevented === true);

  const newChat = key({ key: 'n', ctrlKey: true, bubbles: true, cancelable: true });
  check('Ctrl+N still opens a new chat tab', JSON.stringify(newChat.calls) === '["newChat"]',
    JSON.stringify(newChat.calls));

  const plain = key({ key: 'w', bubbles: true, cancelable: true });
  check('a plain w is just a letter', plain.calls.length === 0, JSON.stringify(plain.calls));
  const shiftW = key({ key: 'W', ctrlKey: true, shiftKey: true, bubbles: true,
    cancelable: true });
  check('Ctrl+Shift+W is left alone (a browser closes the window)', shiftW.calls.length === 0,
    JSON.stringify(shiftW.calls));
}

// --- the activity and the bridge answer both keys ---------------------------------------
{
  check('the bridge exposes cycleTab and closeTab',
    /@JavascriptInterface\s+public void cycleTab\(final int direction\)/.test(BRIDGE) &&
    /@JavascriptInterface\s+public void closeTab\(\)/.test(BRIDGE));
  check('the activity steps the strip for a tab key',
    /void onCycleTabShortcut\(int direction\)/.test(MAIN) &&
    /stepTab\(direction < 0 \? -1 : 1\)/.test(MAIN));
  const close = /void onCloseTabShortcut\(\)\s*\{[\s\S]*?\n    \}/.exec(MAIN);
  check('a close key closes a window and says nothing',
    !!close && /closeWindowById\(id\)/.test(close[0]) &&
    !/toast\(/.test(close[0]),
    close ? close[0].replace(/\s+/g, ' ').slice(0, 220) : 'not found');
}

// --- three taps toggle "App-style text" -------------------------------------------------
/** A page with the guard installed and a policy stylesheet, as the app writes them. */
function taps() {
  const dom = new JSDOM('<!doctype html><html><head></head><body><div id=reply>a reply</div>' +
    '<textarea id=composer></textarea></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  const style = w.document.createElement('style');
  style.id = 'arena-select-policy';
  style.textContent = '*{user-select:none !important;}';
  w.document.head.appendChild(style);
  const seen = [];
  w.ArenaNative = { selectToggled: (on) => seen.push(on) };
  w.__agonNoSelect = function () {};
  w.eval(TRIPLE);
  const tap = (id, times) => {
    const el = w.document.getElementById(id);
    for (let i = 0; i < times; i++) {
      el.dispatchEvent(new w.Event('touchstart', { bubbles: true }));
    }
  };
  return { w, tap, seen };
}

{
  const { w, tap, seen } = taps();
  check('nothing happens on one or two taps', (tap('reply', 2), seen.length === 0));
  tap('reply', 1);
  check('the third tap unlocks selection', seen[seen.length - 1] === true,
    JSON.stringify(seen));
  check('the rule that stopped it is gone',
    w.document.getElementById('arena-select-policy') === null);
  check('and so is the listener that cancelled selections', w.__agonNoSelect === null);
  check('it can be locked again with three more taps',
    (tap('reply', 3), seen[seen.length - 1] === false), JSON.stringify(seen));
  check('the stylesheet is put back, not re-derived',
    !!w.document.getElementById('arena-select-policy') &&
    /user-select:none/.test(w.document.getElementById('arena-select-policy').textContent));

  const typing = taps();
  typing.tap('composer', 3);
  check('three taps in the composer never toggle the setting', typing.seen.length === 0,
    JSON.stringify(typing.seen));
}

// --- ...and the setting is what actually changes ----------------------------------------
{
  check('the page reports the toggle to the app',
    /@JavascriptInterface\s+public void selectToggled\(final boolean unlocked\)/.test(BRIDGE));
  check('the activity writes the setting, so Settings and the next launch agree',
    /void onSelectToggled\(boolean unlocked\)/.test(MAIN) &&
    /Settings\.set\(this, Settings\.K_NO_SELECT, !unlocked\)/.test(MAIN));
  check('it says which way the switch went',
    /R\.string\.select_unlocked/.test(MAIN) && /R\.string\.select_locked/.test(MAIN) &&
    /name="select_unlocked"/.test(STRINGS) && /name="select_locked"/.test(STRINGS));
  check('the gesture is installed on every page, not only on the first one',
    /evaluateJavascript\(JS_TRIPLE_TAP_SELECT/.test(TABS));
}

// --- copying does not depend on Android's selection menu any more -----------------------
{
  // The page used to copy on its own 450 ms timer as well; that is gone (it fired while the
  // user was still dragging). The hold is the WebView's, and the page only answers with the
  // text under the point, or copies the block the app selected.
  check('a held press copies the text under it',
    /AgonCopy\.under/.test(TABS) && /AgonCopyNative/.test(TABS) &&
    /window\.__agonCopyTap/.test(TABS));
  check('the page has no copy timer of its own any more',
    !/holdTimer=setTimeout/.test(TABS) && !/__agonHold=function/.test(TABS));
  check('the app writes a native clip when the page hands the text over',
    /@JavascriptInterface\s+public void copied\(final String text\)/.test(BRIDGE) &&
    /void onPageCopied\(String text\)/.test(MAIN) &&
    /setPrimaryClip\(ClipData\.newPlainText/.test(MAIN));
  check('a page that cannot copy at all says what to use instead',
    /copy_needs_menu/.test(STRINGS) && /onCopyUnavailable/.test(MAIN));
}

console.log(`\n${bad === 0 ? 'all shortcut tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
