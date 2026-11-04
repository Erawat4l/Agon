/**
 * Tests for the rule that hides arena's own sidebar inside the app's page.
 *
 * Three device builds shipped with the panel still on screen, so this rule has to be
 * proven against the two layouts that matter: signed out (the panel says "Sign in") and
 * signed in (it shows the account's email address instead). It also has to refuse the
 * page's own shell, a landing page, and any panel that holds the composer - hiding one of
 * those is the "page looks mashed / nothing works" failure.
 *
 * The script is EXTRACTED FROM TabWebViews.java, so editing the Java cannot leave these
 * tests passing against stale code. Both injection sites are covered: the rule has to be
 * in the branch that reserves space for the app's bars AND in the branch that does not,
 * and the app currently takes the second one.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/sidebar.test.js
 */
const fs = require('fs');
const path = require('path');
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the sidebar tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && ' +
    'node test/sidebar.test.js');
  process.exit(0);
}

const JAVA = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java',
  'ai', 'arena', 'webapp', 'TabWebViews.java');
const SRC = fs.readFileSync(JAVA, 'utf8');

/** Pulls one `private static final String NAME = ... ;` constant out of the Java source. */
function constant(name) {
  const start = SRC.indexOf('private static final String ' + name + ' =');
  if (start < 0) throw new Error(name + ' not found');
  const end = SRC.indexOf(';\n', start);
  let js = '';
  for (const m of SRC.slice(start, end).matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  return js.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

// Addresses in these fixtures are example.com/.org on purpose: a real consumer address in
// a tracked file is a personal-data leak the pre-flight refuses to publish, and it caught
// exactly that the first time this file was built.
const SIDEBAR = constant('JS_SIDEBAR');
const HEADER = constant('JS_HEADER');

/**
 * Builds the same helper block applyChromeInsets() injects, then the rules. jsdom has no
 * layout, so a test chooses which elements "have size" by tagging them with a class the
 * stubbed getBoundingClientRect() reads.
 */
const HELPERS = `
  var undo=[];
  function hide(el){undo.push(el);el.style.setProperty('display','none','important');}
  function shown(el){var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}
  function empty(el){return !el.querySelector('main,textarea,form,[contenteditable="true"]');}
  function text(el){return ((el.innerText||'')+' '+
    (el.getAttribute&&el.getAttribute('aria-label')||'')).toLowerCase();}
`;

function run(html, { size = {} } = {}) {
  const dom = new JSDOM('<!doctype html><html><body>' + html + '</body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  // jsdom implements textContent but not innerText, which every real WebView has. The rule
  // reads innerText (it skips hidden text), so the stand-in is what makes the rule testable.
  Object.defineProperty(w.HTMLElement.prototype, 'innerText', {
    get() { return this.textContent; },
    configurable: true,
  });
  // Layout stand-in: an element is visible unless it, or an ancestor, is marked .zero.
  w.Element.prototype.getBoundingClientRect = function () {
    let el = this, hidden = false;
    for (; el; el = el.parentElement) {
      if (el.classList && el.classList.contains('zero')) hidden = true;
    }
    const box = size[this.id] || { w: 260, h: 600 };
    return hidden
      ? { width: 0, height: 0, top: 0, bottom: 0, left: 0, right: 0 }
      : { width: box.w, height: box.h, top: box.top || 0, bottom: box.top || 0,
          left: 0, right: box.w };
  };
  w.eval('(function(){try{' + HELPERS + SIDEBAR + '}catch(e){}})();');
  return w.document;
}

const hidden = doc => {
  const out = [];
  doc.querySelectorAll('[data-test]').forEach(el => {
    if (el.style.getPropertyValue('display') === 'none') out.push(el.dataset.test);
  });
  return out;
};

let bad = 0;
function check(name, html, wantHidden, opts) {
  const doc = run(html, opts);
  const got = hidden(doc).sort();
  const want = wantHidden.slice().sort();
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  hidden=${JSON.stringify(got)}` +
    (ok ? '' : `  want=${JSON.stringify(want)}`));
}

// --- the rule has to be reachable at all -----------------------------------------------
{
  const injections = SRC.split('(hideSidebar ? JS_SIDEBAR : "")').length - 1;
  const ok = injections >= 2;
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  sidebar JS is injected in both branches ` +
    `(${injections} site(s))`);
}

// --- signed in: the account row is an email, not the words "sign in" --------------------
check('signed-in panel with an account email is hidden',
  `<div id=sb data-test="sidebar">
     <a>New chat</a><a>Leaderboard</a><a>Search</a>
     <button>someone@example.com</button>
   </div>
   <main data-test="main"><textarea></textarea></main>`,
  ['sidebar']);

// --- the exact device failure this rule was rewritten for -------------------------------
check('the old four-word rule could never match a signed-in phone',
  `<div id=sb data-test="sidebar">
     <a>New chat</a><a>Leaderboard</a><a>Search</a><button>other.person@example.org</button>
   </div>`,
  ['sidebar']);

// --- signed out ------------------------------------------------------------------------
check('signed-out panel says "Sign in" and is hidden',
  `<div id=sb data-test="sidebar">
     <a>New chat</a><a>Leaderboard</a><a>Search</a><a>Sign in</a>
   </div>`,
  ['sidebar']);

// --- must never match ------------------------------------------------------------------
check('the page shell holding the composer is never hidden',
  `<div data-test="shell"><a>New chat</a><a>Leaderboard</a><a>Search</a>
     <a>me@x.com</a><textarea></textarea></div>`,
  []);

check('a landing page with no account row is left alone',
  `<div data-test="landing"><a>New chat</a><a>Leaderboard</a><a>Search</a>
     <a>Docs</a></div>`,
  []);

check('a wide hero band is not a sidebar',
  `<div id=hero data-test="hero"><a>New chat</a><a>Leaderboard</a><a>Search</a>
     <a>me@x.com</a></div>`,
  [], { size: { hero: { w: 900, h: 300 } } });

// --- the phone case that broke it: the panel is a full-width slide-over ---------------
check('a full-width drawer on a phone is hidden',
  `<div id=sb data-test="sidebar">
     <a>New chat</a><a>Leaderboard</a><a>Search</a><button>someone@example.com</button>
   </div>`,
  ['sidebar'], { size: { sb: { w: 392, h: 780 } } });

check('a narrow phone drawer with one nav word behind an overflow control is hidden',
  `<div id=sb data-test="sidebar">
     <a>New chat</a><a>Search</a><button>More</button><button>someone@example.com</button>
   </div>`,
  ['sidebar'], { size: { sb: { w: 300, h: 700 } } });

check('a full-width drawer that holds the composer is never hidden',
  `<div data-test="drawer">
     <a>New chat</a><a>Search</a><button>someone@example.com</button>
     <textarea></textarea></div>`,
  [], { size: { drawer: { w: 392, h: 780 } } });

// One nav word plus an account row is a promo card or a settings panel, not the sidebar.
check('a single nav word with an account row is not a sidebar',
  `<div data-test="thin"><a>New chat</a><button>someone@example.com</button></div>`,
  []);

check('a hidden panel stays hidden and nothing else is touched',
  `<div class=zero data-test="gone"><a>New chat</a><a>Leaderboard</a>
     <a>Search</a><a>me@x.com</a></div>
   <div data-test="content">the conversation</div>`,
  []);

// --- the header rule still behaves ------------------------------------------------------
{
  const dom = new JSDOM('<!doctype html><html><body>' +
    '<header id=h data-test="header"><img src=x><a>arena</a></header>' +
    '<div data-test="body">hello</div></body></html>',
    { runScripts: 'outside-only', url: 'https://arena.ai/' });
  const w = dom.window;
  w.Element.prototype.getBoundingClientRect = function () {
    if (this.id === 'h') {
      return { width: 1000, height: 56, top: 0, bottom: 56, left: 0, right: 1000 };
    }
    return { width: 1000, height: 600, top: 60, bottom: 660, left: 0, right: 1000 };
  };
  Object.defineProperty(w, 'innerWidth', { value: 1000 });
  w.eval('(function(){try{' + HELPERS + HEADER + '}catch(e){}})();');
  const got = w.document.getElementById('h').style.getPropertyValue('display');
  const ok = got === 'none';
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  the page's own top header is still hidden`);
}

const total = 14;
console.log(`\n${total - bad}/${total} PASS`);
process.exit(bad === 0 ? 0 : 1);
