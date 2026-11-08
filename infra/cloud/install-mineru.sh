#!/usr/bin/env bash
set -euo pipefail

# Keep the legacy /file_parse ZIP contract used by the Java application.
root=/opt/know-engine-mineru
install -d -m 755 "$root" "$root/bin" "$root/cache" "$root/output"
python3 - "$root" <<'PY'
import hashlib
import io
import json
from pathlib import Path
import sys
from urllib.request import urlopen
from urllib.parse import urlparse
from zipfile import ZipFile

root = Path(sys.argv[1])
metadata = json.load(urlopen('https://pypi.org/pypi/uv/0.13.7/json', timeout=30))
wheel = next(item for item in metadata['urls'] if item['filename'].endswith('manylinux_2_17_x86_64.manylinux2014_x86_64.whl'))
download_url = 'https://mirrors.aliyun.com/pypi' + urlparse(wheel['url']).path
content = urlopen(download_url, timeout=90).read()
assert hashlib.sha256(content).hexdigest() == wheel['digests']['sha256']
with ZipFile(io.BytesIO(content)) as archive:
    executable = next(name for name in archive.namelist() if name.endswith('/scripts/uv'))
    target = root / 'bin/uv'
    target.write_bytes(archive.read(executable))
    target.chmod(0o755)
print('Verified uv', metadata['info']['version'])
PY
export UV_CACHE_DIR="$root/cache/uv"
export UV_HTTP_TIMEOUT=180
"$root/bin/uv" venv --allow-existing --python /usr/bin/python3 "$root/venv"
"$root/bin/uv" pip install --python "$root/venv/bin/python" \
  'torch==2.9.1+cpu' 'torchvision==0.24.1+cpu' --no-deps --index-url https://download.pytorch.org/whl/cpu
"$root/bin/uv" pip install --python "$root/venv/bin/python" 'mineru[pipeline]==3.4.5' 'six==1.17.0' \
  --index-url https://mirrors.aliyun.com/pypi/simple
if ! ldconfig -p | grep 'libGL.so.1' >/dev/null; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libgl1
fi
"$root/bin/uv" pip freeze --python "$root/venv/bin/python" > "$root/requirements.lock.txt"
"$root/venv/bin/python" -c 'import torch; from mineru.version import __version__; print("MinerU", __version__, "torch", torch.__version__, "CUDA", torch.cuda.is_available())'
"$root/venv/bin/python" -c 'import mineru.backend.pipeline.pipeline_analyze; print("Pipeline imports passed")'
"$root/venv/bin/mineru-api" --help
