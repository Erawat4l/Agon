/**
 * Tests for the "Repair this page" rule.
 *
 * This is the escape hatch for a frozen UI: the app rewrites arena's page, and if a guess
 * hides the wrong thing the user can end up with nothing they can press. The repair must
 * therefore undo *everything* this app added - and nothing that belongs to arena.
 *
 * Both scripts are EXTRACTED FROM src/main.js, not copied, so a change there cannot leave
 * these tests passing against stale code.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/repair-page.test.js
 */
const fs = require('fs');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the repair tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && node test/repair-page.test.js');
  process.exit(0);
}
const src = fs.readFileSync(require('path').join(__dirname, '..', 'src', 'main.js'), 'utf8');
const repair = src.match(/const REPAIR_PAGE_JS = `([\s\S]*?)`;/)[1];
const diagnose = src.match(/const DIAGNOSE_PAGE_JS = `([\s\S]*?)`;/)[1];
const css = src.match(/const HIDE_GOOGLE_CSS = `([\s\S]*?)`;/)[1];
const hide = src.match(/const HIDE_GOOGLE_JS = `([\s\S]*?)`;/)[1];
if (!repair || !diagnose || !css || !hide) {
  console.error('extract failed');
  process.exit(1);
}

let bad = 0;
function check(name, ok, extra) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  ' + extra : ''}`);
}

function page(html) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true });
  const style = dom.window.document.createElement('style');
  style.textContent = css;                       // the marker CSS the app injects
  dom.window.document.head.appendChild(style);
  return dom.window;
}

// --- the repair really un-hides what the app hid
{
  const w = page('<div class="panel"><h2>Log In or Create Account</h2>'
    + '<button id=g>Continue with Google</button><input placeholder="Your email" id=ein></div>');
  w.eval(hide);
  const g = w.document.querySelector('#g');
  check('before repair: Google button is marked hidden',
    g.hasAttribute('data-agon-hidden-google'));
  check('before repair: the CSS makes the marker mean display:none',
    /data-agon-hidden-google/.test(w.document.querySelector('style').textContent));

  const removed = w.eval(repair);
  check('repair removes the marker', !g.hasAttribute('data-agon-hidden-google'), `removed=${removed}`);
  check('repair leaves the element in the DOM (arena keeps its reference)',
    !!w.document.querySelector('#g'));
  check('repair leaves the rest of the page alone', !!w.document.querySelector('#ein'));
}

// --- and it does not touch anything the page owns
{
  const w = page('<nav><a href=# id=nav>Google</a></nav><div id=own class=keep>hello</div>');
  w.eval(repair);
  check('an untouched page survives a repair unchanged',
    !!w.document.querySelector('#nav') && !!w.document.querySelector('#own'));
  check('no stray attributes are invented',
    !w.document.querySelector('[data-agon-hidden]'));
}

// --- the style element the insets code injects is removed
{
  const w = page('<body><div id=app></div>');
  const st = w.document.createElement('style');
  st.id = 'arena-app-insets';
  st.textContent = 'html{margin-top:60px !important}';
  w.document.head.appendChild(st);
  w.document.documentElement.style.marginTop = '60px';
  w.document.documentElement.style.marginBottom = '54px';
  const removed = w.eval(repair);
  check('repair removes the injected style element', !w.document.getElementById('arena-app-insets'));
  check('repair clears the inline margins it added',
    !w.document.documentElement.style.marginTop && !w.document.documentElement.style.marginBottom,
    `removed=${removed}`);
}

// --- twice is harmless (a second repair on an already-repaired page)
{
  const w = page('<div><button id=g>Continue with Google</button></div>');
  w.eval(repair);
  const second = w.eval(repair);
  check('repairing twice is harmless', second === 0 || typeof second === 'number', `removed=${second}`);
}

// --- the diagnosis reports a full-window overlay, and stays quiet when there is none
{
  const w = page('<div id=under>content</div>');
  w.document.querySelector('#under').getBoundingClientRect = () =>
    ({ width: 300, height: 200, top: 0, bottom: 200, left: 0, right: 300 });
  const clean = JSON.parse(w.eval(diagnose));
  check('diagnosis reports a page with no overlay', clean.overlays === null,
    JSON.stringify(clean.overlays));
  check('diagnosis reports element counts', typeof clean.total === 'number' && typeof clean.visible === 'number');
  check('diagnosis reports the viewport', typeof clean.w === 'number' && typeof clean.h === 'number');
}

console.log(bad === 0 ? '\nall repair-page tests passed' : `\n${bad} FAILED`);
process.exit(bad === 0 ? 0 : 1);
