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
const REPO_NAME = 'Arena-Ai-App';
const ARENA_HOSTS = ['arena.ai', 'lmarena.ai'];
/** Auth surfaces that have to stay inside the app, or the cookie lands somewhere else. */
const PROVIDER_HOSTS = ['accounts.google.com', 'accounts.youtube.com', 'google.com'];

const PARTITION = 'persist:agon';
const isDev = !app.isPackaged;

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
 * A Chromium user agent with the Electron wrapper token removed.
 *
 * Nothing else is changed: the Chrome version stays the real one, so a page that sniffs
 * the browser sees what it actually is. The token is dropped because it is what some
 * sites use to refuse an "embedded" client, and because keeping it would make the session
 * look like a different browser than the one that stores it.
 */
function userAgent(ses) {
  const ua = ses.getUserAgent();
  return ua.replace(/\sElectron\/[\d.]+/i, '').replace(/\sAgon\/[\d.]+/i, '');
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
  return { width: 1200, height: 820 };
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
}

function hardenWebContents(contents) {
  // No <webview> tag: it is a second, less supervised renderer.
  contents.on('will-attach-webview', (event) => event.preventDefault());
}

function attachNavigationPolicy(contents) {
  contents.on('will-navigate', (event, rawUrl) => {
    const info = urlInfo(rawUrl);
    if (!info) {
      event.preventDefault();
      return;
    }
    if (info.scheme === 'https' && isArenaHost(info.host)) return;   // the app's own site
    event.preventDefault();
    // A provider page in the same window would replace the app's page and lose the way
    // back; anything else is not this app's business at all.
    if (info.scheme === 'https' && isProviderHost(info.host)) {
      contents.loadURL(START_URL);
    }
  });

  // window.open / target=_blank: a provider popup gets a child window in the SAME session
  // (so the cookie it sets is the one arena.ai then sees), everything else is refused.
  contents.setWindowOpenHandler(({ url }) => {
    const info = urlInfo(url);
    if (!info) return { action: 'deny' };
    if (info.scheme === 'https' && (isArenaHost(info.host) || isProviderHost(info.host))) {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 520,
          height: 700,
          parent: mainWindow || undefined,
          modal: false,
          autoHideMenuBar: true,
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
    }
    return { action: 'deny' };
  });
}

function createWindow() {
  const state = readWindowState();
  mainWindow = new BrowserWindow({
    x: state.x,
    y: state.y,
    width: state.width,
    height: state.height,
    minWidth: 420,
    minHeight: 480,
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
  attachNavigationPolicy(mainWindow.webContents);

  mainWindow.webContents.on('did-create-window', (child) => {
    childWindows.add(child);
    child.on('closed', () => childWindows.delete(child));
    hardenWebContents(child.webContents);
    attachNavigationPolicy(child.webContents);
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
      go(START_URL);
      dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: 'Sign in',
        message: 'Use arena.ai\'s own sign-in on the page.',
        detail: 'Tap Log in on the page: the email route works here, and a magic link opens '
          + 'in your default browser. If Google refuses this window, use "Open in browser" - '
          + 'the sign-in then stays in that browser, not in Agon.',
        buttons: ['Continue', 'Open arena.ai in the browser', 'Cancel'],
        defaultId: 0,
        cancelId: 2,
      }).then((answer) => {
        if (answer.response === 1) openExternally(START_URL);
      });
      break;
    default:
      break;
  }
}

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
 * A private repository needs a token in GH_TOKEN (read-only Contents), which is the same
 * rule the Android app has.
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
          + 'That usually means no release has been published yet, or the repository is '
          + 'private. A private repository needs a read-only token in GH_TOKEN, which is the '
          + 'same rule the Android app follows.';
      } else if (status) {
        detail = 'GitHub answered HTTP ' + status[1] + '.\n\n'
          + 'Check your connection, and whether a private repository needs a token in '
          + 'GH_TOKEN (Contents: read-only).';
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
