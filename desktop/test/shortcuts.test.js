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

const { constant } = require('./script-source');

const KEYS = constant(TABS, 'JS_NEW_CHAT_LISTENER');
const CHECK = constant(TABS, 'JS_TAP_CHECK');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `  ${detail}`));
}

// --- both scripts are real JavaScript ---------------------------------------------------
check('the shortcut listener is valid JavaScript',
  (() => { try { new Function(KEYS); return true; } catch (e) { return false; } })(),
  KEYS.slice(0, 160));
check('the tap check is valid JavaScript',
  (() => { try { new Function(CHECK); return true; } catch (e) { return false; } })(),
  CHECK.slice(0, 160));

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

// --- the three-tap gesture is GONE -----------------------------------------------------
//
// "no three two to enable disable or enable text selection so move this to settings in
// more". It was a hidden shortcut for a setting that already exists, and it fired by
// accident: three taps anywhere on the page flipped text selection, so taps appeared to
// behave differently from one minute to the next. "App-style text" in Settings -> For
// developers is the only way to change it now.
{
  check('the page no longer watches for three taps',
    !/JS_TRIPLE_TAP_SELECT/.test(TABS) && !/__agonTripleTap/.test(TABS),
    'the triple-tap script must be removed, not just un-injected');
  check('and nothing installs it on a page finish',
    !/TripleTap|tripleTap/.test(TABS));
  check('the bridge call it used is gone too', !/selectToggled/.test(BRIDGE));
  check('and so is the activity handler', !/onSelectToggled/.test(MAIN));
  check('the toast that told the user to tap three times again is gone',
    !/name="select_unlocked"/.test(STRINGS) && !/name="select_locked"/.test(STRINGS));
  check('the switch itself is still there, in For developers',
    /toggle\(Settings\.K_NO_SELECT, R\.string\.settings_selectable/.test(
      fs.readFileSync(path.join(NAV, 'SettingsSheet.java'), 'utf8')));
  // One decision, made by the setting: no page flag may keep a page unlocked after the
  // switch was turned back on.
  check('the setting is the only thing that decides the page rule',
    !/__agonSelectUnlocked/.test(TABS));
}

// --- a press that is not a copy reaches the page ----------------------------------------
//
// "I have to click on anything multiple times". The touch handler swallowed every lift that
// was not a copy, on the theory that the page would copy it a moment later - so a press the
// page's own copy path refused never reached the control under the finger, and it had to be
// tapped again.
{
  const up = /case android\.view\.MotionEvent\.ACTION_UP:([\s\S]*?)break;/.exec(TABS);
  check('the lift is still swallowed when it became a copy',
    !!up && /return true;/.test(up[1]),
    up ? up[1].replace(/\s+/g, ' ').slice(0, 200) : 'ACTION_UP not found');
  check('a press that was not a copy is reported to the gate',
    !!up && /pressListener\.onPressFinished/.test(up[1]) && /false, travel/.test(up[1]));
  check('the gate exists and asks the page before doing anything',
    /private void watchPress\(/.test(TABS) && /AgonTapCheck/.test(TABS) &&
    /JS_TAP_CHECK/.test(TABS));
  const retap = TABS.slice(TABS.indexOf('private void retap('));
  check('the retry is one press, delivered to the same point',
    retap.length > 0 && /ACTION_DOWN/.test(retap.slice(0, 900)) &&
    /ACTION_UP/.test(retap.slice(0, 900)));
  check('a retry is never itself watched, so it cannot loop',
    /feedingTap = true;[\s\S]{0,600}feedingTap = false;/.test(TABS) &&
    /if \(copy \|\| feedingTap\) return;/.test(TABS));
  check('a scroll is never retried',
    /TAP_SLOP_PX/.test(TABS) && /travel > TAP_SLOP_PX/.test(TABS));
  check('the tap check is installed on every page, not only the first',
    /evaluateJavascript\(JS_TAP_CHECK/.test(TABS));
}

// The retry only fires when the page says a control did nothing.
{
  const dom = new JSDOM('<!doctype html><html><body>'
    + '<button id=b style="width:200px;height:40px">Send</button>'
    + '<div id=text>just text, no control</div></body></html>',
  { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  // jsdom has no layout, so the hit test is what the page would answer at that point.
  w.document.elementFromPoint = () => w.document.getElementById('b');
  w.eval(CHECK);
  const born = Date.now();
  check('a control that produced no click is reported as ignored',
    w.AgonTapCheck.ask(60, 20, 1, born) === true);
  check('a control that DID produce a click is left alone',
    (() => {
      w.document.getElementById('b').dispatchEvent(new w.MouseEvent('click',
        { bubbles: true, clientX: 60, clientY: 20 }));
      return w.AgonTapCheck.ask(60, 20, 1, born) === false;
    })(), JSON.stringify(w.__agonClicks));
  check('plain text is never re-delivered',
    (() => {
      w.document.elementFromPoint = () => w.document.getElementById('text');
      return w.AgonTapCheck.ask(60, 20, 1, Date.now()) === false;
    })());
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
