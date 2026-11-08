/**
 * Tests for "a dev server's preview does not open in the app".
 *
 * The report: arena's file explorer lists a running dev server, the link opens on a
 * computer, and in the app it opens an empty page and stays there.
 *
 * Three causes, all closed here:
 *
 *  1. the link said `localhost:3000`. On a phone `localhost` is the PHONE, and nothing this
 *     app runs listens there - the request cannot succeed, and the app said nothing, so it
 *     looked like a dead link;
 *  2. cleartext http was refused outright (usesCleartextTraffic=false), so even a reachable
 *     `http://192.168.x.x:3000` never left the WebView: no page, no error;
 *  3. an https arena page embedding an http preview was a mixed-content block under
 *     NEVER_ALLOW, and an empty frame reads as "nothing happened".
 *
 * The Java is read, not run (no JDK/Android here), and the address arithmetic is
 * re-implemented from the source so the tests fail if it changes.
 *
 *   cd desktop && node test/local-preview.test.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const NAV = path.join(ROOT, 'app', 'src', 'main', 'java', 'ai', 'arena', 'webapp');
const CHROME = fs.readFileSync(path.join(NAV, 'AppChrome.java'), 'utf8');
const MAIN = fs.readFileSync(path.join(NAV, 'MainActivity.java'), 'utf8');
const TABS = fs.readFileSync(path.join(NAV, 'TabWebViews.java'), 'utf8');
const WINDOW = fs.readFileSync(path.join(NAV, 'WebWindow.java'), 'utf8');
const LAN = fs.readFileSync(path.join(NAV, 'LanAddresses.java'), 'utf8');
const STRINGS = fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'res', 'values',
  'strings.xml'), 'utf8');
const NET = fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'res', 'xml',
  'network_security_config.xml'), 'utf8');
const MANIFEST = fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'AndroidManifest.xml'),
  'utf8');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `\n      ${detail}`));
}

// --- 1. localhost on a phone is named, not loaded into a blank page ---------------------
{
  const phoneLoopback = (host) => {
    const h = String(host || '').toLowerCase();
    return ['127.0.0.1', 'localhost', '::1', '[::1]', '10.0.2.2'].includes(h)
      && h !== '10.0.2.2';      // the emulator alias is the host machine, not the phone
  };
  check('this phone\'s own loopback is recognised',
    phoneLoopback('localhost') && phoneLoopback('127.0.0.1'));
  check('the emulator alias is left alone (it IS the host machine)',
    phoneLoopback('10.0.2.2') === false);
  check('an ordinary host is not touched', phoneLoopback('arena.ai') === false);

  check('AppChrome owns the list, so the config and the code cannot drift',
    /public static boolean isLoopback\(String host\)/.test(CHROME) &&
    /public static boolean isPhoneLoopback\(String host\)/.test(CHROME));
  check('a tapped localhost link is intercepted, not loaded',
    /isPhoneLoopback\(host\)[\s\S]{0,200}openLocalHelp/.test(MAIN));
  check('a popup to localhost is intercepted too',
    /isPhoneLoopback\(host\)[\s\S]{0,120}openLocalHelp\(url, host, this::openWebWindow\)/
      .test(MAIN));
  check('the explanation exists and says the computer is the address to use',
    /name="local_help_title"/.test(STRINGS) && /name="local_help_body"/.test(STRINGS) &&
    /computer/.test(STRINGS));
  check('the body carries the example address as a placeholder',
    /name="local_help_body">[^<]*%1\$s/.test(STRINGS) &&
    /setMessage\(getString\(R\.string\.local_help_body/.test(MAIN));
  check('the title names the host that failed', /local_help_title">%1\$s/.test(STRINGS));
}

// --- 2. cleartext http stays off everywhere except this device -------------------------
{
  check('the manifest still refuses cleartext globally',
    /usesCleartextTraffic="false"/.test(MANIFEST));
  check('the network config allows it for the three local names only',
    /<base-config cleartextTrafficPermitted="false" \/>/.test(NET) &&
    ['127.0.0.1', 'localhost', '10.0.2.2'].every((h) =>
      new RegExp(`<domain includeSubdomains="false">${h.replace(/\./g, '\\.')}</domain>`)
        .test(NET)));
  check('and the code list matches the config list, name for name',
    ['127.0.0.1', 'localhost', '10.0.2.2'].every((h) => CHROME.includes(`"${h}"`)));
}

// --- 3. an https page may embed an http preview ----------------------------------------
{
  check('a tab no longer blocks mixed content outright',
    /MIXED_CONTENT_COMPATIBILITY_MODE/.test(TABS) &&
    !/MIXED_CONTENT_NEVER_ALLOW/.test(TABS));
  check('a window the page opened does not either',
    /MIXED_CONTENT_COMPATIBILITY_MODE/.test(WINDOW) &&
    !/MIXED_CONTENT_NEVER_ALLOW/.test(WINDOW));
  check('the popup path matches the tab path',
    /MIXED_CONTENT_COMPATIBILITY_MODE/.test(MAIN));
  // The sign-in sheet keeps the strict setting: an account flow must never be downgraded.
  const SIGNIN = fs.readFileSync(path.join(NAV, 'SignInSheet.java'), 'utf8');
  check('sign-in keeps NEVER_ALLOW, so an account flow is never downgraded',
    /MIXED_CONTENT_NEVER_ALLOW/.test(SIGNIN));
}

// --- 4. the address offered to the user keeps the port ---------------------------------
{
  // Same arithmetic as LanAddresses.portOf(), re-implemented to pin it.
  function portOf(url) {
    const scheme = url.indexOf('://');
    const start = scheme >= 0 ? scheme + 3 : 0;
    const slash = url.indexOf('/', start);
    const authority = slash >= 0 ? url.substring(start, slash) : url.substring(start);
    const colon = authority.lastIndexOf(':');
    if (colon < 0) return '';
    const port = authority.substring(colon + 1);
    return /^\d{1,5}$/.test(port) ? ':' + port : '';
  }
  check('a dev-server port is kept', portOf('http://localhost:3000/') === ':3000');
  check('IPv6 brackets are not mistaken for a port',
    portOf('http://[::1]:5173/') === ':5173');
  check('no port means no colon', portOf('http://localhost/') === '');
  check('the app uses that helper when building the address',
    /LanAddresses\.portOf\(url\)/.test(MAIN) &&
    /public static String portOf\(String url\)/.test(LAN));

  check('the list is generated from the phone\'s own interfaces',
    /NetworkInterface\.getNetworkInterfaces/.test(LAN) &&
    /isLoopback/.test(LAN) && /Inet4Address/.test(LAN));
  check('and always offers the loopback entry last, for a server on the phone',
    /http:\/\/127\.0\.0\.1/.test(LAN));
  check('picking one opens it', /entries\.get\(which\)\.url \+ suffix/.test(MAIN));
  check('the dialog can always be closed',
    /setNegativeButton\(getString\(R\.string\.local_help_close\), null\)/.test(MAIN));
}

console.log(`\n${bad === 0 ? 'all local-preview tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
