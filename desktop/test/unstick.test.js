/**
 * Tests for JS_UNSTICK - the page-side repair for a stuck floating toolbar.
 *
 * From the screen recording: after typing in the composer, a strip of three icons comes
 * to rest over the sidebar, the composer stays in edit state, and the page stops
 * responding to taps. The fix has to (a) make a page let go of such a float and
 * (b) say truthfully whether it is still stuck - because the app reloads on a "still
 * stuck" answer, and must not reload a page that is fine.
 *
 * The script is EXTRACTED FROM TabWebViews.java, not copied, so it cannot drift.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/unstick.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the unstick tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && node test/unstick.test.js');
  process.exit(0);
}

const java = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
  'ai', 'arena', 'webapp', 'TabWebViews.java'), 'utf8');

/**
 * Rebuilds the Java string literal concatenation into the JavaScript it produces.
 *
 * It cannot stop at the first ';' - the JavaScript itself is full of semicolons, and a
 * first version did exactly that and extracted 35 characters of a 1531-character script
 * while still "finding" the constant. So the literal is walked character by character,
 * tracking whether it is inside a Java string, and ends only at a ';' outside one.
 */
function extractJavaJs(constName) {
  const decl = java.indexOf('private static final String ' + constName + ' =');
  if (decl < 0) throw new Error(constName + ' not found');
  let i = java.indexOf('=', decl) + 1;
  let inString = false;
  let literal = '';
  for (; i < java.length; i++) {
    const c = java[i];
    if (inString) {
      if (c === '\\') { literal += c + java[++i]; continue; }
      if (c === '"') { inString = false; }
      literal += c;
      continue;
    }
    if (c === '"') { inString = true; literal += c; continue; }
    if (c === ';') break;
    literal += c;
  }
  const parts = literal.match(/"(?:[^"\\]|\\.)*"/g) || [];
  return parts.map((p) => JSON.parse(p)).join('');
}

const unstick = extractJavaJs('JS_UNSTICK');
if (!unstick || !unstick.includes('AgonEscape.stuck')) {
  console.error('extract failed');
  process.exit(1);
}

let bad = 0;
function check(name, ok, extra) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  ' + extra : ''}`);
}

/**
 * A page with a real layout. jsdom has no layout engine, so getBoundingClientRect is
 * stubbed per element from the data-* box it declares - the shape is what is under test,
 * not jsdom's measurement.
 */
function page(bodyHtml, opts = {}) {
  const dom = new JSDOM(`<!doctype html><html><body>${bodyHtml}</body></html>`,
    { runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  Object.defineProperty(w, 'innerHeight', { value: 800, configurable: true });
  Object.defineProperty(w, 'innerWidth', { value: 640, configurable: true });

  // every element with data-box="w,h,top" gets that rectangle
  for (const el of w.document.querySelectorAll('[data-box]')) {
    const [width, height, top] = el.getAttribute('data-box').split(',').map(Number);
    el.getBoundingClientRect = () => ({
      width, height, top, left: 0, right: width, bottom: top + height, x: 0, y: top,
    });
    // computed style from data-style, so the position test is explicit
    const style = el.getAttribute('data-style') || 'position:fixed;visibility:visible';
    const map = {};
    for (const pair of style.split(';')) {
      const [k, v] = pair.split(':');
      if (k && v) map[k.trim()] = v.trim();
    }
    const original = w.getComputedStyle.bind(w);
    el.__style = map;
  }
  const realGet = w.getComputedStyle.bind(w);
  w.getComputedStyle = (el) => {
    const base = realGet(el);
    if (el && el.__style) {
      return new Proxy(base, {
        get(target, prop) {
          if (prop in el.__style) return el.__style[prop];
          const v = target[prop];
          return typeof v === 'function' ? v.bind(target) : v;
        },
      });
    }
    return base;
  };

  const answers = [];
  const events = [];
  w.AgonEscape = { stuck: (v) => answers.push(v) };
  for (const type of ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'keydown']) {
    w.document.addEventListener(type, (e) => events.push(type + ':' + (e.key || '')), true);
  }
  return { w, answers, events };
}

// --- the float exactly as measured from the recording: a VERTICAL pill (keyboard,
// emoji, menu) about 55x250 CSS px, parked over the left edge of the page
{
  const { w, answers, events } = page(
    `<div id=side data-box="200,600,60" data-style="position:static;visibility:visible">
       <a href=#>New Chat</a></div>
     <div id=pill data-box="55,250,80" data-style="position:fixed;visibility:visible">
       <button>keyboard</button><button>emoji</button><button>menu</button></div>
     <textarea id=box></textarea>`);
  w.document.querySelector('#box').focus();
  check('a field is focused before the repair', w.document.activeElement.id === 'box');
  w.eval(unstick);
  check('reports still stuck for the vertical pill', answers[0] === true, `answers=${answers}`);
  check('focus is released', w.document.activeElement.id !== 'box');
  check('a pointer press was dispatched', events.some((e) => e.startsWith('pointerdown')),
    events.join(','));
  check('Escape was dispatched', events.includes('keydown:Escape'));
}

// --- the horizontal form of the same thing is also recognised
{
  const { w, answers } = page(
    `<div id=strip data-box="180,48,300" data-style="position:fixed;visibility:visible">
       <button>a</button><button>b</button><button>c</button></div>`);
  w.eval(unstick);
  check('a horizontal strip of controls is recognised too', answers[0] === true, `answers=${answers}`);
}

// --- an emoji picker that holds a text field is content, not a stuck toolbar
{
  const { w, answers } = page(
    `<div id=pick data-box="60,300,80" data-style="position:fixed;visibility:visible">
       <input placeholder="search"><button>a</button><button>b</button><button>c</button></div>`);
  w.eval(unstick);
  check('a panel containing a text field is left alone', answers[0] === false, `answers=${answers}`);
}

// --- a normal page: no float, so the app must NOT reload it
{
  const { w, answers } = page(
    `<div id=side data-box="200,600,60" data-style="position:static;visibility:visible">
       <a href=#>New Chat</a></div>
     <main id=body data-box="440,700,60" data-style="position:static;visibility:visible">
       <p>hello</p></main>`);
  w.eval(unstick);
  check('a clean page reports not stuck', answers[0] === false, `answers=${answers}`);
}

// --- the page's own bottom bar is not mistaken for the stuck toolbar
{
  const { w, answers } = page(
    `<nav id=bar data-box="640,54,746" data-style="position:fixed;visibility:visible">
       <button>chat</button><button>board</button><button>search</button><button>more</button>
     </nav>`);
  w.eval(unstick);
  check('the bottom nav bar is not treated as a stuck toolbar', answers[0] === false,
    `answers=${answers}`);
}

// --- a single chip or byline (few controls) is not a toolbar
{
  const { w, answers } = page(
    `<div id=chip data-box="300,40,120" data-style="position:fixed;visibility:visible">
       <button>close</button></div>`);
  w.eval(unstick);
  check('a two-control chip is not a toolbar', answers[0] === false, `answers=${answers}`);
}

// --- a wide panel that is not short (a real dialog) is left alone
{
  const { w, answers } = page(
    `<div id=dlg data-box="400,420,120" data-style="position:fixed;visibility:visible">
       <button>a</button><button>b</button><button>c</button></div>`);
  w.eval(unstick);
  check('a tall dialog is not a toolbar', answers[0] === false, `answers=${answers}`);
}

// --- the bridge being absent must not throw, and must not claim success
{
  const dom = new JSDOM('<!doctype html><body><div id=x></div></body>', { runScripts: 'outside-only' });
  let threw = false;
  try { dom.window.eval(unstick); } catch (e) { threw = true; }
  check('a missing bridge does not throw', !threw);
}

console.log(bad === 0 ? '\nall unstick tests passed' : `\n${bad} FAILED`);
process.exit(bad === 0 ? 0 : 1);
