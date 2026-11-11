// Picture-in-picture for the WHOLE APP: leaving the app at the home screen leaves it running
// in a small floating window.
//
// "Pip mode meant like for the whole app not videos. Remove video thing." The first version
// only entered when a <video> was playing, and shaped the page around that video. Both are
// gone: any page can be the floating window, and nothing in the app looks for a video,
// touches its styles, or offers video buttons.
//
// Run: node test/pip.test.js

const fs = require('fs');
const path = require('path');

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
const DRAWABLE_DIR = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'res',
  'drawable');

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

// --- 1. there is no video feature left ----------------------------------------------------
{
  const videoWords = /<video|videoPlaying|videoAction|currentTime|\.pause\(\)|play\(\)|RemoteAction|pip_rewind|pip_forward|pip_play_pause/;
  check('the floating window has nothing to do with video',
    !videoWords.test(PIP), (PIP.match(videoWords) || [])[0]);
  check('and nothing else in the app still asks a page about a video',
    !/videoPlayingJs|pipCssJs|pipPage\(/.test(MAIN));
  check('the video buttons and their strings are gone',
    !/<string name="pip_/.test(STRINGS) &&
    !fs.readdirSync(DRAWABLE_DIR).some((f) => /^ic_pip_/.test(f)),
    fs.readdirSync(DRAWABLE_DIR).filter((f) => /^ic_pip_/.test(f)).join(' '));
  check('the switch says what it now does, in plain words',
    /Keep the app in a floating window/.test(STRINGS) && !/playing video/i.test(STRINGS),
    (STRINGS.match(/settings_pip"[^>]*>([^<]*)/) || [])[1]);
}

// --- 2. it is offered where it works, and can be switched off -----------------------------
{
  check('the manifest allows picture-in-picture',
    /android:supportsPictureInPicture="true"/.test(MANIFEST));
  check('the switch exists, is on by default and is a user setting',
    /K_PIP_APP = "pip_video"/.test(SETTINGS) && /K_PIP_APP, true\)/.test(SETTINGS) &&
    /Settings\.K_PIP_APP/.test(SHEET));
  check('a phone that cannot do it is never shown the switch',
    /if \(Pip\.available\(activity\)\)/.test(SHEET) && /FEATURE_PICTURE_IN_PICTURE/.test(PIP));
  check('the key still matches the one the video version wrote, so an old "off" stays off',
    /K_PIP_APP = "pip_video"/.test(SETTINGS));
}

// --- 3. leaving the app is the trigger ----------------------------------------------------
{
  const leave = method(MAIN, 'protected void onUserLeaveHint()');
  check('leaving puts the app in its floating window', /pip\.enter\(\)/.test(leave));
  check('the switch is read at that moment', /Settings\.pipApp\(this\)/.test(leave));
  check('a rotation is not "leaving"', /isChangingConfigurations\(\)/.test(leave));

  const enter = method(PIP, 'public void enter()');
  check('an off switch means no window', /!Settings\.pipApp\(activity\)\) return;/.test(enter));
  check('Android 12+ is left to enter by itself, so the animation is real',
    /MIN_FOR_AUTO\) return;/.test(enter));
  check('a phone that refuses it does not take the app down',
    /catch \(RuntimeException ignored\)/.test(enter));

  const sync = method(PIP, 'public void sync()');
  check('enter-on-home is set only where the version has it',
    /MIN_FOR_AUTO[\s\S]{0,120}setAutoEnterEnabled\(Settings\.pipApp\(activity\)\)/.test(sync));
  check('the parameters are kept current on every return to the app',
    /pip\.sync\(\)/.test(method(MAIN, 'protected void onResume()')));
  const apply = method(MAIN, 'private void applySetting(String key, boolean value)');
  check('and when the switch is flipped',
    /case Settings\.K_PIP_APP:[\s\S]{0,400}?pip\.sync\(\)/.test(apply),
    (apply.match(/case Settings\.K_PIP_APP:[\s\S]{0,160}/) || [''])[0]);
  check('and after a rotation, because the window is the app\'s own shape',
    /pip\.sync\(\)/.test(method(MAIN,
      'public void onConfigurationChanged(Configuration newConfig)')));
}

// --- 4. what the floating window shows ----------------------------------------------------
{
  const changed = method(MAIN,
    'public void onPictureInPictureModeChanged(boolean inPip, Configuration config)');
  check('the app\'s own rows are hidden in the window and come back on return',
    /pipChromeVisible\(!inPip\)/.test(changed));
  const chrome = method(MAIN, 'private void pipChromeVisible(boolean visible)');
  check('and come back only if their own switches say so',
    /Settings\.showTabStrip\(this\)/.test(chrome) && /Settings\.showNav\(this\)/.test(chrome));
  check('the page is not reshaped: the window is the whole app',
    !/evaluateJavascript/.test(changed) && !/evaluateJavascript/.test(PIP));
}

// --- 5. the window is the app's own shape, within Android's limits ------------------------
{
  const shape = method(PIP, 'private Rational shape()');
  check('the shape comes from the window itself', /getDecorView\(\)\.getWidth\(\)/.test(shape));
  check('it is clamped to the ratios Android accepts',
    /MIN_RATIO/.test(shape) && /MAX_RATIO/.test(shape) &&
    /1d \/ 2\.39d/.test(PIP) && /2\.39d/.test(PIP));
  check('an unmeasured window does not divide by zero',
    /if \(w <= 0 \|\| h <= 0\) return new Rational\(9, 16\)/.test(shape));
}

// The switch's key is the one an earlier build wrote, so nobody who turned the video version
// off gets a floating app they had said no to.
{
  check('the old key and the new one are the same string',
    (SETTINGS.match(/K_PIP_APP = "([^"]+)"/) || [])[1] === 'pip_video');
}

console.log(`\n${bad === 0 ? 'all pip tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
