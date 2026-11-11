import sys, re
# usage: python3 make_lli.py nka-laden.liste nka.spg nka.lli
laden, spg, out = sys.argv[1:4]
drawers = []
for line in open(laden, encoding='ascii'):
    f = line.split()
    if len(f) < 3:
        continue
    nr = int(f[0].rsplit('/', 1)[1])
    count, total = int(f[1]), int(f[2])
    drawers.append((nr, total - count + 1, count))
labels = {}
for line in open(spg, encoding='cp850'):
    m = re.match(r'(\d+) (.*)$', line.rstrip('\r\n'))
    if m:
        labels[int(m.group(1))] = m.group(2).strip()
missing = [nr for nr, _, _ in drawers if nr not in labels]
if missing:
    sys.exit('missing labels for drawers: %s' % missing[:20])
with open(out, 'w', encoding='iso-8859-1', newline='\n') as o:
    for nr, start, count in drawers:
        o.write('%d\t%s\t%d\t%d\n' % (nr, labels[nr], start, count))
print(len(drawers), 'drawers')
