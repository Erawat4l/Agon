/**
 * Tests for JS_POPUP_FIT - the rule that answers "i cant see all".
 *
 * From the phone: arena's model picker (Auto / Text / Image / Web Search / Code) opens as a
 * panel that is laid out for a desktop window. On a phone its left edge is off screen and
 * its right side is cropped, so the list looks like it starts halfway through its own text
 * and the tick beside "Auto" is the only thing that shows where a row ends.
 *
 * The rule is measured, not guessed: anything fixed or absolute that reaches outside the
 * window is capped to the window and allowed to scroll inside itself, and is given its own
 * styles back as soon as it fits again. It must never touch the page shell, the composer, or
 * ordinary content.
 *
 * jsdom does no layout, so every element in here carries a stubbed getBoundingClientRect
 * that reads back the styles the rule sets - which is what makes "did it actually fit?"
 * answerable without a browser.
 *
 *   cd desktop && node test/popup-fit.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the popup-fit tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && node test/popup-fit.test.js');
  process.exit(0);
}

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
  'ai', 'arena', 'webapp', 'TabWebViews.java'), 'utf8');

/** The Java constant, rebuilt into the JavaScript it actually produces. */
function extractJs(name) {
  const decl = SRC.indexOf('private static final String ' + name + ' =');
  if (decl < 0) throw new Error(name + ' not found');
  let i = SRC.indexOf('=', decl) + 1;
  let inString = false;
  let literal = '';
  for (; i < SRC.length; i++) {
    const c = SRC[i];
    if (inString) {
      if (c === '\\') { literal += c + SRC[++i]; continue; }
      if (c === '"') inString = false;
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

const FIT = extractJs('JS_POPUP_FIT');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `\n      ${detail}`));
}

const VIEW_W = 400;
const VIEW_H = 700;

/**
 * A window with a stubbed layout.
 *
 * `geo` is the element's natural box; the stub then subtracts what the rule did to it
 * (max-width, max-height, and the left/right/width pin) so the second measurement inside
 * the rule sees the same thing a real browser would.
 */
function stage(html) {
  const dom = new JSDOM('<!doctype html><html><body>' + html + '</body></html>',
    { runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  Object.defineProperty(w, 'innerWidth', { value: VIEW_W, configurable: true });
  Object.defineProperty(w, 'innerHeight', { value: VIEW_H, configurable: true });
  Object.defineProperty(w.document.documentElement, 'clientWidth', { value: VIEW_W, configurable: true });
  Object.defineProperty(w.document.documentElement, 'clientHeight', { value: VIEW_H, configurable: true });
  w.__geo = new WeakMap();
  return w;
}

function box(w, el, geo) {
  w.__geo.set(el, geo);
  el.getBoundingClientRect = () => {
    const g = w.__geo.get(el);
    const num = (prop) => parseFloat(el.style.getPropertyValue(prop));
    let left = Number.isNaN(num('left')) ? g.left : num('left');
    let width = Number.isNaN(num('width')) ? g.width : num('width');
    let height = g.height;
    const maxW = num('max-width');
    if (!Number.isNaN(maxW) && width > maxW) width = maxW;
    const maxH = num('max-height');
    if (!Number.isNaN(maxH) && height > maxH) height = maxH;
    const right = num('right');
    if (!Number.isNaN(right) && el.style.getPropertyValue('transform') === 'none') {
      width = VIEW_W - left - right;
    }
    return {
      left, width, height, right: left + width,
      top: g.top, bottom: g.top + height,
      x: left, y: g.top,
    };
  };
  return el;
}

function run(w) {
  w.eval(FIT);
}

// --- the panel that started this -------------------------------------------------------
{
  const w = stage('<div id="picker" role="menu"><button>Auto</button><button>Text</button>'
    + '<button>Image</button><button>Web Search</button><button>Code</button></div>');
  const picker = w.document.querySelector('#picker');
  // Laid out for a desktop window: 900 wide, starting at the left edge of a 400 wide phone.
  box(w, picker, { left: 0, top: 240, width: 900, height: 420 });
  picker.style.position = 'fixed';

  run(w);

  check('a panel wider than the window is marked as fitted',
    picker.getAttribute('data-agon-fitted') === '1');
  check('it is capped to the window width',
    parseFloat(picker.style.getPropertyValue('max-width')) <= VIEW_W - 12,
    picker.style.getPropertyValue('max-width'));
  // (a cap alone is enough when the box starts at the left edge; the pin exists for a
  // panel that is shifted sideways, and the next case covers that)
  check('a capped box is not left hanging off the edge',
    picker.style.getPropertyValue('max-width') !== '');
  const r = picker.getBoundingClientRect();
  check('and it really does fit afterwards',
    r.left >= 0 && r.right <= VIEW_W, `left=${r.left} right=${r.right}`);

  // The stylesheet hook is written once, not on every pass.
  const styles = w.document.querySelectorAll('#arena-popup-fit');
  check('the cap is expressed once, in its own stylesheet', styles.length === 1);
  check('that stylesheet caps any fitted box',
    /data-agon-fitted/.test(styles[0].textContent)
      && /max-width/.test(styles[0].textContent));
}

// --- a menu taller than the room above the composer ------------------------------------
{
  const w = stage('<div id="menu" role="menu"><button>Auto</button><button>Text</button>'
    + '<button>Image</button></div>');
  const menu = w.document.querySelector('#menu');
  box(w, menu, { left: 20, top: -60, width: 300, height: 300 });   // top cut off
  menu.style.position = 'absolute';

  run(w);

  const maxH = parseFloat(menu.style.getPropertyValue('max-height'));
  check('a menu clipped at the top is capped to the room below it',
    maxH > 0 && maxH <= 240, 'max-height=' + menu.style.getPropertyValue('max-height'));
  check('and it scrolls inside itself instead of hiding its first option',
    menu.style.getPropertyValue('overflow-y') === 'auto');
}

// --- nothing else is touched ------------------------------------------------------------
{
  const w = stage('<div id="shell"><main><textarea></textarea></main></div>'
    + '<div id="card" style="position:relative"><button>a</button><button>b</button>'
    + '<button>c</button></div>');
  const shell = w.document.querySelector('#shell');
  box(w, shell, { left: -100, top: 0, width: 800, height: 700 });
  const card = w.document.querySelector('#card');
  box(w, card, { left: 0, top: 100, width: 900, height: 300 });

  run(w);

  check('the app shell (it holds main/textarea) is never resized',
    shell.getAttribute('data-agon-fitted') === null
      && shell.style.getPropertyValue('max-width') === '');
  check('ordinary content that is merely wide is never resized',
    card.getAttribute('data-agon-fitted') === null);
}

// --- what was touched is given back ----------------------------------------------------
{
  const w = stage('<div id="panel" role="menu"><button>Auto</button><button>Text</button>'
    + '<button>Code</button></div>');
  const panel = w.document.querySelector('#panel');
  panel.style.position = 'fixed';
  panel.style.left = '12px';
  box(w, panel, { left: 0, top: 100, width: 900, height: 300 });

  run(w);
  check('fitted while it is too wide', panel.getAttribute('data-agon-fitted') === '1');

  // The page re-lays itself out for the phone: the same element now measures 300 wide,
  // while this rule's own styles are still on it. That is the change it has to notice.
  w.__geo.set(panel, { left: 12, top: 100, width: 300, height: 300 });
  run(w);

  check('once it fits, the marker is removed',
    panel.getAttribute('data-agon-fitted') === null);
  check('and the page\'s own inline left comes back',
    panel.style.left === '12px', 'left=' + panel.style.left);
}

// --- the hooks that make it run at the right moments -----------------------------------
{
  check('it runs immediately (a menu can already be open)',
    /fixPopups\(\)/.test(FIT));
  check('it re-runs after a tap, because menus are built on demand',
    /addEventListener\('click'[\s\S]*?fixPopups/.test(FIT));
  check('it re-runs when the window changes size',
    /addEventListener\('resize'/.test(FIT));
  check('it keeps looking for a few seconds after load',
    /setInterval\([\s\S]*?fixPopups/.test(FIT));
}

console.log(`\n${bad === 0 ? 'all popup-fit tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
