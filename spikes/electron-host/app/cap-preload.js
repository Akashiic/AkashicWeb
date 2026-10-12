const { ipcRenderer } = require('electron');
window.startCapture = async function () {
  try {
    const stream = await navigator.mediaDevices.getDisplayMedia({ audio: { echoCancellation: false, autoGainControl: false, noiseSuppression: false, channelCount: 2 }, video: true });
    stream.getVideoTracks().forEach(t => t.stop()); // audio only: drop the video capture track
    const at = stream.getAudioTracks();
    console.log('capture tracks audio=' + at.length + ' video=' + stream.getVideoTracks().length + ' settings=' + JSON.stringify(at[0] ? at[0].getSettings() : {}));
    const ctx = new AudioContext({ sampleRate: 48000 });
    const src = ctx.createMediaStreamSource(new MediaStream(at));
    const proc = ctx.createScriptProcessor(4096, 2, 2);
    let frames = 0, sumsq = 0, n = 0, last = Date.now();
    proc.onaudioprocess = (ev) => {
      const l = ev.inputBuffer.getChannelData(0); this.ch = ev.inputBuffer.numberOfChannels;
      frames += l.length; for (let i = 0; i < l.length; i += 16) { sumsq += l[i] * l[i]; n++; }
      if (Date.now() - last > 2000) { ipcRenderer.send('pcm', { framesTotal: frames, rate: ctx.sampleRate, rms: Math.sqrt(sumsq / Math.max(1, n)).toFixed(4) }); sumsq = 0; n = 0; last = Date.now(); }
    };
    src.connect(proc); proc.connect(ctx.destination);
  } catch (e) { console.log('capture failed: ' + e.name + ' ' + e.message); }
};
