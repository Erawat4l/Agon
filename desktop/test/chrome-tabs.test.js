// Tabs behave like Chrome's: the cross closes the tab it is drawn on and nothing else, a
// strip with one tab has no cross at all, and closing the last tab leaves an empty app
// rather than a replacement tab.
//
// "I clicked on X in default chat icon it didn't closed bruh it just gone on home page. I
// want cmplt off that chat." The chat tab used to be immortal: its cross reloaded it to
// arena's front page, which looked exactly like "it went home instead of closing".
//
// Nothing here needs jsdom: it is all about what the Java does, not what a page does.
//
// Run: cd desktop && node test/chrome-tabs.test.js

const fs = require('fs');
const path = require('path');

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena',
  'webapp');
const MAIN = fs.readFileSync(path.join(NAV, 'MainActivity.java'), 'utf8');
const TABS = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
const STRIP = fs.readFileSync(path.join(NAV, 'TabStrip.java'), 'utf8');
const STRINGS = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
  'res', 'values', 'strings.xml'), 'utf8');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `  ${detail}`));
}

/** One Java method body, by its signature line. */
function method(src, signature) {
  const at = src.indexOf(signature);
  if (at < 0) return '';
  let i = src.indexOf('{', at), depth = 0;
  for (let j = i; j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}') {
      depth--;
      if (depth === 0) return src.slice(i, j + 1);
    }
  }
  return src.slice(i);
}

// --- 1. one close path, and it closes the tab it was given ------------------------------
{
  const byId = method(MAIN, 'private void closeTabById(int id)');
  check('there is one close path for every chip', byId.length > 0);
  check('it closes the id it was handed', /tabs\.closeTab\(id\)/.test(byId));
  check('the old reload-the-chat-tab path is gone',
    !/closeChatTab|closeWindowById/.test(MAIN), 'MainActivity still mentions them');
  check('the tab layer closes any tab, chat included',
    /if \(id != AppUi\.TAB_CHAT && !isWindow\(id\)\) return false;/.test(
      method(TABS, 'public boolean closeTab(int id)')));
  check('and a closed tab is really destroyed',
    /w\.destroy\(\)/.test(method(TABS, 'public boolean closeTab(int id)')));
}

// --- 2. Chrome's cross rule -------------------------------------------------------------
{
  check('a strip with one tab draws no cross',
    /boolean closable = host\.isClosable\(tabId\);/.test(STRIP) &&
    /boolean isClosable\(int tabId\);/.test(STRIP));
  check('the cross is only added on that branch',
    /if \(closable\) \{[\s\S]{0,900}onCloseTab/.test(STRIP));
  check('a row that became empty is redrawn, not repainted around',
    /ids\.size\(\)/.test(method(STRIP, 'public void refresh()')));
}

// --- 3. the last tab leaves the app empty -----------------------------------------------
{
  const byId = method(MAIN, 'private void closeTabById(int id)').replace(/\s+/g, ' ');
  check('closing the last tab shows the empty screen',
    /tabs\.isEmpty\(\)\) \{\s*currentTab = AppUi\.TAB_CHAT;\s*showEmpty\(\);/.test(byId),
    byId.slice(0, 300));
  check('and no tab is invented to replace it',
    !/openChatTab|ensure\(/.test(byId));
  const empty = method(TABS, 'public int neighbourAfterClosing(int closing)');
  check('the tab layer says "nothing left" with -1', /return -1;/.test(empty));
  check('the empty screen exists with one way out',
    /empty_tabs_title/.test(STRINGS) && /empty_tabs_open/.test(STRINGS) &&
    /openChatTabFromEmpty/.test(MAIN));
  check('+ with nothing open opens the first tab instead of a second one',
    /if \(tabs\.isEmpty\(\)\) \{\s*openChatTabFromEmpty\(\);/.test(
      method(MAIN, 'private void newChatTab()').replace(/\s+/g, ' ')));
  check('tapping Chat after closing it opens a tab again',
    /tabs\.openChatTab\(\)/.test(method(MAIN, 'private void showChat()')));
  check('the empty screen goes away as soon as a tab is shown',
    /hideEmpty\(\)/.test(method(MAIN, 'private void showTab(int tab)')) &&
    /hideEmpty\(\)/.test(method(MAIN, 'private void switchWindow(int id)')));
}

// --- 3b. the empty state survives the things that could quietly undo it ------------------
{
  check('a rotation does not hand back the tabs the user closed',
    /STATE_NO_TABS/.test(method(MAIN, 'protected void onSaveInstanceState(Bundle outState)')) &&
    /STATE_NO_TABS/.test(method(MAIN, 'private void buildShell(Bundle savedInstanceState)')));
  check('a destination with no tabs open opens one to load into',
    /if \(tabs\.isEmpty\(\)\) tabs\.openChatTab\(\);/.test(
      method(MAIN, 'private void showTab(int tab)')));
  check('a new chat with no tabs opens one first',
    /openChatTabFromEmpty\(\)/.test(method(MAIN, 'private void openNewChat()')));
  check('tapping Chat opens a tab, it never shows a tab that is not there',
    /tabs\.openChatTab\(\)/.test(method(MAIN, 'private void showChat()')));
}

// --- 3c. the cross is per tab, and a press on it is never destroyed under the finger ------
//
// "if i have 2 or more tabs opened then if i try to close the default then its just send to
// home screen not actually closes". Two real causes, both here:
//
//  a. the strip rebuilt every chip whenever a tab's NAME changed - names change constantly
//     while an answer streams - so a press could land on a view that was detached before the
//     click fired;
//  b. closing a tab let the tab layer swap to the neighbour by itself, and the activity then
//     "showed the neighbour" even when the closed tab was in the background.
{
  check('the strip asks, per tab, whether it may be closed',
    /host\.isClosable\(tabId\)/.test(STRIP) &&
    /boolean closable = host\.isClosable\(tabId\);/.test(STRIP));
  check('the host answers Chrome\'s rule: no cross that would leave no tab',
    /return ids\.size\(\) > 1 && ids\.contains\(tabId\);/.test(
      method(MAIN, 'public boolean isClosable(int tabId)')));
  check('with two tabs open, the default chat tab is closable',
    /ids\.size\(\) > 1/.test(method(MAIN, 'public boolean isClosable(int tabId)')));

  const refresh = method(STRIP, 'public void refresh()');
  check('a name change does not rebuild the row',
    !/tabName\(id\)/.test(refresh.split('signature')[0]) &&
    /for \(Integer id : ids\) key\.append\('\|'\)\.append\(id\);/.test(refresh),
    refresh.slice(0, 400).replace(/\s+/g, ' '));
  check('a renamed tab is relabelled in place instead',
    /relabel\(ids\)/.test(refresh) && /private void relabel\(java\.util\.List<Integer> ids\)/.test(STRIP));
  const relabel = method(STRIP, 'private void relabel(java.util.List<Integer> ids)');
  check('relabelling adds and removes nothing',
    !/addView|removeView|removeAllViews/.test(relabel) && /setText\(want\)/.test(relabel));
}

// --- 3d. closing a tab never brings a different tab forward ------------------------------
{
  const closeTab = method(TABS, 'public boolean closeTab(int id)');
  check('the tab layer closes the page and stops there',
    !/show\(/.test(closeTab), closeTab.replace(/\s+/g, ' ').slice(0, 300));
  check('but it remembers which tab should follow',
    /lastClosedNeighbour = next;/.test(closeTab));
  const byId = method(MAIN, 'private void closeTabById(int id)').replace(/\s+/g, ' ');
  check('the activity shows the next tab only when the closed tab was on screen',
    /if \(wasCurrent\) \{\s*if \(next >= 0/.test(byId), byId.slice(0, 500));
  check('and never loads anything',
    !/loadUrl|evaluateJavascript/.test(byId));
}

// --- 4. the row is the tab layer's, not a guess -----------------------------------------
{
  const open = method(MAIN, 'private java.util.List<Integer> openTabs()');
  check('the strip lists exactly the open tabs', /tabs\.tabs\(\)/.test(open));
  check('the chat tab is not added back in by the activity',
    !/ids\.add\(AppUi\.TAB_CHAT\)/.test(open), open.replace(/\s+/g, ' ').slice(0, 240));
  const tabs = method(TABS, 'public java.util.List<Integer> tabs()');
  check('the chat tab is leftmost when it exists',
    /row\.add\(0, AppUi\.TAB_CHAT\)/.test(tabs));
  check('nothing is opened in the constructor behind the user\'s back',
    !/ensure\(AppUi\.TAB_CHAT\);/.test(method(TABS, 'public TabWebViews(Activity activity, FrameLayout container, Host host)')));
}

console.log(`\n${bad === 0 ? 'all chrome-tabs tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
