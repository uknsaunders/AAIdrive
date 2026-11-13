#!/usr/bin/env python3
"""Builds the i3D desk simulator: one HTML page that runs the real car screen (app/src/main/assets/i3dash/index.html)
with a stand-in phone, so the layout and knob flow can be tried at a desk.

    python3 tools/simulator/build.py [output.html]
"""
import base64, json, os, re, sys
here = os.path.dirname(os.path.abspath(__file__))
app = os.path.join(here, '..', '..', 'app', 'src', 'main', 'assets', 'i3dash')
read = lambda *p: open(os.path.join(*p), encoding='utf-8').read()
b64 = lambda path: base64.b64encode(open(path, 'rb').read()).decode()

page = read(app, 'index.html')

def once(old, new):
    global page
    assert page.count(old) == 1, (old, page.count(old))
    page = page.replace(old, new)

# the map libraries are left out: the simulator draws a stand-in map, because map pictures cannot load there
for tag in ('<link rel="stylesheet" href="lib/maplibre-gl.css">', '<link rel="stylesheet" href="lib/leaflet.css">',
            '<script src="lib/maplibre-gl.js"></script>', '<script src="lib/leaflet.js"></script>'):
    once(tag, '')
# fonts and car pictures travel inside the page
page, fonts = re.subn(r"url\((['\"]?)fonts/([\w.-]+)\1\)", lambda m: "url(data:font/otf;base64,%s)" % b64(os.path.join(app, 'fonts', m.group(2))), page)
assert fonts >= 1, 'no fonts found'
cars = {f[:-5]: 'data:image/webp;base64,' + b64(os.path.join(app, 'cars', f)) for f in sorted(os.listdir(os.path.join(app, 'cars'))) if f.endswith('.webp')}
once("'cars/'+PAINTS[b]+'--'+BELTS[e]+'.webp'", "__cars[PAINTS[b]+'--'+BELTS[e]]")
page, first = re.subn(r'src="cars/([\w-]+)\.webp"', lambda m: 'src="%s"' % cars[m.group(1)], page)
assert first == 1
once('<head>', '<head>\n<script>window.__cars=%s;</script>\n<script>\n%s</script>' % (json.dumps(cars), read(here, 'engine-top.js')))
once('</body>', '<script>\n%s</script>\n</body>' % read(here, 'engine-bottom.js'))

shell = read(here, 'shell.html')
assert shell.count('%%APP%%') == 1
out = shell.replace('%%APP%%', json.dumps(page).replace('</', '<\\/'))
target = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, 'i3d-simulator.html')
open(target, 'w', encoding='utf-8').write(out)
print(target, '%.1f MB' % (len(out.encode()) / 1e6))
