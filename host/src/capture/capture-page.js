'use strict';
// Runs in the hidden capture window (sandboxed, contextIsolation on). It asks for the screen's tab
// audio through getDisplayMedia; the session's display-media handler grants exactly the screen this
// window was created for (capture.js). Processing switches (AEC/AGC/NS) are off so the PCM is the
// page's own mix. The video track is required by the API and stopped at once.

window.akwStartCapture = async function akwStartCapture(workletSource) {
  const stream = await navigator.mediaDevices.getDisplayMedia({
    audio: { echoCancellation: false, autoGainControl: false, noiseSuppression: false, channelCount: 2, sampleRate: 48000 },
    video: true,
  });
  stream.getVideoTracks().forEach(t => t.stop());
  const tracks = stream.getAudioTracks();
  if (tracks.length === 0) throw new Error('no-audio-track');
  const ctx = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' });
  const url = URL.createObjectURL(new Blob([workletSource], { type: 'text/javascript' }));
  await ctx.audioWorklet.addModule(url);
  URL.revokeObjectURL(url);
  const source = ctx.createMediaStreamSource(new MediaStream(tracks));
  const node = new AudioWorkletNode(ctx, 'akw-capture', {
    numberOfInputs: 1, numberOfOutputs: 1, channelCount: 2, channelCountMode: 'explicit', channelInterpretation: 'speakers',
  });
  node.port.onmessage = e => window.akwCapture.pcm(e.data);
  source.connect(node);
  // The node writes nothing to its output, so this connection plays silence; it only keeps the graph
  // pulled by the audio device clock.
  node.connect(ctx.destination);
  tracks[0].addEventListener('ended', () => window.akwCapture.ended());
  const settings = tracks[0].getSettings();
  return { sampleRate: ctx.sampleRate, channels: settings.channelCount || 2 };
};
