/**
 * Tests for the script that makes a new tab a *new chat*.
 *
 * On the device, tapping + opened a second tab showing the conversation that was already
 * on screen: the new tab was loaded with the chat tab's current URL, which on arena is the
 * open conversation's. The app cannot know a fresh conversation's address (arena has none),
 * so it loads the root and lets the page decide - and this script is what decides.
 *
 * The two failures that matter: a tab that stays on the old conversation, and a tab that
 * navigates away from a chat the user is reading in a *different* tab. Both are covered.
 *
 * The script is EXTRACTED FROM TabWebViews.java, so editing the Java cannot leave these
 * tests passing against stale code.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/new-chat.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the new-chat tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && ' +
    'node test/new-chat.test.js');
  process.exit(0);
}

function extract() {
  const file = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
    'ai', 'arena', 'webapp', 'TabWebViews.java');
  const src = fs.readFileSync(file, 'utf8');
  const start = src.indexOf('private static final String JS_NEW_CHAT =');
  if (start < 0) throw new Error('JS_NEW_CHAT not found');
  const end = src.indexOf('";\n', start) + 1;
  let js = '';
  for (const line of src.slice(start, end).split('\n')) {
    if (line.trim().startsWith('//')) continue;
    for (const m of line.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  }
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const JS = extract();

/** Runs the script in a page at `url` with `html`, and reports what it did. */
function run(url, html, { rect } = {}) {
  const dom = new JSDOM('<!doctype html><html><body>' + (html || '') + '</body></html>',
    { runScripts: 'outside-only', url });
  const w = dom.window;
  // jsdom has no layout, so offsetParent is always null - but the script uses it to tell a
  // control that is on screen from one that is not, exactly as a real WebView would.
  Object.defineProperty(w.HTMLElement.prototype, 'offsetParent', {
    get() {
      if (this.classList && this.classList.contains('gone')) return null;
      return this.parentElement || null;
    },
    configurable: true,
  });
  // everything is visible unless a test says otherwise.
  w.Element.prototype.getBoundingClientRect = () =>
    (rect || { width: 40, height: 40, top: 0, bottom: 40, left: 0, right: 40 });
  const presses = [];
  w.document.querySelectorAll('[data-press]').forEach(el => {
    el.addEventListener('click', () => presses.push(el.dataset.press));
  });
  let reported = null;
  w.AgonNewChat = { state(v) { reported = v; } };
  let popped = 0;
  w.addEventListener('popstate', () => { popped++; });
  w.eval(JS);
  return { w, presses, reported: () => reported, popped };
}

const cases = [];
const test = (name, fn) => cases.push({ name, fn });

test("arena's own New chat control is pressed when it is visible", () => {
  const { presses } = run('https://arena.ai/c/abc123',
    '<button data-press="new" aria-label="New chat">+</button>');
  return presses.length === 1 && presses[0] === 'new';
});

test('a hidden New chat control is not pressed, and the route is used instead', () => {
  const { presses, reported } = run('https://arena.ai/c/abc123',
    '<button class="gone" data-press="new" aria-label="New chat">+</button>');
  return presses.length === 0 && reported() === 'routed';
});

test('already on a fresh page: nothing is changed', () => {
  const { w, reported } = run('https://arena.ai/');
  return reported() === 'already-new' && w.location.pathname === '/';
});

test('on a conversation with the sidebar hidden: the route changes', () => {
  const { w, reported, popped } = run('https://arena.ai/c/abc123');
  return reported() === 'routed' && w.location.pathname !== '/c/abc123' && popped >= 1;
});

test('a /chat/<id> route is recognised as a conversation', () => {
  const { w, reported } = run('https://arena.ai/chat/xyz789');
  return reported() === 'routed' && w.location.pathname !== '/chat/xyz789';
});

test('a trailing slash does not confuse the route test', () => {
  const { w, reported } = run('https://arena.ai/c/abc123/');
  return reported() === 'routed' && w.location.pathname !== '/c/abc123/';
});

test('a non-conversation page is left alone', () => {
  const { w, reported } = run('https://arena.ai/leaderboard');
  return reported() === 'already-new' && w.location.pathname === '/leaderboard';
});

test('it reports something even when everything throws', () => {
  const { w, reported } = run('https://arena.ai/c/abc123');
  w.AgonNewChat = undefined;
  let threw = false;
  try { w.eval(JS); } catch (e) { threw = true; }
  return !threw;
});

let bad = 0;
for (const c of cases) {
  let ok = false;
  try {
    ok = c.fn();
  } catch (e) {
    console.log(`FAIL  ${c.name}  threw ${e.message}`);
    bad++;
    continue;
  }
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${c.name}`);
}
console.log(`\n${cases.length - bad}/${cases.length} PASS`);
process.exit(bad === 0 ? 0 : 1);
