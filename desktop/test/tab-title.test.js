/**
 * Tests for the script that reads a conversation's name out of arena's page.
 *
 * The device showed a tab called "Arena | Benchma..." for a conversation about something
 * else. Arena is a single-page app and never changes document.title per conversation, so
 * WebView.getTitle() is the same marketing string everywhere - the name has to come from
 * the page. The failures that matter are the ones that put the wrong name on a tab: the
 * site's own tagline, the word "Chat", or a stray heading of the page's chrome.
 *
 * The script is EXTRACTED FROM TabWebViews.java, so editing the Java cannot leave these
 * tests passing against stale code.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/tab-title.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the tab-title tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && ' +
    'node test/tab-title.test.js');
  process.exit(0);
}

function extract() {
  const file = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
    'ai', 'arena', 'webapp', 'TabWebViews.java');
  const src = fs.readFileSync(file, 'utf8');
  const start = src.indexOf('private static final String JS_TAB_TITLE =');
  if (start < 0) throw new Error('JS_TAB_TITLE not found');
  const end = src.indexOf('";\n', start) + 1;
  let js = '';
  for (const line of src.slice(start, end).split('\n')) {
    if (line.trim().startsWith('//')) continue;
    for (const m of line.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  }
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const JS = extract();

/** Runs the script over a page and returns every name it reported. */
async function names(html, waitMs = 1200) {
  const dom = new JSDOM('<!doctype html><html><body>' + html + '</body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/c/abc123' });
  const w = dom.window;
  // jsdom has no layout: a message block is "visible" unless it is marked .gone.
  w.Element.prototype.getBoundingClientRect = function () {
    if (this.classList && this.classList.contains('gone')) {
      return { width: 0, height: 0, top: 0, bottom: 0, left: 0, right: 0 };
    }
    return { width: 600, height: 40, top: 0, bottom: 40, left: 0, right: 600 };
  };
  const seen = [];
  w.AgonTitle = { name(v) { seen.push(v); } };
  w.eval(JS);
  await new Promise(r => setTimeout(r, waitMs));
  return seen;
}

const cases = [];
const test = (name, fn) => cases.push({ name, fn });

test('an explicit conversation heading wins', async () => {
  const got = await names('<h1>Fixing my CSS grid</h1><article>hello there</article>');
  return got[0] === 'Fixing my CSS grid';
});

test('without a heading, the first message is the name', async () => {
  const got = await names(
    '<div class="message">How do I make a tab strip in Android? I want arrows.</div>');
  return got[0] === 'How do I make a tab strip in Android';
});

test("arena's own tagline is never used as a name", async () => {
  const got = await names(
    '<h1>Arena | Benchmarking the world\'s best AI models</h1>' +
    '<div class="message">Plan my trip to Jaipur</div>');
  return got[0] === 'Plan my trip to Jaipur';
});

test('the word Chat is not a conversation name', async () => {
  const got = await names('<h2>Chat</h2><div class="message">Explain vectors simply</div>');
  return got[0] === 'Explain vectors simply';
});

test('an empty new chat reports nothing', async () => {
  const got = await names('<h1>New chat</h1><textarea></textarea>');
  return got.length === 0;
});

test('a heading that holds the composer is not a message', async () => {
  const got = await names(
    '<h2>New chat<textarea></textarea></h2>' +
    '<div class="message">Summarise this PDF for me</div>');
  return got.length === 1 && got[0] === 'Summarise this PDF for me';
});

test('an invisible message is skipped', async () => {
  const got = await names(
    '<div class="message gone">hidden draft text here</div>' +
    '<div class="message">Second visible question</div>');
  return got.length === 1 && got[0] === 'Second visible question';
});

test('a name arriving later is reported (the answer streams in)', async () => {
  const dom = new JSDOM('<!doctype html><html><body><div id=box></div></body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/c/abc123' });
  const w = dom.window;
  w.Element.prototype.getBoundingClientRect = () =>
    ({ width: 600, height: 40, top: 0, bottom: 40, left: 0, right: 600 });
  const seen = [];
  w.AgonTitle = { name(v) { seen.push(v); } };
  w.eval(JS);
  await new Promise(r => setTimeout(r, 500));
  const p = w.document.createElement('p');
  p.className = 'message';
  p.textContent = 'Late arriving question about taxes';
  w.document.getElementById('box').appendChild(p);
  await new Promise(r => setTimeout(r, 1400));
  return seen.length >= 1 && seen[seen.length - 1] === 'Late arriving question about taxes';
});

test('installed twice, one name is reported once', async () => {
  const dom = new JSDOM('<!doctype html><html><body><h1>Only once</h1></body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/c/abc123' });
  const w = dom.window;
  w.Element.prototype.getBoundingClientRect = () =>
    ({ width: 600, height: 40, top: 0, bottom: 40, left: 0, right: 600 });
  const seen = [];
  w.AgonTitle = { name(v) { seen.push(v); } };
  w.eval(JS);
  w.eval(JS);
  await new Promise(r => setTimeout(r, 1200));
  return seen.length === 1;
});

(async () => {
  let bad = 0;
  for (const c of cases) {
    let ok = false;
    try {
      ok = await c.fn();
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
})();
