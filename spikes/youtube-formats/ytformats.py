import sys, re, json
for path in sys.argv[1:]:
    t = open(path, encoding='utf-8', errors='replace').read()
    m = re.search(r'ytInitialPlayerResponse\s*=\s*(\{.*?\});\s*(?:var|</script>)', t, re.S)
    print('==', path, len(t))
    if not m:
        print('  no ytInitialPlayerResponse; title:', re.findall(r'<title>(.*?)</title>', t)[:1]); continue
    try:
        pr = json.loads(m.group(1))
    except Exception as e:
        print('  json error', e); continue
    vd = pr.get('videoDetails', {})
    print('  title:', vd.get('title'), '| isLive:', vd.get('isLive'), '| isLiveContent:', vd.get('isLiveContent'), '| author:', vd.get('author'))
    ps = pr.get('playabilityStatus', {})
    print('  playability:', ps.get('status'), ps.get('reason'))
    sd = pr.get('streamingData', {})
    print('  keys:', sorted(sd.keys()))
    fm = {}
    for f in sd.get('adaptiveFormats', []) + sd.get('formats', []):
        fm.setdefault(f.get('mimeType'), []).append(str(f.get('itag')))
    for k, v in sorted(fm.items()):
        print('   ', k, 'itags=', ','.join(v))
    li = pr.get('microformat', {}).get('playerMicroformatRenderer', {}).get('liveBroadcastDetails')
    print('  liveBroadcastDetails:', li)
