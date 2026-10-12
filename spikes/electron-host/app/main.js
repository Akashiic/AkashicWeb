// Probe: Electron offscreen rendering as a browser host for a Java 8 parent.
// Args: --url=<page> --fps=30 --sink=127.0.0.1:<port> --token=<t> --seconds=N --w=1280 --h=720 [--gpu]
const { app, BrowserWindow, session, ipcMain } = require('electron');
const net = require('net');
const path = require('path');
const arg = (k, d) => { const a = process.argv.find(s => s.startsWith('--' + k + '=')); return a ? a.split('=').slice(1).join('=') : d; };
const URL_ = arg('url'), FPS = +arg('fps', '30'), W = +arg('w', '1280'), H = +arg('h', '720');
const SECONDS = +arg('seconds', '20'), SINK = arg('sink', ''), TOKEN = arg('token', 'x');
if (!process.argv.includes('--gpu')) app.disableHardwareAcceleration();
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
const log = (...a) => process.stderr.write('[probe] ' + a.join(' ') + '\n');

let sock = null, backpressured = false, sent = 0, dropped = 0, paints = 0, bitmapMs = 0, dirtyArea = 0;
function connectSink() {
  if (!SINK) return;
  const [h, p] = SINK.split(':');
  sock = net.connect({ host: h, port: +p }, () => { sock.setNoDelay(true); sock.write(TOKEN + '\n'); log('sink connected'); });
  sock.on('drain', () => { backpressured = false; });
  sock.on('error', e => log('sink error', e.message));
  sock.on('close', () => { log('sink closed -> quit'); app.quit(); }); // parent gone: exit (lifecycle)
}

app.whenReady().then(async () => {
  connectSink();
  // Deny every permission request (policy), except display capture used for audio below.
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => { cb(perm === 'media' || perm === 'display-capture'); });
  const win = new BrowserWindow({ width: W, height: H, show: false,
    webPreferences: { offscreen: true, backgroundThrottling: false, sandbox: true, contextIsolation: true } });
  win.webContents.setFrameRate(FPS);
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('console-message', (e, ...rest) => { const m = (e && e.message) || rest[1]; log('page:', m); });
  win.webContents.on('paint', (event, dirty, image) => {
    paints++;
    dirtyArea += dirty.width * dirty.height;
    if (!sock || backpressured) { dropped++; return; }
    const t0 = process.hrtime.bigint();
    const bmp = image.toBitmap(); // BGRA on little-endian
    bitmapMs += Number(process.hrtime.bigint() - t0) / 1e6;
    const sz = image.getSize();
    const hdr = Buffer.alloc(16); hdr.writeInt32BE(0x414B5746, 0); hdr.writeInt32BE(sz.width, 4); hdr.writeInt32BE(sz.height, 8); hdr.writeInt32BE(bmp.length, 12);
    sock.write(hdr);
    if (!sock.write(bmp)) backpressured = true;
    sent++;
  });
  await win.loadURL(URL_);
  log('loaded', URL_);

  // Audio: capture the OSR frame's audio through the display-media handler into a hidden capture window.
  session.defaultSession.setDisplayMediaRequestHandler((req, cb) => {
    log('display media request; frame=', req.frame && req.frame.url);
    cb({ video: win.webContents.mainFrame, audio: win.webContents.mainFrame });
  });
  if (process.argv.includes('--nocap')) { log('no audio capture window'); } else {
  const cap = new BrowserWindow({ show: false, webPreferences: { preload: path.join(__dirname, 'cap-preload.js'), contextIsolation: false, sandbox: false } });
  ipcMain.on('pcm', (e, info) => log('pcm', JSON.stringify(info)));
  cap.webContents.on('console-message', (e, ...rest) => { const m = (e && e.message) || rest[1]; log('cap:', m); });
  await cap.loadFile(path.join(__dirname, 'capture.html'));
  setTimeout(() => cap.webContents.executeJavaScript('startCapture()', true).catch(err => log('cap err', err.message)), 2000);
  }

  // Input: click the text box, type ABNT2-style text as char events, Backspace as keyDown/keyUp.
  setTimeout(async () => {
    const click = (x, y) => { win.webContents.sendInputEvent({ type: 'mouseDown', x, y, button: 'left', clickCount: 1 });
                              win.webContents.sendInputEvent({ type: 'mouseUp', x, y, button: 'left', clickCount: 1 }); };
    click(100, 20);
    for (const ch of 'ação ç $%&X') win.webContents.sendInputEvent({ type: 'char', keyCode: ch });
    win.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Backspace' });
    win.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Backspace' });
    win.webContents.sendInputEvent({ type: 'mouseWheel', x: 640, y: 400, deltaX: 0, deltaY: -120 });
    setTimeout(async () => {
      const v = await win.webContents.executeJavaScript('JSON.stringify({value: document.getElementById("t").value, wheel: window.__wheel||0, clicks: window.__clicks||0})');
      log('input result', v);
    }, 500);
  }, 4000);

  const t0 = Date.now();
  setTimeout(async () => {
    const s = (Date.now() - t0) / 1000;
    const vs = await win.webContents.executeJavaScript('JSON.stringify(window.__status||{})');
    log(`summary seconds=${s.toFixed(1)} paints=${paints} paintsPerSec=${(paints / s).toFixed(1)} sent=${sent} dropped=${dropped} avgToBitmapMs=${(bitmapMs / Math.max(1, sent)).toFixed(2)} avgDirtyFrac=${(dirtyArea / Math.max(1, paints) / (W * H)).toFixed(2)}`);
    log('video status', vs);
    app.quit();
  }, SECONDS * 1000);
});
