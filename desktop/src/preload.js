'use strict';

/**
 * The only bridge between the page and the app.
 *
 * Deliberately one-way and tiny: it exposes a version string, the update command and a
 * few navigation commands, and it never accepts a value from the page. The page cannot
 * read files, spawn anything, or reach Node - contextIsolation is on and this preload is
 * the only code that runs with both worlds in scope.
 */

const { contextBridge, ipcRenderer } = require('electron');

const ALLOWED_COMMANDS = new Set([
  'new-chat', 'chat', 'leaderboard', 'sign-in', 'sign-in-browser', 'release-page', 'devtools',
  'hide-google-on', 'hide-google-off',
]);

contextBridge.exposeInMainWorld('agon', {
  /** "1.4.22" */
  version: () => ipcRenderer.invoke('agon:version'),
  /** Asks the app to check GitHub releases; the app decides what to show. */
  checkForUpdates: () => ipcRenderer.invoke('agon:check-updates'),
  /** Sends one of a fixed set of navigation commands - anything else is ignored. */
  command: (name) => {
    if (ALLOWED_COMMANDS.has(name)) ipcRenderer.send('agon:command', name);
  },
});
