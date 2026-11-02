/**
 * Tests for the Back-press-as-Escape script inside the Android app.
 *
 * Arena's own overlays (the "Was this task successful?" card, the model menu, the file
 * rail) are drawn inside the page and close on Escape - that card prints "Esc" in its
 * corner. A phone has no Escape key, so MainActivity turns a Back press into one. If the
 * dispatch does not work, Back stops closing the card and starts navigating away from the
 * chat instead, which is worse than not having the feature.
 *
 * The script is EXTRACTED FROM TabWebViews.java, so editing the Java cannot leave these
 * tests passing against stale code.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/escape.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the escape tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && ' +
    'node test/escape.test.js');
  process.exit(0);
}

/** Pulls JS_ESCAPE out of the Java source and undoes the Java string escaping. */
function extract() {
  const file = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
    'ai', 'arena', 'webapp', 'TabWebViews.java');
  const src = fs.readFileSync(file, 'utf8');
  const start = src.indexOf('private static final String JS_ESCAPE =');
  if (start < 0) throw new Error('JS_ESCAPE not found');
  const end = src.indexOf(';\n', src.indexOf('})();', start));
  let js = '';
  for (const m of src.slice(start, end).matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const JS = extract();

/** jsdom has no layout, so every element reports a card-sized rect at the top. */
const RECT = { width: 1000, height: 300, top: 100, bottom: 400, left: 0, right: 1000 };

async function run(build) {
  const dom = new JSDOM('<!doctype html><html><body></body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  w.HTMLElement.prototype.getBoundingClientRect = () => RECT;
  let answered = null;
  w.AgonEscape = { report(v) { answered = v; } };
  build(w);
  w.eval(JS);
  await new Promise(r => setTimeout(r, 250));
  return answered;
}

const cases = [];
const test = (name, build, want) => cases.push({ name, build, want });

// The real card: fixed, at the top, removes itself when it hears Escape.
test('card closes on Escape', w => {
  const card = w.document.createElement('div');
  card.style.position = 'fixed';
  card.innerHTML = '<p>Was this task successful?</p><button>Yes</button>';
  w.document.body.appendChild(card);
  w.addEventListener('keydown', e => { if (e.key === 'Escape') card.remove(); });
}, true);

// Nothing open: the activity must let Back fall through to navigation.
test('nothing open reports false', () => {}, false);

// A page listening on document rather than window.
test('document-level listener', w => {
  const card = w.document.createElement('div');
  card.style.position = 'fixed';
  w.document.body.appendChild(card);
  w.document.addEventListener('keydown', e => { if (e.key === 'Escape') card.remove(); });
}, true);

// A handler that only reads the legacy keyCode/which fields.
test('legacy keyCode-only handler', w => {
  const card = w.document.createElement('div');
  card.style.position = 'fixed';
  w.document.body.appendChild(card);
  w.document.addEventListener('keyup', e => { if (e.which === 27) card.remove(); });
}, true);

(async () => {
  let bad = 0;
  for (const c of cases) {
    const got = await run(c.build);
    const ok = got === c.want;
    if (!ok) bad++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${c.name}  reported=${got}`);
  }
  console.log(`\n${cases.length - bad}/${cases.length} PASS`);
  process.exit(bad === 0 ? 0 : 1);
})();
