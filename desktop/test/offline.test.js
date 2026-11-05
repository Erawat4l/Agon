/**
 * Tests for saved pages and the "it only loads after a refresh" fix.
 *
 * Two device reports drive this:
 *   "add also every page of arena.ai is saved inside the app" - pages must open instantly
 *   and still open when there is no usable connection.
 *   "if you dont have fast internet the log-in icon comes only after refresh, sometimes
 *   the app doesnt load" - a page that failed must be fetched again by itself, and the
 *   error screen must not be the end of the story.
 *
 * The honest model, which these tests pin down:
 *   - online: LOAD_DEFAULT (the network wins; a cached document must never look frozen)
 *   - offline: LOAD_CACHE_ELSE_NETWORK + the last good copy of each tab, saved with
 *     saveWebArchive() and replayed from file:// when a load fails
 *   - file:// never reaches the chrome: the arrows, the strip and the retry code ask
 *     realUrl(), so a restored copy still navigates to the real page
 *
 * There is no JDK or Android SDK in the CI sandbox, so this reads the Java instead of
 * running it: the constants, the branches and the call sites are extracted from the
 * source, so the Java cannot drift away from the test.
 *
 *   cd desktop && node test/offline.test.js
 */
const fs = require('fs');
const path = require('path');

const NAV = 'ai/arena/webapp/';
const JAVA = (name) => path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', NAV + name);

const OFFLINE = fs.readFileSync(JAVA('OfflinePages.java'), 'utf8');
const TABS = fs.readFileSync(JAVA('TabWebViews.java'), 'utf8');
const MAIN = fs.readFileSync(JAVA('MainActivity.java'), 'utf8');
const SETTINGS = fs.readFileSync(JAVA('Settings.java'), 'utf8');

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `\n      ${detail}`));
}

/** The body of one method, from its signature to the closing brace at its indent. */
function method(src, signature) {
  const start = src.indexOf(signature);
  if (start < 0) return '';
  let i = src.indexOf('{', start);
  let depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  return '';
}

/** A `private static final long X = ...;` value, with its arithmetic folded. */
function constant(src, name) {
  const m = src.match(new RegExp('static final (?:long|int) ' + name + '\\s*=\\s*([^;]+);'));
  if (!m) return null;
  const expr = m[1].replace(/L/g, '').replace(/_/g, '');
  // eslint-disable-next-line no-new-func
  return Function('"use strict";return (' + expr + ');')();
}

// --- the cache mode is chosen per connection, never hardcoded --------------------------
{
  check('online the network wins (LOAD_DEFAULT)',
    /online \? WebSettings\.LOAD_DEFAULT\s*:\s*WebSettings\.LOAD_CACHE_ELSE_NETWORK/
      .test(OFFLINE));
  check('online and offline are both reachable from one helper',
    method(OFFLINE, 'public static void applyCacheMode(WebView web, boolean online)')
      .includes('setCacheMode'));
  check('a convenience overload asks the device instead of guessing',
    /public static void applyCacheMode\(WebView web\)/.test(OFFLINE)
      && /applyCacheMode\(web, online\(web\.getContext\(\)\)\)/.test(OFFLINE));

  // The old bug: the WebView settings pinned the cache mode once, for ever.
  const settingsBlock = TABS.slice(TABS.indexOf('s.setCacheMode'), TABS.indexOf('s.setCacheMode') + 120);
  check('the WebView settings still do not pin a cache mode',
    /s\.setCacheMode\(WebSettings\.LOAD_DEFAULT\)/.test(settingsBlock),
    settingsBlock);

  // ...and the chosen mode is re-applied whenever a page finishes or a tab is created.
  const pageFinished = method(TABS, 'public void onPageFinished(WebView view, String url)');
  check('every finished page re-chooses the cache mode',
    /OfflinePages\.applyCacheMode\(view\)/.test(pageFinished));
  check('the save is queued, not run inside onPageFinished',
    /postDelayed\(\(\) -> OfflinePages\.save\(saving, saveUrl\)/.test(pageFinished));
}

// --- snapshots: where they live, how many, how big, how often --------------------------
{
  check('snapshots live in the app private cache dir',
    /new File\(context\.getCacheDir\(\), "pages"\)/.test(OFFLINE));
  check('one file per URL, named by a hash (no path traversal, no query in the name)',
    /MessageDigest\.getInstance\("SHA-256"\)/.test(OFFLINE)
      && /new StringBuilder\("p-"\)[\s\S]*?\.mht"/.test(OFFLINE));
  check('the archive is written to a temp file and renamed into place',
    /\.tmp"/.test(OFFLINE) && /tmp\.renameTo\(target\)/.test(OFFLINE));
  check('the archive is only kept if the save actually produced it',
    /if \(value == null\) return;/.test(OFFLINE));

  const keep = constant(OFFLINE, 'KEEP');
  const cap = constant(OFFLINE, 'MAX_SNAPSHOT_BYTES');
  const throttle = constant(OFFLINE, 'MIN_INTERVAL_MS');
  check('the number of kept pages is bounded', keep !== null && keep > 0 && keep <= 16, keep);
  check('an oversized page is not stored', cap !== null && cap > 0 && cap <= 64 * 1024 * 1024, cap);
  check('saving is throttled per URL', throttle !== null && throttle >= 60 * 1000, throttle);
  check('pruning runs after a successful save', /prune\(context\);/.test(OFFLINE));
  check('the oldest files are the ones dropped',
    /Long\.compare\(a\.lastModified\(\), b\.lastModified\(\)\)/.test(OFFLINE));

  // Only arena.ai pages are saved; a signed-in provider page or a download must never be.
  check('only arena.ai URLs are saved', /MainActivity\.isArenaUrl\(url\)/.test(OFFLINE));
  check('a saved file is never saved back onto itself',
    /"about:blank"\.equals\(url\)/.test(OFFLINE));
}

// --- restore: offline start and load failure both fall back to the saved copy ----------
{
  check('a saved copy is replayed from file://',
    /web\.loadUrl\("file:\/\/" \+ f\.getAbsolutePath\(\)\)/.test(OFFLINE));
  // Every tab runs with file access OFF, so a replay has to open the door for one load and
  // close it again - otherwise the saved page cannot be read at all.
  check('a replay opens file access',
    /setAllowFileAccess\(true\)/.test(method(OFFLINE, 'public static boolean restore(')));
  check('the door is shut again when the copy has loaded',
    /setAllowFileAccess\(false\)/.test(method(OFFLINE, 'public static void lockFileAccess(')));
  const finished = method(TABS, 'public void onPageFinished(WebView view, String url)');
  check('a finished saved copy locks file access again',
    /OfflinePages\.lockFileAccess\(view\)/.test(finished));
  check('the WebView is still created with file access off',
    /s\.setAllowFileAccess\(false\)/.test(TABS));
  check('restore says whether it could', /public static boolean restore\(/.test(OFFLINE));
  check('an empty or missing file is not a restore',
    /f == null \|\| !f\.isFile\(\) \|\| f\.length\(\) == 0\) return false;/.test(OFFLINE));

  const ensure = method(TABS, 'private WebView ensure(int tab)');
  check('a tab created with no connection restores its copy first',
    /OfflinePages\.hasSnapshot\(activity, startUrl\)/.test(ensure)
      && /OfflinePages\.restore\(w, startUrl\)/.test(ensure));
  check('the restored tab remembers the real URL',
    /restoredUrl\.put\(tab, startUrl\)/.test(ensure));

  // Both onReceivedError overloads: API 23+ takes the WebResourceRequest one.
  const failure = method(TABS, 'public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,');
  check('a main-frame failure restores the copy instead of the error screen',
    /isForMainFrame\(\)/.test(failure) && /OfflinePages\.restore\(view, failed\)/.test(failure));
  const legacy = TABS.slice(TABS.indexOf('public void onReceivedError(WebView view, int errorCode'),
    TABS.indexOf('public void onReceivedError(WebView view, int errorCode') + 1200);
  check('the deprecated callback restores too', /OfflinePages\.restore\(view, failingUrl\)/.test(legacy));
  check('the failure path is logged, so a report can show what happened',
    /CrashLog\.note\(activity, "load failed, showing the saved copy of /.test(TABS));
}

// --- file:// never reaches the chrome --------------------------------------------------
{
  const realUrl = method(TABS, 'public String realUrl(int tab)');
  check('realUrl ignores a file:// URL and remembers the arena one',
    realUrl.includes('startsWith("file://")') && /restoredUrl\.get\(tab\)/.test(realUrl));

  // Every place that reloads the current tab has to go through realUrl(), or the app
  // "reloads" a local file and the page can never come back.
  const reloadOfCurrent = MAIN.split('\n').filter((line) =>
    /loadInCurrent\(/.test(line) && !/void loadInCurrent/.test(line));
  check('nothing reloads the current tab from a raw template URL',
    !reloadOfCurrent.some((line) => /TabWebViews\.urlFor\(/.test(line)),
    reloadOfCurrent.join(' | '));
  check('every retry path (error, resume, connectivity) uses realUrl',
    (MAIN.match(/loadInCurrent\(tabs\.realUrl\(currentTab\)\)/g) || []).length >= 3);
  check('the stalled bar sends a restored tab back to the live page',
    /stuck\.loadUrl\(tabs\.realUrl\(tab\)\)/.test(MAIN));

  // The tab strip and the title follow the visible URL, which must not be a saved copy.
  check('the title/name of a restored tab still comes from the page, not the file',
    /realUrl\(/.test(TABS));
}

// --- privacy: saved pages die with the session ----------------------------------------
{
  const signOut = method(MAIN, 'void onSignOut()');
  check('signing out deletes every saved page', /OfflinePages\.clear\(/.test(signOut));
  const clearData = method(MAIN, 'private void doClearData()').length
    ? method(MAIN, 'private void doClearData()')
    : method(MAIN, 'void onClearData()');
  check('clearing data deletes every saved page', /OfflinePages\.clear\(/.test(clearData));
  check('the switch that turns saved pages off is a real setting',
    /K_OFFLINE_PAGES/.test(SETTINGS) && /offlinePages\(Context c\)/.test(SETTINGS));
  check('turning the switch off deletes the copies',
    /case Settings\.K_OFFLINE_PAGES:/.test(MAIN) && /OfflinePages\.clear\(this\)/.test(MAIN));
  check('saving and restoring both honour the switch',
    (OFFLINE.match(/Settings\.offlinePages\(/g) || []).length >= 3);
  check('a sign-out reload never reloads a file that was just deleted',
    /restoredUrl\.clear\(\)/.test(method(TABS, 'public void clearAndReload()'))
      && /loadInCurrent\(realUrl\(currentTab\)\)/.test(method(TABS, 'public void clearAndReload()')));
  check('saved pages are never written outside the cache dir',
    !/getExternal/.test(OFFLINE) && !/Environment\./.test(OFFLINE));
}

// --- the connection is watched, and the callback is given back ------------------------
{
  const watch = method(MAIN, 'private void watchConnectivity()');
  check('the app registers a network callback', /registerNetworkCallback\(/.test(watch));
  check('a network that can reach the internet is what it waits for',
    /NET_CAPABILITY_INTERNET/.test(watch));
  check('the callback hops to the UI thread',
    /runOnUiThread\(\(\) -> onConnectivityGained\(\)\)/.test(watch));
  check('a refused registration cannot break the app',
    /catch \(Exception e\)/.test(watch) && /connectivity = null;/.test(watch));

  const destroy = method(MAIN, 'protected void onDestroy()');
  check('the callback is unregistered with the activity', /unregisterNetworkCallback/.test(destroy));

  const gained = method(MAIN, 'private void onConnectivityGained()');
  check('a returning connection reloads a page that never loaded',
    /hideError\(\)/.test(gained) && /loadInCurrent\(tabs\.realUrl\(currentTab\)\)/.test(gained));
  check('a page fine as it is must not be reloaded just because wifi came back',
    /stalledBar/.test(gained));
}

console.log(`\n${bad === 0 ? 'all offline tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
