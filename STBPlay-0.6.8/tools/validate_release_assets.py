#!/usr/bin/env python3
"""Validate store dimensions, listing limits and offline/public policy consistency."""
from pathlib import Path
import html, json, re, struct
root=Path(__file__).resolve().parents[1]
a=root/'publishing/store-listing/assets'
checks=[]
def png(path,size,colors=None):
 b=path.read_bytes(); assert b[:8]==b'\x89PNG\r\n\x1a\n',path
 w,h=struct.unpack('>II',b[16:24]); assert (w,h)==size,(path,w,h)
 if colors is not None: assert b[25] in colors,(path,b[25])
 checks.append({'file':str(path.relative_to(root)),'width':w,'height':h,'bytes':len(b),'pngColorType':b[25]})
png(a/'app-icon-512.png',(512,512),[6]);assert (a/'app-icon-512.png').stat().st_size<1024*1024
for p in sorted((a/'feature-graphics').glob('*.png')):png(p,(1024,500),[2])
png(a/'tv-banner-1280x720.png',(1280,720),[2])
png(a/'tv-launcher-banner-320x180.png',(320,180),[2])
shots=sorted((a/'screenshots').glob('*.png'))
assert len(shots)==8,('Expected 8 real screenshots',len(shots))
for p in shots:png(p,(1920,1080),[2])
short=(root/'publishing/store-listing/short-description.txt').read_text().strip()
full=(root/'publishing/store-listing/full-description.txt').read_text()
assert 1<=len(short)<=80 and 1<=len(full)<=4000
props={}
for line in (root/'publishing.properties').read_text().splitlines():
 if '=' in line and not line.lstrip().startswith('#'):
  k,v=line.split('=',1);props[k.strip()]=v.strip()
policy=(root/'app/src/main/assets/privacy_policy.txt').read_text()
for key in ['publisherName','supportEmail']:policy=policy.replace('{{'+key+'}}',props[key])
for name in ['index.html','privacy-policy.html']:
 page=(root/'publishing/privacy-site'/name).read_text()
 body=re.search(r'<pre class="policy">(.*?)</pre>',page,re.S).group(1)
 assert html.unescape(body)==policy.partition('\n')[2], 'Policy mismatch'
 assert '{{' not in page and '<script' not in page.lower()
 assert not re.search(r'<(?:img|iframe|link|script)\b[^>]*(?:src|href)\s*=',page,re.I)
assert props['privacyPolicyUrl']=='','In-app policy is intended to remain offline-only'
manifest={'version':'0.6.8','versionCode':22,'shortDescriptionCharacters':len(short),'fullDescriptionCharacters':len(full),'recommendedFeatureGraphic':'feature-graphics/feature-02-1024x500.png','screenshots':'Eight real 1920x1080 app captures with fictional demo catalog and synthetic footage. No overlays, device frames, credentials or third-party program artwork. PNG alpha removed losslessly for 24-bit upload format.','assets':checks}
(root/'publishing/store-listing/asset-manifest.json').write_text(json.dumps(manifest,indent=2))
print('PASS: listing limits, 8 screenshots, icon/features/banners, matching policies, no website scripts/external resources.')
