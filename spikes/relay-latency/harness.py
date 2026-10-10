import subprocess, threading, time, json, os, sys, signal
S = os.environ['SPIKE_DIR']  # contains mtx/bin/mediamtx, jbuild/classes, jbuild/java-cef and lat/
L = S + '/lat'
mode = sys.argv[1]
vcodec = sys.argv[2] if len(sys.argv) > 2 else 'vp9'
procs = []
def start(cmd, **kw):
    p = subprocess.Popen(cmd, **kw); procs.append(p); return p
mtx_log = open(L + '/mtx.log', 'w')
start([S + '/mtx/bin/mediamtx', L + '/mediamtx.yml'], cwd=L, stdout=mtx_log, stderr=subprocess.STDOUT)
json.dump({'t0': None}, open(L + '/www/t0.json', 'w'))
start([sys.executable, '-I', '-m', 'http.server', '8000', '--bind', '127.0.0.1', '--directory', L + '/www'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(2)
boxes = ['drawbox=x=0:y=0:w=1024:h=64:color=black:t=fill']
for k in range(16):
    boxes.append("drawbox=x=%d:y=0:w=64:h=64:color=white:t=fill:enable='mod(floor(n/%d),2)'" % (64 * k, 2 ** k))
venc = {
  'vp9': ['-c:v', 'libvpx-vp9', '-deadline', 'realtime', '-cpu-used', '8', '-row-mt', '1', '-tile-columns', '2', '-b:v', '1800k', '-minrate', '1800k', '-maxrate', '1800k', '-bufsize', '1800k', '-g', '30', '-lag-in-frames', '0', '-threads', '4'],
  'vp8': ['-c:v', 'libvpx', '-deadline', 'realtime', '-cpu-used', '16', '-b:v', '2500k', '-minrate', '2500k', '-maxrate', '2500k', '-bufsize', '2500k', '-g', '30', '-lag-in-frames', '0', '-auto-alt-ref', '0', '-threads', '4'],
}[vcodec]
ff = ['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-nostdin', '-re', '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=30',
      '-re', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-vf', ','.join(boxes)] + venc + \
     ['-strict', 'experimental', '-c:a', 'libopus', '-b:a', '96k', '-application', 'lowdelay', '-f', 'rtsp', '-rtsp_transport', 'tcp', 'rtsp://127.0.0.1:8554/live',
      '-progress', 'pipe:1', '-stats_period', '0.25']
ffp = start(ff, stdout=subprocess.PIPE, stderr=open(L + '/ff.log', 'w'), text=True)
best = [None]
def rd():
    for line in ffp.stdout:
        if line.startswith('out_time_us='):
            try: ot = int(line.split('=')[1]) / 1e6
            except ValueError: continue
            if ot <= 0: continue
            cand = time.time() - ot
            if best[0] is None or cand < best[0]:
                best[0] = cand
                json.dump({'t0': best[0] * 1000.0}, open(L + '/www/t0.json.tmp', 'w')); os.replace(L + '/www/t0.json.tmp', L + '/www/t0.json')
threading.Thread(target=rd, daemon=True).start()
time.sleep(4)
cmd = ['xvfb-run', '-a', 'java', '-cp', S + '/jbuild/classes', 'probe.Probe152', S + '/jbuild/java-cef/jcef_build/native/Release',
       'http://127.0.0.1:8000/index.html?mode=' + mode, '75', L + '/noop.js']
nclients = int(os.environ.get('NCLIENTS', '1'))
cps = [subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True) for _ in range(nclients)]
for i, cp in enumerate(cps):
    out = cp.communicate(timeout=170)[0]
    for line in out.splitlines():
        if line.startswith('CONSOLE:') and ('RESULT' in line or 'WARMUP' in line or 'FIRST_FRAME' in line or '_ERR' in line): print('client%d %s' % (i, line))
print('t0_est_wall_ms', best[0] and int(best[0] * 1000))
for p in procs:
    try: p.send_signal(signal.SIGINT)
    except Exception: pass
time.sleep(1)
for p in procs:
    try: p.kill()
    except Exception: pass
