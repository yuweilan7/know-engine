#!/usr/bin/env bash
set -euo pipefail

# Run from the directory containing this script, install-mineru.sh and the unit.
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ $(id -u) == 0 ]] || { echo 'Run as root.' >&2; exit 1; }
bash "$source_dir/install-mineru.sh"
id know-engine-mineru >/dev/null 2>&1 || useradd --system --home-dir /opt/know-engine-mineru --shell /usr/sbin/nologin know-engine-mineru
chown know-engine-mineru:know-engine-mineru /opt/know-engine-mineru
chown -R know-engine-mineru:know-engine-mineru /opt/know-engine-mineru/cache /opt/know-engine-mineru/output
install -m 644 "$source_dir/know-engine-mineru.service" /etc/systemd/system/know-engine-mineru.service
systemd-analyze verify /etc/systemd/system/know-engine-mineru.service
systemctl daemon-reload
systemctl start know-engine-mineru.service
for attempt in {1..45}; do
  if curl -fsS --max-time 3 http://127.0.0.1:18000/health; then exit 0; fi
  sleep 2
done
journalctl -u know-engine-mineru -n 40 --no-pager
exit 1
