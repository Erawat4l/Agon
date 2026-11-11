/**
 * Tests for the two arrows in the tab strip.
 *
 * From the phone: "make the colours same and no restriction on switching tab, also see i
 * cant see all". Three separate things, in the order they were asked for:
 *
 *  1. the two arrows must look the same. The forward one was dimmed to 28% at the last tab,
 *     so a full row of tabs sat next to a greyed-out arrow - two controls that do the same
 *     job looking like two different kinds of control;
 *  2. pressing an arrow must always do something. It used to put "This is the last tab" on
 *     screen instead of moving, which is a message about a list that visibly has tabs on
 *     both sides of it;
 *  3. a tap that lands between tabs must not land on a message either.
 *
 * The Java is read, not run: there is no JDK or Android SDK in the CI sandbox, so the
 * arithmetic that decides where an arrow lands is re-implemented here exactly as the source
 * writes it, and the source is checked for the messages that are no longer used.
 *
 *   cd desktop && node test/tab-arrows.test.js
 */
const fs = require('fs');
const path = require('path');

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena',
  'webapp');
const STRIP = fs.readFileSync(path.join(NAV, 'TabStrip.java'), 'utf8');
const MAIN = fs.readFileSync(path.join(NAV, 'MainActivity.java'), 'utf8');
const STRINGS = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
  'res', 'values', 'strings.xml'), 'utf8');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `\n      ${detail}`));
}

/** The body of one method, from its signature to the brace that closes it. */
function method(src, signature) {
  const start = src.indexOf(signature);
  if (start < 0) return '';
  let i = src.indexOf('{', start);
  let depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  return '';
}

// --- 1. the arrows look the same -------------------------------------------------------
{
  const arrows = method(STRIP, 'public void setArrowsEnabled(');
  check('there is one place that decides how the arrows look', arrows.length > 0);
  check('an arrow is never dimmed', /back\.setAlpha\(1f\)/.test(arrows)
    && /forward\.setAlpha\(1f\)/.test(arrows),
  arrows.replace(/\s+/g, ' '));
  check('no stray alpha is left on either arrow anywhere in the strip',
    !/setAlpha\(0\.\d+\)/.test(STRIP), (STRIP.match(/setAlpha\([^)]*\)/g) || []).join(' '));
  check('both arrows are built the same way (same size, same tint, same padding)',
    (STRIP.match(/arrowButton\(R\.drawable\.ic_nav_(back|forward)/g) || []).length === 2
      && /true,\n\s+\(\) -> host\.onPreviousTab\(\)/.test(STRIP)
      && /true,\n\s+\(\) -> host\.onNextTab\(\)/.test(STRIP));
  check('neither arrow is disabled or hidden',
    !/back\.setEnabled/.test(STRIP) && !/forward\.setEnabled/.test(STRIP)
      && !/forward\.setVisibility/.test(STRIP));
}

// --- 2. an arrow always moves ----------------------------------------------------------
{
  const step = method(MAIN, 'private void stepTab(int direction)');
  const tabsSrc = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
  const walked = method(tabsSrc, 'public int steppedTab(int onScreen, int direction)');
  check('the left arrow goes to the previous tab and the right one to the next',
    /stepTab\(-1\)/.test(MAIN) && /stepTab\(1\)/.test(MAIN));
  check('there is no toast on the arrow path at all',
    !/toast\(/.test(step), step.replace(/\s+/g, ' ').slice(0, 260));
  // The walk itself moved into TabWebViews, so the arrows and Ctrl/Cmd+Tab cannot disagree.
  check('the arrows and Ctrl+Tab share one step',
    /tabs\.steppedTab\(pageId\(\),/.test(step), step.replace(/\s+/g, ' ').slice(0, 240));
  check('moving wraps around instead of stopping at an end',
    /%\s*row\.size\(\)\s*\+\s*row\.size\(\)\)\s*%\s*row\.size\(\)/
      .test(walked.replace(/\s+/g, ' ')), walked.replace(/\s+/g, ' ').slice(0, 300));
  check('a press is never spent on nothing',
    /if \(row\.size\(\) < 2\) return -1;/.test(walked) && /selectPage\(next\)/.test(step));
  // "same for arrow": the chat tab is part of the row, so a step from it lands on the
  // first extra tab - which was impossible while the step worked off pageId().
  const row = [0, 1000, 1001];
  const step2 = (i, d) => row[((i + d) % row.length + row.length) % row.length];
  check('from the chat tab, > goes to the first extra tab', step2(0, 1) === 1000);
  check('from the chat tab, < wraps to the last tab', step2(0, -1) === 1001);

  // The arithmetic, exactly as the source writes it.
  const land = (index, direction, count) => ((index + direction) % count + count) % count;
  check('from the last tab, > lands on the first', land(3, 1, 4) === 0);
  check('from the first tab, < lands on the last', land(0, -1, 4) === 3);
  check('in the middle it is ordinary next/previous',
    land(1, 1, 4) === 2 && land(2, -1, 4) === 1);
  check('with one tab it lands on that tab', land(0, 1, 1) === 0 && land(0, -1, 1) === 0);
}

// --- 3. the message that is gone stays gone --------------------------------------------
{
  check('"This is the last tab" is no longer produced by the arrows',
    !/R\.string\.strip_at_end/.test(MAIN) && !/R\.string\.strip_single_tab/.test(MAIN));
  check('the strings are gone too, so nothing can accidentally use them',
    !/name="strip_at_end"/.test(STRINGS) && !/name="strip_single_tab"/.test(STRINGS));
  // "nothing before it" is still the page-history message on a long press of <, which is a
  // different thing (a page that really has no history) and is still correct there.
  check('page history still has its own message',
    /name="strip_at_start"/.test(STRINGS) && /R\.string\.strip_at_start/.test(MAIN));
  check('the + still explains itself when the tab limit is reached',
    /R\.string\.window_limit/.test(MAIN));
}

// --- closing a tab never answers with a message -----------------------------------------
//
// "Also closing a tab shows (default one) that screenshot": closing the last chip put
// "That is the last tab - the + adds another" on screen for two seconds, over the page.
// The strip already says it there is nothing left to close - it draws no cross - so the
// press is answered by closing something, never by a message.
{
  const close = method(MAIN, 'private void closeTabById(int id)');
  check('closing never puts "that is the last tab" on screen',
    !/R\.string\.strip_last_tab/.test(MAIN) && !/name="strip_last_tab"/.test(STRINGS),
    (close.match(/toast\([^;]*\)/g) || []).join(' | '));
  // "If i click on that x on the default chat tab ... the default tab should only get closed
  // not the other one". There is one close path for every chip now, and it closes the id it
  // was given: no chip can close a tab it was not drawn on.
  check('a close for a chip closes that chip\'s tab, and only it',
    /tabs\.closeTab\(id\)/.test(close) && !/tabs\.windows\(\)/.test(close) &&
    !/id = open\.get\(/.test(close),
    close.replace(/\s+/g, ' ').slice(0, 320));
  check('and that is the same path Ctrl/Cmd+W takes',
    /closeTabById\(pageId\(\)\)/.test(method(MAIN, 'void onCloseTabShortcut()')));
  check('closing the chat tab destroys it rather than reloading it',
    !/loadUrl/.test(close) && /tabs\.closeTab\(id\)/.test(close));
  // Chrome's rule, and the user's own words: with one tab open there is no cross at all.
  check('a strip with one tab draws no cross',
    /boolean closable = host\.isClosable\(tabId\);/.test(
      fs.readFileSync(path.join(NAV, 'TabStrip.java'), 'utf8')));
}

// --- a background tab is not turned into a new chat while it is on screen ---------------
//
// The other half of the same complaint: bringing a window to the front and then having its
// route moved to the landing page is the flash of "What would you like to do?" over a
// conversation that was already there.
{
  // The timer is gone entirely now: a tab keeps the page it was given, so nothing can
  // re-route it to the landing page at all - on screen or in the background.
  const src = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
  const ensure = method(src, 'private WebView ensure(int tab)');
  check('no timer asks a tab for a new chat behind the user',
    !/startNewChat/.test(ensure), ensure.slice(-420));
  check('and neither does opening a tab in the background',
    !/startNewChat/.test(method(src, 'public int openTabInBackground()')),
    method(src, 'public int openTabInBackground()'));

  // A new tab is a browser tab: it starts on arena's front page, which is where arena's
  // own history button lives. That is the second report - "on chats there sidebar history
  // button but not on home page" - and the reason a new tab can no longer be a chat.
  check('a new tab is not a new chat',
    /return openWindow\(\);/.test(method(src, 'public int openTabInBackground()')));
}

// --- and the tab strip still reports where it is, for the code that asks ---------------
{
  check('the strip is still told which tab is current', /paintSelection\(current\)/.test(STRIP));
  check('the host still reports the ends of the row',
    /canSelectPrevious/.test(STRIP) && /canSelectNext/.test(STRIP));
}

console.log(`\n${bad === 0 ? 'all tab-arrow tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
