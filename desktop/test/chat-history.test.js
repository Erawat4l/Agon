/**
 * Tests for the chat-history scraper inside the Android app.
 *
 * Two device builds shipped a sheet that always said "nothing here", for two different
 * reasons (light DOM only; then shadow roots only, when in fact the list simply is not on
 * the page at the moment the sheet opens). So this suite covers the case that actually
 * failed: the list arriving *after* the script has run.
 *
 * The script is EXTRACTED FROM ChatHistorySheet.java, not copied, so editing the Java
 * cannot leave these tests passing against stale code.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/chat-history.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the history-scraper tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && ' +
    'node test/chat-history.test.js');
  process.exit(0);
}

/** Pulls JS_HISTORY out of the Java source and undoes the Java string escaping. */
function extract() {
  const file = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
    'ai', 'arena', 'webapp', 'ChatHistorySheet.java');
  const src = fs.readFileSync(file, 'utf8');
  const start = src.indexOf('private static final String JS_HISTORY =');
  const end = src.indexOf('/** Receives the list from the page.');
  if (start < 0 || end < 0) throw new Error('JS_HISTORY not found');
  let js = '';
  for (const line of src.slice(start, end).split('\n')) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('+ "') && !trimmed.startsWith('"')) continue;
    for (const m of trimmed.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  }
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const JS = extract();

/** Runs the scraper in a page, collecting whatever it reports. */
async function scrape(build, waitMs = 400) {
  const dom = new JSDOM('<!doctype html><html><body></body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  let rows = [];
  const take = json => {
    const parsed = JSON.parse(json);
    if (parsed.length) rows = parsed;
  };
  w.AgonHistory = { receive: take, done: take };
  build(w);
  w.eval(JS);
  await new Promise(r => setTimeout(r, waitMs));
  return rows;
}

const cases = [];
const test = (name, build, want) => cases.push({ name, build, want });

// A chat list that is already in the document.
test('light DOM links', w => {
  w.document.body.innerHTML =
    '<a href="/c/abc123">Fix my CSS</a><a href="/c/def456">Write a poem</a>' +
    '<a href="/about">About</a>';
}, rows => rows.length === 2 && rows[0].u === 'https://arena.ai/c/abc123');

// The list inside an open shadow root.
test('shadow root links', w => {
  const host = w.document.createElement('div');
  w.document.body.appendChild(host);
  host.attachShadow({ mode: 'open' }).innerHTML = '<a href="/c/zzz999">Shadow chat</a>';
}, rows => rows.length === 1 && rows[0].u.includes('/c/zzz999'));

// The case the device ships failed: nothing exists yet, it renders a moment later.
test('list rendered after the script ran', w => {
  setTimeout(() => {
    const host = w.document.createElement('div');
    w.document.body.appendChild(host);
    host.attachShadow({ mode: 'open' }).innerHTML =
      '<a href="/c/late42">Late chat</a><a href="/c/late43">Another</a>';
  }, 120);
}, rows => rows.length === 2 && rows.some(r => r.u.includes('/c/late42')));

// Rows that are buttons carrying an id, with no anchors at all.
test('buttons carrying an id', w => {
  w.document.body.innerHTML = '<button data-id="chat_88ab12">Older chat</button>' +
    '<div role="link" data-href="/c/init77">From data-href</div>';
}, rows => rows.length === 2 && rows.some(r => r.u.includes('/c/chat_88ab12')));

// No list in the DOM at all: the ids an app keeps in storage are the only trace.
test('storage fallback', w => {
  w.localStorage.setItem('arena:chats',
    JSON.stringify(['9f8e7d6c-1111-2222-3333-444455556666', 'aaaa-bbbb-cccc-dddd']));
}, rows => rows.length >= 1 && rows.every(r => r.u.startsWith('https://arena.ai/c/')));

// An empty page must not invent rows - a wrong row navigates the tab somewhere random.
test('empty page yields nothing', w => {
  w.document.body.innerHTML = '<p>hi</p>';
}, rows => rows.length === 0);

// Duplicates collapse, non-conversation links drop, and another host is never a row.
test('dedupe, drop, and off-site', w => {
  w.document.body.innerHTML =
    '<a href="/c/same1">One</a><a href="/c/same1">One again</a>' +
    '<a href="/pricing">Pricing</a><a href="https://example.com/c/nope">Offsite</a>';
}, rows => rows.length === 1 && rows[0].u === 'https://arena.ai/c/same1');

(async () => {
  let bad = 0;
  for (const c of cases) {
    let rows;
    try {
      rows = await scrape(c.build);
    } catch (e) {
      console.log(`FAIL  ${c.name}  threw ${e.message}`);
      bad++;
      continue;
    }
    const ok = c.want(rows);
    if (!ok) bad++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${c.name}  -> ${rows.length} row(s)` +
      (ok ? '' : `  ${JSON.stringify(rows.map(r => r.u))}`));
  }
  console.log(`\n${cases.length - bad}/${cases.length} PASS`);
  process.exit(bad === 0 ? 0 : 1);
})();
