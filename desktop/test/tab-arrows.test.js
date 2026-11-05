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
  check('the left arrow goes to the previous tab and the right one to the next',
    /stepTab\(-1\)/.test(MAIN) && /stepTab\(1\)/.test(MAIN));
  check('there is no toast on the arrow path at all',
    !/toast\(/.test(step), step.replace(/\s+/g, ' ').slice(0, 260));
  check('moving wraps around instead of stopping at an end',
    /%\s*count\)?\s*\+\s*count\)\s*%\s*count/.test(step.replace(/\s+/g, ' ')),
    step.replace(/\s+/g, ' ').slice(0, 300));
  check('a press is never spent on nothing',
    /if \(ids\.isEmpty\(\)\) return;/.test(step) && /selectPage\(/.test(step));

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

// --- and the tab strip still reports where it is, for the code that asks ---------------
{
  check('the strip is still told which tab is current', /paintSelection\(current\)/.test(STRIP));
  check('the host still reports the ends of the row',
    /canSelectPrevious/.test(STRIP) && /canSelectNext/.test(STRIP));
}

console.log(`\n${bad === 0 ? 'all tab-arrow tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
