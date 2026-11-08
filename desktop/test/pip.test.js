// Picture-in-picture: a playing video keeps playing in a floating window when the app is
// left, and only then.
//
// "i want a pip option like YouTube - if playing a vid and pressed [home] button or gone
// directly to home screen then a window come or the video playing, so I want that in this
// app (can be toggle from settings too)".
//
// The scripts that decide it (is a video playing? shape the page for the window? nudge the
// video from the floating window's buttons?) are the whole risk here, so they are extracted
// from the Java literal and run in jsdom against real <video> elements.
//
// Run: node test/pip.test.js

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena',
  'webapp');
const read = (f) => fs.readFileSync(path.join(NAV, f), 'utf8');
const PIP = read('Pip.java');
const MAIN = read('MainActivity.java');
const SETTINGS = read('Settings.java');
const SHEET = read('SettingsSheet.java');
const MANIFEST = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
  'AndroidManifest.xml'), 'utf8');
const STRINGS = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main',
  'res', 'values', 'strings.xml'), 'utf8');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `  ${detail}`));
}

/** One Java method body, by its signature line. */
function method(src, signature) {
  const at = src.indexOf(signature);
  if (at < 0) return '';
  let i = src.indexOf('{', at), depth = 0;
  for (let j = i; j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}') {
      depth--;
      if (depth === 0) return src.slice(i, j + 1);
    }
  }
  return src.slice(i);
}

/**
 * One Java string literal, unescaped - the JS a branch of a method really builds.
 *
 * `which` picks the branch: a method that returns two different scripts must not have them
 * glued together, which is a mistake this file made once and the restore test caught.
 */
function literal(fnSignature, which) {
  const body = method(PIP, fnSignature);
  // A branch starts at its `return`/`body =`, and its literals are every quoted string
  // from there to the next `;`.
  if (which === undefined) {
    const all = body.match(/"(?:[^"\\]|\\.)*"/g) || [];
    return all.map((l) => JSON.parse(l)).join('');
  }
  const starts = [];
  const re = /(?:\breturn|\bbody\s*=)\s*"/g;
  let m;
  while ((m = re.exec(body))) starts.push(m.index + m[0].lastIndexOf('"'));
  // From that literal on, take the literals that are concatenated to it: a statement ends
  // where the next thing after a literal is no longer a "+".
  const all = [];
  const lit = /"(?:[^"\\]|\\.)*"/g;
  lit.lastIndex = starts[which];
  let first = lit.exec(body);
  if (!first) return '';
  all.push(first[0]);
  let end = first.index + first[0].length;
  for (;;) {
    const gap = body.slice(end).match(/^\s*\+\s*/);
    if (!gap) break;
    lit.lastIndex = end + gap[0].length;
    const next = lit.exec(body);
    if (!next || next.index !== end + gap[0].length) break;
    all.push(next[0]);
    end = next.index + next[0].length;
  }
  return all.map((l) => JSON.parse(l)).join('');
}

/** A method that builds its script from many literals (a concatenation). */
function script(fnSignature) {
  return literal(fnSignature);
}

// --- 1. it is only offered where it can work, and it can be switched off ----------------
{
  check('the manifest allows picture-in-picture',
    /android:supportsPictureInPicture="true"/.test(MANIFEST));
  check('the switch exists and is on by default',
    /K_PIP_VIDEO = "pip_video"/.test(SETTINGS) && /K_PIP_VIDEO, true\)/.test(SETTINGS));
  check('the switch is in Settings, not behind the developer unlock',
    /Settings\.K_PIP_VIDEO/.test(method(SHEET, 'private void buildRows()')) ||
    /Settings\.K_PIP_VIDEO/.test(method(SHEET, 'private void buildRows(){')));
  check('it is hidden on phones that cannot do it',
    /if \(Pip\.available\(activity\)\)/.test(SHEET));
  check('and says what it does in plain words',
    /name="settings_pip"/.test(STRINGS) && /name="settings_pip_sub"/.test(STRINGS));
}

// --- 2. leaving the app is the trigger, and nothing else -------------------------------
{
  const leave = method(MAIN, 'protected void onUserLeaveHint()');
  check('leaving asks for the floating window', /pip\.enterIfPlaying\(pipPage\(\)\)/.test(leave));
  check('the switch is read at that moment', /Settings\.pipVideo\(this\)/.test(leave));
  check('a rotation is not "leaving"', /isChangingConfigurations\(\)/.test(leave));
  const enter = method(PIP, 'public void enterIfPlaying(final WebView page)');
  check('no video, no window: the page is asked first',
    /videoPlayingJs\(\)/.test(enter) && /"true"\.equals\(answer\)/.test(enter));
  check('the page is shaped only after the answer is yes',
    enter.indexOf('shape(page)') > enter.indexOf('"true".equals(answer)'));
}

// --- 3. the app\'s own rows stay out of the floating window ------------------------------
{
  const changed = method(MAIN, 'public void onPictureInPictureModeChanged(boolean inPip, Configuration config)');
  check('the strip and nav are hidden while the window is up',
    /pipChromeVisible\(!inPip\)/.test(changed));
  const chrome = method(MAIN, 'private void pipChromeVisible(boolean visible)');
  check('and come back only if their own switches say so',
    /Settings\.showTabStrip\(this\)/.test(chrome) && /Settings\.showNav\(this\)/.test(chrome));
  check('the page is put back exactly as it was on the way out',
    /pip\.onPipChanged\(inPip, pipPage\(\)\)/.test(changed) &&
    /pipCssJs\(false\)/.test(method(PIP, 'private void restore(WebView page)')));
}

// --- 4. the scripts are real JavaScript, and they work ------------------------------------
const PLAYING = script('static String videoPlayingJs()');
check('the "is a video playing?" script parses', (() => {
  try { new Function(PLAYING); return true; } catch (e) { return false; }
})(), PLAYING.slice(0, 120));

{
  // A playing video: the answer is yes.
  const dom = new JSDOM('<!doctype html><body><video id=v></video></body>',
    { runScripts: 'outside-only' });
  const w = dom.window;
  const v = w.document.getElementById('v');
  let paused = false;
  Object.defineProperty(v, 'paused', { get: () => paused });
  Object.defineProperty(v, 'ended', { value: false });
  Object.defineProperty(v, 'readyState', { value: 3 });
  v.getBoundingClientRect = () => ({ width: 640, height: 360, left: 0, top: 0 });
  check('a playing video is seen', w.eval(PLAYING) === true);

  paused = true;
  check('a paused video is not', w.eval(PLAYING) === false);

  paused = false;
  v.getBoundingClientRect = () => ({ width: 40, height: 20, left: 0, top: 0 });
  check('a thumbnail-sized video is not "what the user is watching"',
    w.eval(PLAYING) === false);
}

{
  // Shaping, then putting it back: the video fills the viewport and the page returns to the
  // exact styles it had, which is what makes returning to the app invisible.
  const dom = new JSDOM('<!doctype html><body><div id=wrap style="padding:20px">'
    + '<video id=v style="width:70%;border-radius:8px"></video></div></body>',
  { runScripts: 'outside-only' });
  const w = dom.window;
  const v = w.document.getElementById('v');
  Object.defineProperty(v, 'paused', { get: () => false });
  Object.defineProperty(v, 'ended', { value: false });
  Object.defineProperty(v, 'readyState', { value: 3 });
  v.getBoundingClientRect = () => ({ width: 640, height: 360, left: 0, top: 0 });

  // Branch 0 is "put it back", branch 1 is "shape it": both are read from the source, so a
  // change to either is a change to what this test runs.
  const OFF = literal('static String pipCssJs(boolean on)', 0);
  const ON = literal('static String pipCssJs(boolean on)', 1);
  check('the shaping script parses', (() => {
    try { new Function(ON); new Function(OFF); return true; } catch (e) { return false; }
  })(), ON.slice(0, 120));
  const before = v.getAttribute('style');
  w.eval(ON);
  check('the video fills the window', /position:\s*fixed/.test(v.getAttribute('style')));
  check('the page behind it is hidden, not removed', w.document.body.style.visibility === 'hidden');
  w.eval(OFF);
  check('and the page comes back exactly as it was', v.getAttribute('style') === before,
    v.getAttribute('style'));
  check('the wrapper too', w.document.getElementById('wrap').getAttribute('style')
    === 'padding:20px');
}

{
  // The floating window's buttons drive the page's own video element.
  const dom = new JSDOM('<!doctype html><body><video id=v></video></body>',
    { runScripts: 'outside-only' });
  const w = dom.window;
  const v = w.document.getElementById('v');
  let played = 0, paused = 0, wasPaused = true;
  Object.defineProperty(v, 'paused', { get: () => wasPaused });
  Object.defineProperty(v, 'duration', { value: 600 });
  v.currentTime = 100;
  v.play = () => { played++; wasPaused = false; return Promise.resolve(); };
  v.pause = () => { paused++; wasPaused = true; };

  const REWIND = script('static String videoActionJs(String action)'); // both branches below
  const forward = (PIP.match(/ACTION_FORWARD\.equals\(action\)\) \{\s*body = "([^"]*)"/) || [])[1];
  const back = (PIP.match(/ACTION_REWIND\.equals\(action\)\) \{\s*body = "([^"]*)"/) || [])[1];
  const toggle = (PIP.match(/body = "(if\(v\.paused\)[^"]*)"/) || [])[1];
  check('the three button actions exist', !!forward && !!back && !!toggle,
    `${!!forward} ${!!back} ${!!toggle}`);
  const run = (body) => w.eval('(function(){try{var a=document.querySelectorAll("video"),i,v=null;'
    + 'for(i=0;i<a.length;i++){v=a[i];break;}if(!v)return;' + body + '}catch(e){}})();');
  run(back);
  check('ten seconds back', v.currentTime === 90, String(v.currentTime));
  run(forward);
  check('ten seconds forward', v.currentTime === 100, String(v.currentTime));
  run(toggle);
  check('the play button plays a paused video', played === 1 && wasPaused === false);
  run(toggle);
  check('and pauses a playing one', paused === 1);
  check('the action script is valid JavaScript', (() => {
    try { new Function('action', REWIND); return true; } catch (e) { return false; }
  })());
}

// --- 5. old phones are told the truth -----------------------------------------------------
{
  check('Android 8 is the floor, and it is named', /MIN_FOR_PIP = Build\.VERSION_CODES\.O/.test(PIP));
  check('the phone must advertise the feature, not just the version',
    /FEATURE_PICTURE_IN_PICTURE/.test(PIP));
  check('and a phone that refuses PiP does not take the app down',
    /catch \(RuntimeException ignored\)[\s\S]{0,200}(video simply stops|must not take)/.test(PIP));
}

console.log(`\n${bad === 0 ? 'all pip tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
