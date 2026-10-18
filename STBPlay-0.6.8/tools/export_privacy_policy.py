#!/usr/bin/env python3
"""Export the offline app policy as a static, tracking-free GitHub Pages site."""
from pathlib import Path
import html
root = Path(__file__).resolve().parents[1]
props = {}
for line in (root / 'publishing.properties').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1); props[key.strip()] = value.strip()
policy = (root / 'app/src/main/assets/privacy_policy.txt').read_text()
for key in ('publisherName', 'supportEmail'):
    policy = policy.replace('{{' + key + '}}', props[key])
page = (root / 'tools/privacy-template.html').read_text().replace('{{policyBody}}', html.escape(policy.partition('\n')[2], quote=False))
(root / 'publishing/privacy-template.html').write_text(page)  # Backward-compatible preview path
for name in ('index.html', 'privacy-policy.html'):
    (root / 'publishing/privacy-site' / name).write_text(page)
(root / 'publishing/privacy-site/.nojekyll').touch()
print('Exported the same policy to publishing/privacy-site.')
