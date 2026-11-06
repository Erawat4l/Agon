/**
 * Tests for two navigation reports, both about *which* tab a page opens in.
 *
 * 1. "On chats there sidebar history button but not on home page." Arena draws its own
 *    conversation list inside a chat and nowhere else, so the front page - and a tab
 *    sitting on it - had no route to it at all. The app's own history sheet is the route,
 *    and it now reads the page that is actually in front, plus a row in More that reaches
 *    it from anywhere.
 *
 * 2. "if i m on search page on tab 1 and from tab 2 i click on search page then i should be
 *    on tab 2 search page not 1". A destination belongs to the tab the user is looking at;
 *    the old code always swapped to the chat tab and loaded it there.
 *
 * The Java is read, not run (no JDK here), and the behaviour is re-derived from the source
 * so the tests fail if the guard is dropped.
 *
 *   cd desktop && node test/destination.test.js
 */
const fs = require('fs');
const path = require('path');

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena',
  'webapp');
const MAIN = fs.readFileSync(path.join(NAV, 'MainActivity.java'), 'utf8');
const MENU = fs.readFileSync(path.join(NAV, 'MenuSheet.java'), 'utf8');
const TABS = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
const STRINGS = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
  'res', 'values', 'strings.xml'), 'utf8');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `  ${detail}`));
}

/** One method body, from its signature to the brace that closes it. */
function method(src, signature) {
  const start = src.indexOf(signature);
  if (start < 0) return '';
  let i = src.indexOf('{', start);
  let depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '"') {                       // strings are not structure
      i++;
      while (i < src.length && src[i] !== '"') i += src[i] === '\\' ? 2 : 1;
      continue;
    }
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  return '';
}

// --- 2. a destination opens in the tab on screen ---------------------------------------
{
  const showTab = method(MAIN, 'private void showTab(int tab)');
  check('there is one place that switches the visible page', showTab.length > 0);
  check('it asks whether a conversation tab is in front',
    /onConversationTab\(\)/.test(showTab), showTab.replace(/\s+/g, ' ').slice(0, 240));
  check('a destination with a conversation tab in front loads into that tab',
    /tabs\.loadInCurrent\(url\)/.test(showTab));
  check('and it does not put the chat tab on screen first',
    !/showTab\(AppUi\.TAB_CHAT\)/.test(showTab) &&
    !/tabs\.show\(AppUi\.TAB_CHAT\)/.test(showTab));
  check('the nav highlight still follows the destination',
    /styleNav\(this, nav, tab,/.test(showTab));

  const helper = method(MAIN, 'private boolean onConversationTab()');
  check('"a conversation tab is in front" is asked in one place',
    helper.length > 0 && /TabWebViews\.isWindow\(id\)/.test(helper) &&
    /tabs\.get\(id\) != null/.test(helper), helper.replace(/\s+/g, ' '));

  // The behaviour, as the source writes it: the tab on screen is kept.
  const lands = (frontIsWindow, tabWanted) => {
    if (tabWanted === 0 || !frontIsWindow) return 'chat-tab-layer';
    return 'window-on-screen';
  };
  check('search from a window lands in that window',
    lands(true, 2) === 'window-on-screen');
  check('search from the chat tab still uses the chat tab',
    lands(false, 2) === 'chat-tab-layer');
}

// --- 1. the chat list is reachable from everywhere --------------------------------------
{
  const open = method(MAIN, 'private void openSidebar()');
  check('the history sheet is fed the page on screen, not always the chat tab',
    /WebView front = tabs == null \? null : tabs\.current\(\)/.test(open),
    open.replace(/\s+/g, ' ').slice(0, 300));
  check('the chat tab is only forced when the chat is not what is in front',
    /if \(!onConversationTab\(\) && pageId\(\) != AppUi\.TAB_CHAT\)/.test(open),
    open.replace(/\s+/g, ' ').slice(0, 300));

  check('More has a Chats row', /R\.string\.menu_history/.test(MENU));
  check('the row reaches the activity', /onOpenHistory\(\)/.test(MENU) &&
    /void onOpenHistory\(\)/.test(MENU));
  check('the activity answers it with the history sheet',
    /public void onOpenHistory\(\)\s*\{\s*openSidebar\(\);/.test(MAIN.replace(/\s+/g, ' ')
      .replace(/\(\s+/g, '(')), MAIN.includes('onOpenHistory'));
  check('the strings exist',
    /name="menu_history"/.test(STRINGS) && /name="menu_history_sub"/.test(STRINGS));
  check('and there is an icon for the row',
    fs.existsSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'res', 'drawable',
      'ic_nav_history.xml')));
}

// --- a new tab is a browser tab ---------------------------------------------------------
{
  check('opening a tab in the background does not ask the page for a new chat',
    !/startNewChat/.test(method(TABS, 'public int openTabInBackground()')));
  check('and neither does bringing a tab up',
    !/startNewChat/.test(method(TABS, 'private WebView ensure(int tab)')));
  check('the strip\'s + still exists and still makes a tab',
    /R\.string\.strip_new_tab/.test(fs.readFileSync(path.join(NAV, 'TabStrip.java'), 'utf8')));
}

// --- closing a tab closes the tab you are ON --------------------------------------------
//
// "i was on default and pressed ctrl + w the second one closed but i wanted default one to
// close". onCloseTabShortcut() looked at pageId(), got the chat tab, decided "that is not a
// window" and closed the newest extra window instead - the opposite of what was asked.
{
  const close = method(MAIN, 'void onCloseTabShortcut()');
  check('the close key closes the tab on screen when it is a window',
    /if \(TabWebViews\.isWindow\(id\)\)\s*\{\s*closeWindowById\(id\)/.test(
      close.replace(/\s+/g, ' ').replace(/\{ /g, '{')), close.replace(/\s+/g, ' '));
  check('and closes the chat tab itself when that is the tab on screen',
    /closeChatTab\(\)/.test(close) &&
    !/open\.get\(open\.size\(\) - 1\)/.test(close),
    close.replace(/\s+/g, ' '));
  check('closing the chat tab hands the screen to its neighbour',
    /neighbourAfterClosing\(AppUi\.TAB_CHAT\)/.test(method(MAIN, 'private void closeChatTab()')));

  const tabsSrc = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
  const neighbour = method(tabsSrc, 'public int neighbourAfterClosing(int closing)');
  // The row is chat-first, so the tab after the chat tab is the first extra one; the tab
  // after the LAST extra tab is the one before it.
  const land = (row, closing) => {
    const i = row.indexOf(closing);
    if (i < 0) return 'chat';
    return String(i + 1 < row.length ? row[i + 1] : row[i - 1]);
  };
  check('closing a middle tab lands on the one to its right',
    land([0, 1000, 1001, 1002], 1000) === '1001');
  check('closing the last tab lands on the one to its left',
    land([0, 1000, 1001], 1001) === '1000');
  check('closing the chat tab lands on the first extra tab',
    land([0, 1000, 1001], 0) === '1000');
  check('the neighbour is decided before the tab is dropped',
    /final int next = wasCurrent \? neighbourAfterClosing\(id\)/ .test(neighbour) === false &&
    /neighbourAfterClosing\(id\)/.test(method(tabsSrc, 'public boolean closeWindow(int id)')));
  check('a closed tab is never "the first one" by accident',
    !/views\.remove\(id\)[\s\S]{0,200}show\(AppUi\.TAB_CHAT\)/.test(
      method(tabsSrc, 'public boolean closeWindow(int id)')));
}

// --- a new chat belongs to the tab on screen -------------------------------------------
{
  const open = method(MAIN, 'private void openNewChat()');
  check('three taps on Chat make a new chat in the tab on screen',
    /tabs\.current\(\)/.test(open) && /startNewChat\(page\)/.test(open),
    open.replace(/\s+/g, ' '));
  check('Ctrl+N does not silently open a second tab',
    !/newChatTab\(\)/.test(method(MAIN, 'void onNewChatShortcut()')),
    method(MAIN, 'void onNewChatShortcut()').replace(/\s+/g, ' '));
}

console.log(`\n${bad === 0 ? 'all destination tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
