'use strict';

/**
 * Agon for Windows - an unofficial desktop client for arena.ai.
 *
 * Deliberately thin: one BrowserWindow that shows arena.ai in a persistent session, with
 * the same rules the Android app follows. The security model is the part that matters
 * here, so it is stated once and enforced below:
 *
 *   - no Node in the page (contextIsolation on, nodeIntegration off, sandbox on),
 *   - the page can never navigate the window off arena.ai; a foreign link is either shown
 *     in a child window (same session, so a provider sign-in still lands) or refused,
 *   - the window may never open itself without a gesture, and never with node access,
 *   - downloads are named by the page, so the name is flattened to one path segment,
 *   - updates come from this repository's GitHub releases and are verified by
 *     electron-updater against the published SHA-512 of the installer.
 */

const { app, BrowserWindow, Menu, dialog, ipcMain, session, shell } = require('electron');
const path = require('path');
const fs = require('fs');

const START_URL = 'https://arena.ai/';
const REPO_OWNER = 'Erawat4l';
const REPO_NAME = 'Agon';
const ARENA_HOSTS = ['arena.ai', 'lmarena.ai'];
/** Auth surfaces that have to stay inside the app, or the cookie lands somewhere else. */
const PROVIDER_HOSTS = ['accounts.google.com', 'accounts.youtube.com', 'google.com'];

const PARTITION = 'persist:agon';
const isDev = !app.isPackaged;

/**
 * Portable build vs installed build.
 *
 * electron-builder's "portable" target unpacks the app next to wherever the .exe was
 * started from and sets PORTABLE_EXECUTABLE_DIR to that folder. Left alone, Chromium still
 * writes its session (cookies, cache, window size) to %APPDATA%\Agon, which is exactly what
 * a portable build is supposed to avoid - and which breaks when the stick is moved to a PC
 * where the account has no write access to %APPDATA%.
 *
 * So: when PORTABLE_EXECUTABLE_DIR is set, keep everything in Agon-data\ next to the exe.
 * If that is not writable (read-only USB stick, Program Files), fall back to %APPDATA%
 * instead of refusing to start.
 */
function userDataDir() {
  const portableDir = process.env.PORTABLE_EXECUTABLE_DIR;
  if (!portableDir) return null;              // installed build: Electron's default
  const candidate = path.join(portableDir, 'Agon-data');
  try {
    fs.mkdirSync(candidate, { recursive: true });
    fs.accessSync(candidate, fs.constants.W_OK);
    return candidate;
  } catch (notWritable) {
    return null;                              // fall back to %APPDATA%, still runs
  }
}

let mainWindow = null;
/** Windows this app opened itself (provider popups), so they can be closed together. */
const childWindows = new Set();

function isArenaHost(host) {
  if (!host) return false;
  const lower = host.toLowerCase();
  return ARENA_HOSTS.some((base) => lower === base || lower.endsWith('.' + base));
}

function isProviderHost(host) {
  if (!host) return false;
  const lower = host.toLowerCase();
  return PROVIDER_HOSTS.some((base) => lower === base || lower.endsWith('.' + base));
}

function urlInfo(raw) {
  try {
    const url = new URL(raw);
    return { url, scheme: url.protocol.replace(':', ''), host: url.hostname };
  } catch (notAUrl) {
    return null;
  }
}

/**
 * A Chromium user agent with the app's and Electron's tokens removed.
 *
 * The token list is not guesswork: Electron composes the default UA from the `name` field
 * of package.json, which is "agon-desktop", so the real string is
 *   ... (KHTML, like Gecko) agon-desktop/1.4.22 Chrome/116... Electron/26.6.10 Safari/537.36
 * The old cleaner only stripped "Agon/" and then "Electron/", so `agon-desktop/1.4.22`
 * survived and every request to arena.ai and Google carried "this is an app" in it. That
 * is exactly what an embedded-browser refusal is keyed on. Both real tokens are removed,
 * and any future name (=<word>/<digits>) is caught by the last pattern.
 *
 * The Chrome version is left alone: a page that sniffs the browser still learns the truth
 * about the engine, and inventing a newer one would only create a mismatch to be caught by.
 */
function userAgent(ses) {
  const ua = ses.getUserAgent();
  return ua
    .replace(/\s(?:agon-desktop|agon|ArenaApp)\/[\d.]+/gi, '')
    .replace(/\sElectron\/[\d.]+/gi, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

/** One path segment: a page-supplied download name must not escape the Downloads folder. */
function safeFileName(name, fallback) {
  if (!name) return fallback;
  const flat = String(name).replace(/[\\/\u0000-\u001f\u007f]/g, '').replace(/^\.+/, '').trim();
  const clipped = flat.length > 120 ? flat.slice(flat.length - 120) : flat;
  return clipped || fallback;
}

// ---------------------------------------------------------------- window

function windowStatePath() {
  return path.join(app.getPath('userData'), 'window-state.json');
}

function readWindowState() {
  try {
    const raw = JSON.parse(fs.readFileSync(windowStatePath(), 'utf8'));
    if (raw && Number.isFinite(raw.width) && Number.isFinite(raw.height)) return raw;
  } catch (ignored) {
    // first run, or a half-written file: the defaults below are fine
  }
  return { width: 1280, height: 900 };
}

function writeWindowState() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const bounds = mainWindow.getNormalBounds();
  try {
    fs.writeFileSync(windowStatePath(), JSON.stringify({
      x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height,
      maximized: mainWindow.isMaximized(),
    }));
  } catch (ignored) {
    // a read-only profile directory must not stop the app from closing
  }
}

/**
 * Session-wide policy, registered exactly once.
 *
 * It used to live in hardenWebContents(), which runs for the main window *and* every
 * provider popup - three windows meant three will-download handlers on the same session,
 * each renaming the same file. A session is a singleton, so its handlers belong here.
 */
let sessionHardened = false;

function hardenSession(ses) {
  if (sessionHardened) return;
  sessionHardened = true;

  // Downloads: the page chooses the name, the app chooses the folder.
  ses.on('will-download', (event, item) => {
    const name = safeFileName(item.getFilename(), 'arena-download');
    item.setSavePath(path.join(app.getPath('downloads'), name));
  });

  // Permissions: an unofficial client asks for nothing it does not need. A page cannot
  // silently take the camera, microphone, location, clipboard-read or notifications.
  const allowed = new Set(['clipboard-sanitized-write', 'fullscreen']);
  ses.setPermissionRequestHandler((webContents, permission, callback) => {
    callback(allowed.has(permission));
  });
  ses.setPermissionCheckHandler((webContents, permission) => allowed.has(permission));

  // Nothing but https: a downgraded page would be able to read what it is embedded in.
  ses.webRequest.onBeforeRequest({ urls: ['http://*/*'] }, (details, callback) => {
    callback({ cancel: true });
  });

  // setUserAgent() rewrites User-Agent but NOT the User-Agent Client Hints that newer
  // Google pages read, so those still announced Electron:
  //   sec-ch-ua: "Not)A;Brand";v="24", "Chromium";v="116", "Electron";v="26"
  // Dropping the Electron entry with a plain regex leaves a dangling comma (or a leading
  // one, when Electron comes first), which is a malformed header - worse than the original
  // because it becomes a fingerprint of its own. Split the list instead, filter, rejoin.
  const withoutElectron = (value) => {
    if (!value) return value;
    const brands = value.split(',').map((part) => part.trim()).filter(Boolean);
    const kept = brands.filter((part) => !/^"Electron"\s*;/i.test(part));
    return kept.length === brands.length ? value : kept.join(', ');
  };
  // Registered here, not in createWindow(): this runs once per session, and createWindow
  // runs again whenever the window is recreated (all windows closed, then reactivated).
  ses.webRequest.onBeforeSendHeaders((details, callback) => {
    const headers = details.requestHeaders;
    for (const key of Object.keys(headers)) {
      if (/^sec-ch-ua$/i.test(key)) headers[key] = withoutElectron(headers[key]);
    }
    callback({ requestHeaders: headers });
  });
}

function hardenWebContents(contents) {
  // No <webview> tag: it is a second, less supervised renderer.
  contents.on('will-attach-webview', (event) => event.preventDefault());
}

/**
 * Navigation rules for one webContents.
 *
 * `role` decides what is allowed:
 *   'app'     the arena.ai window - may navigate inside arena.ai, nothing else
 *   'popup'   a small arena.ai popup (opened by the page) - same rules as the app
 *   'provider' a Google sign-in window - free to roam Google's own pages and to return to
 *             arena.ai when the redirect carries the session; it may never wander off to
 *             some unrelated third-party site
 *
 * The previous version applied the app's rules to every window, which meant a Google popup
 * that navigated to a second Google page was treated as hostile: it was redirected to
 * arena.ai, loading the whole app *inside* the sign-in window.
 */
function attachNavigationPolicy(contents, role) {
  const kind = role || 'app';

  const allowedHere = (info) => {
    if (!info || info.scheme !== 'https') return false;
    // A provider window must be able to come BACK. Google sign-in ends by redirecting to
    // arena.ai with the session, and the cookie is set on that final arena.ai page - so
    // allowing only provider hosts here cancelled the one navigation that actually signs
    // the app in. Any other outside host is still refused.
    if (kind === 'provider') return isProviderHost(info.host) || isArenaHost(info.host);
    return isArenaHost(info.host);
  };

  contents.on('will-navigate', (event, rawUrl) => {
    const info = urlInfo(rawUrl);
    if (allowedHere(info)) return;

    // In the app window, a provider page must not replace the app: hand it to its own
    // window so arena stays loaded behind it.
    if (kind !== 'provider' && info && info.scheme === 'https' && isProviderHost(info.host)) {
      event.preventDefault();
      openProviderWindow(info.url.toString());
      return;
    }
    event.preventDefault();
  });

  // window.open / target=_blank. A provider popup gets a child window in the SAME session
  // (so the cookie it sets is the one arena.ai then sees); everything else is refused.
  contents.setWindowOpenHandler(({ url }) => {
    const info = urlInfo(url);
    if (!info || info.scheme !== 'https') return { action: 'deny' };
    const provider = isProviderHost(info.host);
    const arena = isArenaHost(info.host);
    if (!provider && !arena) return { action: 'deny' };
    if (kind === 'provider' && !provider) return { action: 'deny' };
    return {
      action: 'allow',
      overrideBrowserWindowOptions: {
        // Google's sign-in page is a full page, not a 520px card: at popup size its form is
        // cramped and its own layout collapses. A provider popup gets a real window size;
        // an arena.ai popup keeps the small one.
        width: provider ? 720 : 520,
        height: provider ? 780 : 700,
        parent: mainWindow && !mainWindow.isDestroyed() ? mainWindow : undefined,
        modal: false,
        autoHideMenuBar: true,
        backgroundColor: provider ? '#ffffff' : '#191919',
        title: provider ? 'Sign in' : 'Agon',
        icon: path.join(__dirname, '..', 'build', 'icon.png'),
        webPreferences: {
          partition: PARTITION,
          contextIsolation: true,
          nodeIntegration: false,
          sandbox: true,
          webviewTag: false,
          spellcheck: false,
        },
      },
    };
  });
}

/**
 * Makes the window behave like an application, not a web page.
 *
 * The screen recording showed two things that should never happen in a client: dragging
 * across the page selected every heading, and the selection's right-click menu offered
 * "Print" and "Search the web" - Chromium's page menu. Typing fields keep their normal
 * behaviour, because those are the one place selection is wanted.
 *
 * Everything is scoped to arena's own pages; a provider popup (Google) is left alone so its
 * own form still works.
 */
const APP_CSS = `
  html, body, div, span, p, h1, h2, h3, h4, h5, h6, li, a, button, label, article, section {
    -webkit-user-select: none !important;
    user-select: none !important;
    -webkit-user-drag: none !important;
  }
  input, textarea, [contenteditable="true"], [contenteditable=""] {
    -webkit-user-select: text !important;
    user-select: text !important;
  }
  /* The blank white flash between page loads: the app's own background shows instead. */
  html { background: #191919; }
`;

/** Windows whose context-menu handler is already installed. */
const chromeHooked = new WeakSet();

function applyAppChrome(contents, rawUrl) {
  const info = rawUrl ? urlInfo(rawUrl) : null;
  if (info && !isArenaHost(info.host)) return;      // leave provider pages untouched
  // insertCSS() only affects the document that is already loaded, so it has to run again
  // after every navigation. The listener below is different: it belongs to the window and
  // would otherwise be added once per page load, piling up handlers that all fire forever.
  contents.insertCSS(APP_CSS).catch(() => {});
  applyGoogleSignInRule(contents);
  if (chromeHooked.has(contents)) return;
  chromeHooked.add(contents);
  // Chromium's page menu (Print, Search the web, Copy image) is replaced by nothing at all:
  // an app window has no business offering to print the site. Inputs still get the native
  // edit menu, which Chromium provides for editable fields without this handler firing.
  contents.on('context-menu', (event, params) => {
    if (params.isEditable) return;                  // keep cut/copy/paste in fields
    event.preventDefault();
  });
}

/**
 * Hides arena's "Continue with Google" button inside arena's own sign-in panel.
 *
 * Why: Google does not allow its sign-in inside an embedded browser, so on this client the
 * button cannot succeed. Leaving it on screen offers a route that is guaranteed to fail,
 * and the refusal it produces (a Google page, or arena's own "please sign in with Google")
 * reads like the app is broken.
 *
 * How, and why it is shaped this way:
 *   - Only elements *inside the sign-in panel* are touched. The panel is found by its own
 *     heading ("Log In or Create Account"), then the Google control is matched by its text.
 *     Nothing is hidden from the rest of the page, so nav or footer Google links stay.
 *   - The control is hidden with CSS on that element, not removed from the DOM: arena's
 *     own code keeps a working reference, and nothing throws on the next re-render.
 *   - If the panel is not found, or no matching control is found, nothing happens. The
 *     worst case of arena changing its markup is therefore "the button is still there",
 *     never "something unrelated disappeared".
 *   - A MutationObserver re-applies it, because arena re-renders the panel.
 *
 * It is a guess about someone else's markup, which is why it is a switch in the menu.
 */
const HIDE_GOOGLE_CSS = `
  [data-agon-hidden-google="1"] {
    display: none !important;
  }
`;

const HIDE_GOOGLE_JS = `(function () {
  if (window.__agonHideGoogle) return;
  window.__agonHideGoogle = true;

  // The panel is identified by BOTH its heading and an email field inside it. That pair is
  // what makes this safe: walking up from any stray "Google" link can no longer reach an
  // element that merely mentions the heading somewhere, because the email field has to be
  // in there too.
  var HEADINGS = ['log in or create account', 'sign in or create account',
                  'log in', 'sign in', 'create account'];
  var GOOGLE_WORDS = /continue with google|sign in with google|log in with google/i;

  function hasEmailField(el) {
    return !!el.querySelector('input[type="email"], input[name*="email" i],'
      + ' input[placeholder*="email" i], input[autocomplete="email"]');
  }

  function findPanel() {
    var els = document.querySelectorAll('div, section, form, dialog, [role="dialog"]');
    var best = null;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (!hasEmailField(el)) continue;
      var heading = el.querySelector('h1, h2, h3, h4, legend, [role="heading"]');
      var text = ((heading && heading.textContent) || '').toLowerCase().trim();
      if (!text) continue;
      if (!HEADINGS.some(function (h) { return text.indexOf(h) >= 0; })) continue;
      // The innermost match wins: the email field must not belong to a larger container.
      if (!best || best.contains(el)) best = el;
    }
    return best;
  }

  function isGoogleControl(el) {
    var label = (el.getAttribute('aria-label') || '') + ' ' + (el.textContent || '');
    return GOOGLE_WORDS.test(label) && label.trim().length < 80;
  }

  /**
   * The "OR" between Google and the email field.
   *
   * Only a divider, not a container: a button, a paragraph, or an element whose own text is
   * the word AND whose children carry no text. The last part matters because the wrapper
   * that holds the email field may itself *start* with "OR"; hiding that would take the
   * email input with it. A bare "OR" like that never has text inside its children.
   */
  function isOrDivider(el) {
    var own = (el.textContent || '').trim();
    if (!/^(or|—|-+)$/i.test(own)) return false;
    if (own.length > 3) return false;
    var tag = el.tagName.toLowerCase();
    var role = (el.getAttribute('role') || '').toLowerCase();
    var structural = tag === 'button' || tag === 'a' || role === 'button' || role === 'link';
    if (structural) return true;
    for (var i = 0; i < el.children.length; i++) {
      if ((el.children[i].textContent || '').trim()) return false;
    }
    return true;
  }

  function hideGoogle() {
    var panel = findPanel();
    if (!panel) return false;
    var hit = false;

    // 1. the Google control itself, and 2. the divider that separated it from the email
    // field - with the button gone, a lone "OR" reads as a broken layout.
    var candidates = panel.querySelectorAll(
      'button, a, [role="button"], [role="link"], p, span, div');
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (el.hasAttribute('data-agon-hidden-google')) continue;
      if (!isGoogleControl(el) && !isOrDivider(el)) continue;
      // Never something that contains the email field: hiding that would hide the form.
      if (hasEmailField(el)) continue;
      el.setAttribute('data-agon-hidden-google', '1');
      hit = true;
    }
    return hit;
  }

  hideGoogle();
  var observer = new MutationObserver(function () { hideGoogle(); });
  observer.observe(document.documentElement, { childList: true, subtree: true });
}());`;

/**
 * Applies (or undoes) the "no Google button" rule for the document that is now loaded.
 *
 * Turning the setting OFF must really bring the button back, so the markers are removed
 * rather than merely not added - otherwise a user who switches it off sees no change until
 * the next full navigation, which looks like the switch does nothing.
 */
function applyGoogleSignInRule(contents) {
  const wanted = readSettings().hideGoogleSignIn;
  contents.insertCSS(HIDE_GOOGLE_CSS).catch(() => {});
  if (wanted) {
    contents.executeJavaScript(HIDE_GOOGLE_JS, true).catch(() => {});
    return;
  }
  contents.executeJavaScript(
    "(function(){"
    + " document.querySelectorAll('[data-agon-hidden-google]')"
    + "   .forEach(function (el) { el.removeAttribute('data-agon-hidden-google'); });"
    + "}());", true).catch(() => {});
}

/** Opens a provider sign-in page in its own small window, in the app's session. */
function openProviderWindow(url) {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const child = new BrowserWindow({
    width: 720,
    height: 780,
    parent: mainWindow,
    modal: false,
    autoHideMenuBar: true,
    backgroundColor: '#ffffff',
    title: 'Sign in',
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    webPreferences: {
      partition: PARTITION,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webviewTag: false,
      spellcheck: false,
    },
  });
  // Present as a normal Chrome. The session UA is already stripped of the Electron token
  // (see userAgent()), and a page opened without it would put the token back.
  child.webContents.setUserAgent(userAgent(session.fromPartition(PARTITION)));
  childWindows.add(child);
  child.on('closed', () => childWindows.delete(child));
  hardenWebContents(child.webContents);
  attachNavigationPolicy(child.webContents, 'provider');
  // Google refuses "this browser may not be secure" often enough that the failure has to be
  // explained where it happens, not in a menu dialog the user cannot see any more.
  let warned = false;
  child.webContents.on('did-finish-load', async () => {
    const url = child.webContents.getURL();
    if (warned || !/accounts\.google\.com/.test(url)) return;
    // Only Google's specific refusal counts. The old test also matched "not secure", which
    // appears on an ordinary Google password form ("Not secure" / help text), so a page that
    // was perfectly usable was reported as refused and closed - and the email route was
    // recommended for a wall that was not there.
    const blocked = await child.webContents.executeJavaScript(`(function(){
      var t = (document.body && document.body.innerText || '').toLowerCase();
      return t.indexOf('browser or app may not be secure') >= 0
          || t.indexOf('couldn.t sign you in') >= 0
          || t.indexOf('secure browsers policy') >= 0
          || t.indexOf('this browser is not secure') >= 0;
    })()`, true).catch(() => false);
    if (!blocked) return;
    warned = true;
    if (!child.isDestroyed()) child.close();
    const answer = await dialog.showMessageBox(mainWindow, {
      type: 'warning',
      title: 'Google refused this window',
      message: 'Google does not allow its sign-in inside an embedded browser.',
      detail: 'That is Google\'s policy for apps like this, and nothing in Agon can override '
        + 'it.\n\nUse the email route instead - it is the one that works. The next window '
        + 'walks you through it.\n\nSigning in with Google in your normal browser does not '
        + 'help: that signs the browser in, not Agon.',
      buttons: ['Use the email link', 'Cancel'],
      defaultId: 0,
      cancelId: 1,
    });
    if (answer.response === 0) promptForSignInLink();
  });
  // The redirect that carries the session lands on arena.ai inside THIS window, while the
  // app window behind it still shows the signed-out page it loaded minutes ago. Without
  // this, a successful sign-in looked like nothing happened: the popup showed arena, the
  // app still showed "Log In". The cookie jar is shared, so reloading is enough.
  let handedOff = false;
  child.webContents.on('did-navigate-in-page', () => handOff());
  child.webContents.on('did-navigate', () => handOff());
  child.webContents.on('did-finish-load', () => handOff());
  function handOff() {
    if (handedOff || child.isDestroyed()) return;
    const info = urlInfo(child.webContents.getURL());
    if (!info || !isArenaHost(info.host)) return;
    handedOff = true;
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.loadURL(START_URL);
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
    // Close the helper window: its job was to carry the cookie back, and leaving a second
    // arena.ai window open invites the user to keep working in the wrong one.
    if (!child.isDestroyed()) child.close();
  }
  child.loadURL(url);
}

function createWindow() {
  const state = readWindowState();
  mainWindow = new BrowserWindow({
    x: state.x,
    y: state.y,
    width: state.width,
    height: state.height,
    // Arena's composer row plus its upward-opening model menu need this much height; a
    // smaller window clips the menu's top options. 420x480 allowed a window where the
    // page technically rendered but could not be operated.
    minWidth: 760,
    minHeight: 640,
    title: 'Agon',
    backgroundColor: '#191919',
    autoHideMenuBar: false,
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    webPreferences: {
      partition: PARTITION,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webviewTag: false,
      spellcheck: true,
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  if (state.maximized) mainWindow.maximize();

  const ses = session.fromPartition(PARTITION);
  ses.setUserAgent(userAgent(ses));
  hardenSession(ses);

  hardenWebContents(mainWindow.webContents);
  attachNavigationPolicy(mainWindow.webContents, 'app');

  mainWindow.webContents.on('did-finish-load', () => {
    applyAppChrome(mainWindow.webContents, mainWindow.webContents.getURL());
  });
  mainWindow.webContents.on('dom-ready', () => {
    applyAppChrome(mainWindow.webContents, mainWindow.webContents.getURL());
  });

  mainWindow.webContents.on('did-create-window', (child) => {
    childWindows.add(child);
    child.on('closed', () => childWindows.delete(child));
    hardenWebContents(child.webContents);
    // The child's URL is not set yet at this point (did-create-window fires before the page
    // loads), so decide the role from the first navigation instead of guessing from ''.
    let roleSet = false;
    const setRole = (rawUrl) => {
      if (roleSet) return;
      roleSet = true;
      const info = rawUrl ? urlInfo(rawUrl) : null;
      attachNavigationPolicy(child.webContents,
        info && isProviderHost(info.host) ? 'provider' : 'popup');
    };
    child.webContents.once('will-navigate', (event, rawUrl) => setRole(rawUrl));
    child.webContents.once('did-start-loading', () => setRole(child.webContents.getURL()));
    setRole(child.webContents.getURL());
  });

  mainWindow.on('close', writeWindowState);
  mainWindow.on('closed', () => {
    for (const child of childWindows) {
      if (!child.isDestroyed()) child.destroy();
    }
    childWindows.clear();
    mainWindow = null;
  });

  mainWindow.loadURL(START_URL);
  if (isDev && process.env.AGON_DEVTOOLS === '1') {
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  }
}

// ---------------------------------------------------------------- menu

/** Navigates the app window. Only ever to arena's own https pages. */
function go(rawUrl) {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const info = urlInfo(rawUrl);
  if (!info || info.scheme !== 'https' || !isArenaHost(info.host)) return;
  mainWindow.loadURL(info.url.toString());
}

/** Opens a URL in the user's own browser - used for links that are not the app's. */
function openExternally(rawUrl) {
  const info = urlInfo(rawUrl);
  if (!info || info.scheme !== 'https') return;
  if (!isArenaHost(info.host) && !['github.com', 'www.github.com'].includes(info.host)) return;
  shell.openExternal(info.url.toString());
}

/**
 * The menu's commands.
 *
 * Sign-in is the one place where this app cannot promise what the Android one does: it
 * has a real Chromium session, but Google may still refuse "this browser or app may not
 * be secure" for an OAuth page inside Electron. So the command says what is happening and
 * offers the labelled browser route, instead of silently failing on a Google page.
 */
function runCommand(command) {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  switch (command) {
    case 'new-chat':
    case 'chat':
      go(START_URL);
      break;
    case 'leaderboard':
      go('https://arena.ai/leaderboard');
      break;
    case 'release-page':
      openExternally(releasePageUrl());
      break;
    case 'devtools':
      mainWindow.webContents.toggleDevTools();
      break;
    case 'sign-in':
      // No dialog. The old one was a modal with a "Continue" button that only reloaded the
      // homepage and then STAYED ON TOP of the page, covering arena's login panel with a
      // grey scrim - so the sign-in screen could not be used at all. Choosing Sign in now
      // just puts you on arena's own login form, which is the only thing that can sign you
      // in. Arena has no password field to fill: it emails a link (see promptForSignInLink
      // for how that link ends up in this app's session).
      go(START_URL);
      break;
    case 'hide-google-on':
    case 'hide-google-off': {
      setSetting('hideGoogleSignIn', command === 'hide-google-on');
      // Re-apply to the page on screen, so the change is visible immediately instead of
      // after the next load.
      applyGoogleSignInRule(mainWindow.webContents);
      const nowHidden = readSettings().hideGoogleSignIn;
      dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: nowHidden ? 'Google button hidden' : 'Google button shown again',
        message: nowHidden
          ? 'The sign-in panel no longer offers "Continue with Google".'
          : '"Continue with Google" is visible again.',
        detail: nowHidden
          ? 'Google does not allow its sign-in inside an embedded browser, so the button '
            + 'could only ever fail. Use the email link: Agon menu -> "Use the sign-in link '
            + 'from my browser".\n\nIf a Google button still appears, arena changed its '
            + 'page; report it rather than relying on this switch.'
          : 'Reopen arena.ai with View -> Reload if the panel does not change immediately.',
        buttons: ['OK'],
      });
      break;
    }
    case 'sign-in-browser':
      // The label already says "Use the sign-in link", so ask for the link straight away.
      // The old dialog before it explained what to do and then asked again, which is one
      // click of pure delay for the person who already has the link - and it offered to
      // open arena.ai in a browser, which can never sign this app in.
      promptForSignInLink();
      break;
    default:
      break;
  }
}

/**
 * Signing in with the email magic link, the only route arena offers.
 *
 * The flow is: type an address on the page, arena emails a link, you click it. The link
 * opens in the DEFAULT BROWSER, and putting a link there is not something an app can
 * redirect - so the browser ends up with the session and Agon does not. That is the whole
 * "sign-in works on the phone but not on Windows" problem.
 *
 * What is possible, and what this does: the browser shows the sign-in confirmation page on
 * arena.ai. `Agon -> Use the sign-in link from my browser` asks for that page's address
 * (paste with Ctrl+V), reads that exact page inside Agon's own session, and relates any
 * cookies arena sets while doing so to the sign-in. Apps normally must not read another
 * app's session; this is the user's own link for the user's own account, handed over
 * deliberately, which is the case the rules exist for.
 *
 * Nothing here guesses a token shape. It loads the URL the user pasted and checks whether
 * arena now considers the session signed in; if it does not, it says so instead of
 * pretending it worked.
 */
async function useBrowserSignInLink(rawUrl) {
  let info;
  try {
    info = urlInfo(rawUrl);
  } catch (notAUrl) {
    info = null;
  }
  if (!info || info.scheme !== 'https' || !isArenaHost(info.host)) {
    throw new Error('That is not an arena.ai link. Copy the address of the page that opened '
      + 'after you clicked the link in your email.');
  }
  if (!mainWindow || mainWindow.isDestroyed()) throw new Error('The window is closed.');

  const ses = session.fromPartition(PARTITION);
  const before = new Set((await ses.cookies.get({ domain: 'arena.ai' })).map(cookieKey));
  await mainWindow.loadURL(info.url.toString());
  const after = await ses.cookies.get({ domain: 'arena.ai' });
  const fresh = after.filter((c) => !before.has(cookieKey(c)));
  if (fresh.length > 0) {
    await ses.cookies.flushStore();
  }
  const signedIn = await looksSignedIn(mainWindow.webContents);
  return { signedIn, cookies: fresh.map((c) => c.name) };
}

// ---------------------------------------------------------------- settings
//
// One JSON file in the profile folder, for choices that must survive a restart. Kept
// separate from window-state.json so a corrupt window position can never take a setting
// with it, and written with the same "a read-only profile must not break the app" care.
function settingsPath() {
  return path.join(app.getPath('userData'), 'settings.json');
}

const DEFAULT_SETTINGS = {
  // Hide arena's "Continue with Google" button on its sign-in panel. On by default because
  // Google refuses its own sign-in inside an embedded browser, so that button cannot work
  // here - it only looks like an option and then fails. Off is one menu click away, because
  // this is the app rewriting a site's page, and if arena changes its markup the guess can
  // hide the wrong thing.
  hideGoogleSignIn: true,
};

let cachedSettings = null;

function readSettings() {
  if (cachedSettings) return cachedSettings;
  cachedSettings = { ...DEFAULT_SETTINGS };
  try {
    const raw = JSON.parse(fs.readFileSync(settingsPath(), 'utf8'));
    if (raw && typeof raw === 'object') {
      for (const key of Object.keys(DEFAULT_SETTINGS)) {
        if (typeof raw[key] === 'boolean') cachedSettings[key] = raw[key];
      }
    }
  } catch (ignored) {
    // first run, or unreadable: the defaults are the intended behaviour
  }
  return cachedSettings;
}

function setSetting(key, value) {
  const settings = readSettings();
  settings[key] = value;
  try {
    fs.writeFileSync(settingsPath(), JSON.stringify(settings));
  } catch (ignored) {
    // a read-only profile keeps the in-memory value for this run only
  }
}

/**
 * Whether arena has refused the email route for this account, and its own wording if so.
 *
 * Reads the page's visible text rather than guessing at a response body: the refusal shows
 * up as a toast, not a status the shell can see. Returns the matched sentence, or null.
 */
async function emailRouteRefused(contents) {
  if (!contents || contents.isDestroyed()) return null;
  try {
    return await contents.executeJavaScript(`(function(){
      var t = (document.body && document.body.innerText || '');
      var m = /please sign in with google[^\n]*/i.exec(t);
      return m ? m[0].trim() : null;
    })()`, true);
  } catch (cannot) {
    return null;                       // never block sign-in on a failed read
  }
}

/** Identity of a cookie, so "set during this load" can be told apart from "already there". */
function cookieKey(c) {
  return [c.name, c.domain, c.path, c.hostOnly ? 'host' : 'domain'].join('|');
}

/**
 * Asks the page itself whether the session is signed in.
 *
 * Deliberately conservative: a positive answer needs either a Log in / Log out control or
 * a known session cookie. When neither is visible it reports "unknown" rather than claiming
 * success - a false "you are signed in" would be worse than an honest "cannot tell".
 */
async function looksSignedIn(contents) {
  try {
    return await contents.executeJavaScript(`(function(){
      try {
        var txt = (document.body && document.body.innerText || '').toLowerCase();
        var hasLogIn = /\\blog\\s*in\\b|sign\\s*in/.test(txt);
        var hasLogOut = /\\blog\\s*out\\b|sign\\s*out/.test(txt);
        if (hasLogOut && !hasLogIn) return true;
        if (hasLogIn && !hasLogOut) return false;
        return 'unknown';
      } catch (e) { return 'unknown'; }
    })()`, true);
  } catch (cannot) {
    return 'unknown';
  }
}

async function promptForSignInLink() {
  if (!mainWindow || mainWindow.isDestroyed()) return;

  // Arena refuses the email route for an account it has linked to Google, with its own
  // toast: "Please sign in with Google for this account". Asking for an email link in that
  // state would send the user to wait for mail that changes nothing, so say it here.
  const refusal = await emailRouteRefused(mainWindow.webContents);
  if (refusal) {
    await dialog.showMessageBox(mainWindow, {
      type: 'warning',
      title: 'This account needs Google',
      message: 'arena.ai says this account must sign in with Google.',
      detail: 'Its message: "' + refusal + '"\n\nAn emailed link cannot sign this account '
        + 'in, because arena has linked it to Google. That is arena\'s rule for the '
        + 'account, not something Agon can change.\n\nWhat can work:\n'
        + '  - sign in on your phone, where Google allows the app\'s browser\n'
        + '  - or use an arena account created with the email link (a different address '
        + 'makes a different account)\n\nNothing was changed.',
      buttons: ['OK'],
    });
    return;
  }
  // A tiny window instead of a text dialog: Electron has no built-in prompt, and the user
  // has to paste a long URL, which needs a real input and Ctrl+V.
  const win = new BrowserWindow({
    width: 640,
    height: 340,
    parent: mainWindow,
    modal: true,
    resizable: false,
    minimizable: false,
    maximizable: false,
    autoHideMenuBar: true,
    title: 'Use the sign-in link from your browser',
    backgroundColor: '#191919',
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  // The listener is registered BEFORE anything is loaded into the window. Registering it
  // afterwards left a gap: the page could report its answer while nothing was listening,
  // and then the dialog waited for a second answer that could never come.
  const answer = await new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      // Always unhooked: a once() listener that the user cancels out of would stay behind
      // and swallow the next attempt's value.
      ipcMain.removeListener('agon:paste-link', onPaste);
      resolve(value);
    };
    function onPaste(event, value) { finish(value); }
    ipcMain.on('agon:paste-link', onPaste);
    win.once('closed', () => finish(null));

    // The dialog's own button handler calls window.agon.pasteLink(). A data: URL has no
    // preload of its own, so expose exactly that one function, only for this window, and
    // only while it is open. It accepts a string and nothing else.
    win.webContents.on('page-title-updated', (event, title) => {
      if (title && title.startsWith('agon:paste:')) {
        event.preventDefault();
        ipcMain.emit('agon:paste-link', null, title.slice('agon:paste:'.length));
      }
    });
    win.webContents.once('did-finish-load', () => {
      win.webContents.executeJavaScript(
        "window.agon = { pasteLink: function (v) {"
        + " document.title = 'agon:paste:' + (typeof v === 'string' ? v : ''); } };", true);
    });
    win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(PASTE_HTML));
  });
  if (!win.isDestroyed()) win.destroy();
  if (!answer) return;

  let result;
  try {
    result = await useBrowserSignInLink(answer);
  } catch (error) {
    await dialog.showMessageBox(mainWindow, {
      type: 'warning', title: 'That did not work', message: 'The link could not be used.',
      detail: String(error.message || error), buttons: ['OK'],
    });
    return;
  }
  const ok = result.signedIn === true;
  await dialog.showMessageBox(mainWindow, {
    type: ok ? 'info' : 'warning',
    title: ok ? 'Signed in' : 'Still not signed in',
    message: ok ? 'Agon is signed in.' : 'That page did not sign this app in.',
    detail: ok
      ? 'The session is stored and will be kept on this PC.'
      : 'The link was opened inside Agon but the page still shows a Log in / Sign in'
        + ' control. Links expire quickly, and each one only works once - request a fresh'
        + ' one: type your email into "Your email", click "Continue with email", and use'
        + ' the link from that email straight away.'
        + (result.cookies.length ? '\n\nCookies set: ' + result.cookies.join(', ') : '\n\nNo new cookies were set.'),
    buttons: ['OK'],
  });
}

/** The paste window's markup. Kept here so it is obvious there is no remote content. */
const PASTE_HTML = `<!doctype html><html><head><meta charset="utf-8">
<style>
  body{font:14px/1.5 system-ui,Segoe UI,sans-serif;background:#191919;color:#e8e8e8;margin:0;padding:16px}
  h1{font-size:15px;margin:0 0 6px}
  p{margin:0 0 12px;color:#a8a8a8;font-size:12.5px}
  ol{margin:0 0 12px;padding-left:20px;color:#a8a8a8;font-size:12.5px}
  ol b{color:#e8e8e8;font-weight:600}
  li{margin-bottom:5px}
  input{width:100%;box-sizing:border-box;padding:9px 10px;border-radius:8px;border:1px solid #3a3a3a;
        background:#111;color:#e8e8e8;font:inherit}
  .row{display:flex;gap:8px;justify-content:flex-end;margin-top:14px}
  button{padding:7px 16px;border-radius:8px;border:1px solid #3a3a3a;background:#242424;
         color:#e8e8e8;font:inherit;cursor:pointer}
  button.primary{background:#c7f26a;border-color:#c7f26a;color:#111;font-weight:600}
</style></head><body>
<h1>Paste the sign-in link from your email</h1>
<ol>
  <li>In Agon, on arena's <b>Log In or Create Account</b> panel, type your address into
      <b>Your email</b>.</li>
  <li>Click <b>Continue with email</b>. (With the field empty it only says
      <i>Email is required</i> - that is not a failure.)</li>
  <li>Open the email arena sends. <b>Do not click the link in it</b> - it works once, and
      clicking uses it up in your mail app.</li>
  <li>Right-click that link and choose <b>Copy Link Address</b>, then paste it here.</li>
</ol>
<input id="u" autofocus placeholder="https://arena.ai/...">
<div class="row"><button id="c">Cancel</button><button class="primary" id="ok">Use this link</button></div>
<script>
  var u=document.getElementById('u');
  function send(v){ if(window.agon && window.agon.pasteLink) window.agon.pasteLink(v); }
  document.getElementById('ok').onclick=function(){send(u.value.trim());};
  document.getElementById('c').onclick=function(){send('');};
  u.addEventListener('keydown',function(e){if(e.key==='Enter')send(u.value.trim());});
  u.focus();
</script></body></html>`;

/** The releases page of the release the app is checking. */
function releasePageUrl() {
  return 'https://github.com/' + REPO_OWNER + '/' + REPO_NAME + '/releases/latest';
}

function buildMenu() {
  const template = [
    {
      label: 'Agon',
      submenu: [
        { label: 'New chat', accelerator: 'CmdOrCtrl+N', click: () => runCommand('new-chat') },
        { label: 'Chat', accelerator: 'CmdOrCtrl+1', click: () => runCommand('chat') },
        { label: 'Leaderboard', accelerator: 'CmdOrCtrl+2', click: () => runCommand('leaderboard') },
        { label: 'Sign in', click: () => runCommand('sign-in') },
        { label: 'Use the sign-in link from my browser', click: () => runCommand('sign-in-browser') },
        { type: 'separator' },
        { label: 'Check for updates…', click: () => checkForUpdates(true) },
        { label: 'Open the release page', click: () => runCommand('release-page') },
        { type: 'separator' },
        { role: 'quit' },
      ],
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload' },
        { role: 'forceReload' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
        { label: 'Toggle developer tools', accelerator: 'CmdOrCtrl+Shift+I',
          click: () => runCommand('devtools') },
        { type: 'separator' },
        {
          // A view preference about how arena's page is displayed, so it lives here rather
          // than with the sign-in commands: it is not part of signing in, it changes what
          // the page shows.
          label: 'Hide "Continue with Google" on the sign-in panel',
          type: 'checkbox',
          checked: readSettings().hideGoogleSignIn,
          click: (item) => runCommand(item.checked ? 'hide-google-on' : 'hide-google-off'),
        },
      ],
    },
    {
      label: 'Help',
      submenu: [
        {
          label: 'About Agon',
          click: () => {
            dialog.showMessageBox(mainWindow, {
              type: 'info',
              title: 'About Agon',
              message: 'Agon ' + app.getVersion(),
              detail: 'An unofficial desktop client for arena.ai.\n\n'
                + 'Not affiliated with, endorsed by, or supported by Arena / LMArena. '
                + 'The site is shown as-is and your account and session stay yours.\n\n'
                + 'Licensed under the Apache License 2.0 (see LICENSE).',
              buttons: ['OK'],
            });
          },
        },
        {
          label: 'Open the LICENSE',
          click: () => shell.openPath(path.join(__dirname, '..', '..', 'LICENSE')),
        },
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// ---------------------------------------------------------------- updates

let autoUpdater = null;

/**
 * Auto-update from this repository's own releases.
 *
 * Never installs silently: electron-updater downloads in the background and only asks
 * once the file is verified, then installs on the user's confirmation and relaunch.
 * The releases are public, so no token or account is involved, exactly like the Android app.
 */
function updates() {
  try {
    ({ autoUpdater } = require('electron-updater'));
  } catch (missing) {
    return null;                    // running from source: no updater, and that is fine
  }
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.logger = null;

  autoUpdater.on('error', (error) => {
    if (autoUpdater.__manualCheck && mainWindow && !mainWindow.isDestroyed()) {
      // Say what actually went wrong instead of dumping a few hundred bytes of HTTP
      // headers at the user. The old dialog pasted the whole 404 response, cookies
      // included, which was both unreadable and needlessly revealing.
      const raw = String(error && error.message ? error.message : error);
      const status = /status code (\d{3})/i.exec(raw) || /\b(404|403|401)\b/.exec(raw);
      let detail;
      if (status && status[1] === '404') {
        detail = 'GitHub has no release it can see (404).\n\n'
          + 'That means no release has been published yet, or the repository was renamed. '
          + 'Check https://github.com/Erawat4l/Agon/releases in a browser.';
      } else if (status) {
        detail = 'GitHub answered HTTP ' + status[1] + '.\n\n'
          + 'Check your internet connection and try again in a few minutes.';
      } else {
        detail = raw.length > 400 ? raw.slice(0, 400) + '...' : raw;
      }
      dialog.showMessageBox(mainWindow, {
        type: 'warning',
        title: 'Update check failed',
        message: 'Could not check for updates.',
        detail,
        buttons: ['OK'],
      });
    }
    autoUpdater.__manualCheck = false;
  });

  autoUpdater.on('update-not-available', () => {
    if (autoUpdater.__manualCheck && mainWindow && !mainWindow.isDestroyed()) {
      dialog.showMessageBox(mainWindow, {
        type: 'info', title: 'Up to date',
        message: 'You are on the newest release (' + app.getVersion() + ').',
        buttons: ['OK'],
      });
    }
    autoUpdater.__manualCheck = false;
  });

  autoUpdater.on('update-downloaded', async (info) => {
    autoUpdater.__manualCheck = false;
    if (!mainWindow || mainWindow.isDestroyed()) return;

    // latest.yml describes Agon-Setup-64.exe, because a single YAML file cannot describe
    // two binaries and serving the 64-bit build is the safe choice (an x64 installer can
    // only fail on a 32-bit machine, while the right file downloaded by hand always works).
    // So a 32-bit install is told where its own installer is instead of being handed one
    // it cannot run.
    if (process.arch === 'ia32') {
      await dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: 'Update available',
        message: 'Agon ' + info.version + ' is available for 32-bit Windows.',
        detail: 'Automatic installation is only set up for the 64-bit build. Download '
          + 'Agon-Setup-32.exe from the release page and run it; your data stays as it is.',
        buttons: ['Open the release page', 'Later'],
        defaultId: 0,
        cancelId: 1,
      }).then((answer) => {
        if (answer.response === 0) {
          shell.openExternal('https://github.com/' + REPO_OWNER + '/' + REPO_NAME
            + '/releases/latest');
        }
      });
      return;
    }

    const answer = await dialog.showMessageBox(mainWindow, {
      type: 'question',
      title: 'Update ready',
      message: 'Agon ' + info.version + ' has been downloaded.',
      detail: 'Install it now? The app closes and reopens on the new version.',
      buttons: ['Install and restart', 'Later'],
      defaultId: 0,
      cancelId: 1,
    });
    if (answer.response === 0) autoUpdater.quitAndInstall();
  });
  return autoUpdater;
}

function checkForUpdates(manual) {
  if (!autoUpdater) {
    if (manual && mainWindow && !mainWindow.isDestroyed()) {
      dialog.showMessageBox(mainWindow, {
        type: 'info', title: 'Updates',
        message: 'Update checks only work in a packaged build.',
        detail: 'Run "npm run dist" (or install a release) to get the updater.',
        buttons: ['OK'],
      });
    }
    return;
  }
  autoUpdater.__manualCheck = Boolean(manual);
  autoUpdater.checkForUpdates().catch(() => {
    // the 'error' handler above already reported it when the check was manual
  });
}

// ---------------------------------------------------------------- lifecycle

// Before the single-instance lock: that lock lives in userData, and a portable copy on a USB
// stick must lock its own folder rather than fight the installed copy over %APPDATA%.
const portableData = userDataDir();
if (portableData) app.setPath('userData', portableData);

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    // Anything that is not https never reaches a renderer.
    // Every window, including ones Electron creates on its own, refuses anything that is
    // not https. Per-window host rules live in attachNavigationPolicy, which knows whether
    // it is guarding the app window or a Google popup.
    app.on('web-contents-created', (event, contents) => {
      contents.on('will-navigate', (navEvent, rawUrl) => {
        const info = urlInfo(rawUrl);
        if (!info || info.scheme !== 'https') navEvent.preventDefault();
      });
    });

    autoUpdater = updates();
    buildMenu();
    createWindow();

    ipcMain.on('agon:command', (event, command) => runCommand(command));
    ipcMain.handle('agon:version', () => app.getVersion());
    ipcMain.handle('agon:check-updates', () => {
      checkForUpdates(true);
      return true;
    });

    // One quiet check shortly after launch, then every six hours: the same rhythm as the
    // Android build, and never while the window is hidden.
    const firstCheck = setTimeout(() => checkForUpdates(false), 20000);
    const repeat = setInterval(() => checkForUpdates(false), 6 * 60 * 60 * 1000);
    app.on('before-quit', () => {
      clearTimeout(firstCheck);
      clearInterval(repeat);
    });
  });

  app.on('window-all-closed', () => {
    app.quit();
  });

  // A second window is never wanted: this is a single-window client.
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
}
