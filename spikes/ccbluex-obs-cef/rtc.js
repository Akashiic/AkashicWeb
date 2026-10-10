(function(){
  try {
    var v = RTCRtpReceiver.getCapabilities('video').codecs.map(function(c){return c.mimeType + (c.sdpFmtpLine ? '[' + c.sdpFmtpLine.slice(0,40) + ']' : '');});
    var a = RTCRtpReceiver.getCapabilities('audio').codecs.map(function(c){return c.mimeType;});
    var uniq = function(x){ return x.filter(function(e,i){return x.indexOf(e)===i;}); };
    console.log('RTC_RECV_VIDEO ' + uniq(v.map(function(s){return s.split('[')[0];})).join(','));
    console.log('RTC_RECV_AUDIO ' + uniq(a).join(','));
  } catch (e) { console.log('RTC_ERR ' + e); }
  console.log('NATIVE_HLS ' + document.createElement('video').canPlayType('application/vnd.apple.mpegurl'));
  console.log('PROBE_DONE');
})();
