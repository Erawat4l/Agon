/**
 * Tests for the Enter-to-send listener injected into arena's page.
 *
 * Reported from the device: "Esc works, Enter does not". Escape is handled by the page
 * itself, so the key is reaching the document - the composer's own send-on-Enter handler
 * is what does not see it. The app cannot intercept that from Java without breaking every
 * other field on the page, so the page gets a listener that presses the composer's own
 * send control.
 *
 * That is code rewriting someone else's page, so the failures that matter here are the
 * destructive ones: sending twice, sending on Shift+Enter, sending an empty box, or
 * clicking something that is not the send button. All covered below.
 *
 * The script is EXTRACTED FROM TabWebViews.java, so editing the Java cannot leave these
 * tests passing against stale code.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/enter-send.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the enter-to-send tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && ' +
    'node test/enter-send.test.js');
  process.exit(0);
}

function extract() {
  const file = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
    'ai', 'arena', 'webapp', 'TabWebViews.java');
  const src = fs.readFileSync(file, 'utf8');
  const start = src.indexOf('private static final String JS_ENTER_SEND =');
  if (start < 0) throw new Error('JS_ENTER_SEND not found');
  // The statement ends at the first `";` followed by a newline - the JS itself contains
  // semicolons and `})();`, so searching for those would cut the constant in half.
  // +1 so the closing quote of the last literal is inside the slice: the constant ends
  // with `"})();";`, and cutting at the quote would drop the IIFE's closing brace.
  const end = src.indexOf('";\n', start) + 1;
  if (end < 1) throw new Error('JS_ENTER_SEND is not terminated');
  let js = '';
  // Java comment lines are skipped: a comment that mentions a JS string (a selector, a
  // button type) would otherwise be spliced into the script as code.
  for (const line of src.slice(start, end).split('\n')) {
    if (line.trim().startsWith('//')) continue;
    for (const m of line.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  }
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

const JS = extract();

/** A page with an arena-shaped composer: a box on the left, the send button on the right. */
function build(html) {
  const dom = new JSDOM('<!doctype html><html><body>' + html + '</body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  // jsdom has no layout: everything a test wants visible is visible, everything marked
  // .gone is not (the send button is disabled by the page while the box is empty).
  w.Element.prototype.getBoundingClientRect = function () {
    if (this.classList && this.classList.contains('gone')) {
      return { width: 0, height: 0, top: 0, bottom: 0, left: 0, right: 0 };
    }
    return { width: 40, height: 40, top: 100, bottom: 140, left: 0, right: 40 };
  };
  const presses = [];
  w.document.querySelectorAll('[data-send]').forEach(el => {
    el.addEventListener('click', () => presses.push(el.dataset.send));
  });
  w.eval(JS);
  return { w, presses };
}

/** Presses Enter in an element, the way a hardware keyboard would. */
function enter(w, el, extra = {}) {
  el.dispatchEvent(new w.KeyboardEvent('keydown', Object.assign({
    key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true,
  }, extra)));
}

function typeInto(w, el, text) {
  if (el.tagName === 'TEXTAREA') el.value = text;
  else el.textContent = text;
}

const COMPOSER = `
  <div class="composer">
    <textarea id="box"></textarea>
    <button id="send" data-send="send" aria-label="Send message"></button>
  </div>`;

const cases = [];
const test = (name, fn) => cases.push({ name, fn });

test('Enter in a filled box presses send once', () => {
  const { w, presses } = build(COMPOSER);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box);
  return presses.length === 1;
});

test('Shift+Enter is left to the page (a newline)', () => {
  const { w, presses } = build(COMPOSER);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box, { shiftKey: true });
  return presses.length === 0;
});

test('an empty box never sends', () => {
  const { w, presses } = build(COMPOSER);
  const box = w.document.getElementById('box');
  typeInto(w, box, '   ');
  enter(w, box);
  return presses.length === 0;
});

test('Enter outside a text field is ignored', () => {
  const { w, presses } = build(COMPOSER + '<input id="search">');
  enter(w, w.document.getElementById('search'));
  return presses.length === 0;
});

test('an IME composition in progress is not a send', () => {
  const { w, presses } = build(COMPOSER);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box, { keyCode: 229, isComposing: true });
  return presses.length === 0;
});

test('a disabled send button is not pressed', () => {
  const { w, presses } = build(`
    <div class="composer"><textarea id="box"></textarea>
      <button id="send" class="gone" data-send="send" aria-label="Send message"></button>
    </div>`);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box);
  return presses.length === 0;
});

test('a form with its own submit is left alone', () => {
  const { w, presses } = build(`
    <form><textarea id="box"></textarea>
      <button id="send" data-send="send" aria-label="Send message"></button></form>`);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box);
  return presses.length === 0;
});

test('the wrong button in the composer is not clicked', () => {
  const { w, presses } = build(`
    <div class="composer"><textarea id="box"></textarea>
      <button id="attach" data-send="attach" aria-label="Attach a file"></button>
      <button id="model" data-send="model">Auto</button>
    </div>`);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box);
  return presses.length === 0;
});

test('a contenteditable composer works too', () => {
  const { w, presses } = build(`
    <div class="composer"><div id="box" contenteditable="true"></div>
      <button id="send" data-send="send" title="Send"></button></div>`);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box);
  return presses.length === 1;
});

test('installed twice, one Enter is still one send', () => {
  const { w, presses } = build(COMPOSER);
  w.eval(JS);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box);
  return presses.length === 1;
});

test('Ctrl+Enter is not turned into a send the page did not ask for', () => {
  const { w, presses } = build(COMPOSER);
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box, { ctrlKey: true });
  return presses.length === 0;
});

test('a composer with no send control sends nothing and throws nothing', () => {
  const { w } = build('<div class="composer"><textarea id="box"></textarea></div>');
  const box = w.document.getElementById('box');
  typeInto(w, box, 'hello');
  enter(w, box);
  return true;
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
