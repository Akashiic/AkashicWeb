(async function(){
  const ms = [
    'video/mp4; codecs="avc1.4d401f"', 'audio/mp4; codecs="mp4a.40.2"', 'audio/mp4; codecs="mp4a.40.5"',
    'video/mp4; codecs="avc1.4d401f,mp4a.40.2"', 'video/mp2t; codecs="avc1.42E01E,mp4a.40.2"',
    'video/mp4; codecs="hev1.1.6.L93.B0"', 'video/webm; codecs="vp9"', 'video/webm; codecs="vp09.00.40.08"',
    'audio/webm; codecs="opus"', 'video/mp4; codecs="av01.0.05M.08"', 'video/mp4; codecs="vp09.00.40.08"',
    'audio/mp4; codecs="opus"', 'audio/mpeg', 'audio/webm; codecs="vorbis"'];
  for (const t of ms) console.log('MSE ' + t + ' => ' + (window.MediaSource ? MediaSource.isTypeSupported(t) : 'no MSE'));
  const v = document.createElement('video');
  for (const t of ['video/mp4; codecs="avc1.42E01E"', 'audio/aac', 'application/vnd.apple.mpegurl', 'application/x-mpegURL', 'video/webm; codecs="vp9,opus"'])
    console.log('canPlayType ' + t + ' => "' + v.canPlayType(t) + '"');
  try {
    const cfg = [{initDataTypes:['cenc'], videoCapabilities:[{contentType:'video/mp4; codecs="avc1.42E01E"'}]},
                 {initDataTypes:['webm'], videoCapabilities:[{contentType:'video/webm; codecs="vp9"'}]}];
    const acc = await navigator.requestMediaKeySystemAccess('com.widevine.alpha', cfg);
    console.log('EME widevine => granted ' + JSON.stringify(acc.getConfiguration().videoCapabilities));
  } catch (e) { console.log('EME widevine => ' + e.name + ': ' + e.message); }
  console.log('UA ' + navigator.userAgent);
  
})();

setTimeout(function(){
  var v = document.createElement('video'); v.muted = true; v.autoplay = true; document.body.appendChild(v);
  v.addEventListener('error', function(){ console.log('H264AAC_VIDEO_ERROR code=' + (v.error && v.error.code) + ' msg=' + (v.error && v.error.message)); console.log('PROBE_DONE'); });
  v.addEventListener('playing', function(){ console.log('H264AAC_PLAYING ' + v.videoWidth + 'x' + v.videoHeight); });
  v.src = 'file:///REPLACE_WITH/h264aac.mp4';
  setTimeout(function(){ var q = v.getVideoPlaybackQuality(); console.log('H264AAC_STATUS t=' + v.currentTime.toFixed(2) + ' ready=' + v.readyState + ' decoded=' + q.totalVideoFrames + ' audioTracks=' + (v.audioTracks ? v.audioTracks.length : 'n/a') + ' webkitAudioDecodedByteCount=' + v.webkitAudioDecodedByteCount); console.log('PROBE_DONE'); }, 4000);
}, 1500);
