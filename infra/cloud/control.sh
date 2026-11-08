#!/usr/bin/env bash
set -euo pipefail

project=${1:-}
action=${2:-}
case "$project:$action" in
  know-engine:start|know-engine:stop|know-engine:status|deephelp:start|deephelp:stop|deephelp:status) ;;
  *) echo 'Usage: control.sh know-engine|deephelp start|stop|status' >&2; exit 2 ;;
esac

root=/srv/know-engine
exec 9>"$root/$project.control.lock"
flock -w 90 9

compose() { docker compose --project-directory "$root" -f "$root/compose.yaml" "$@"; }
container_state() { docker inspect -f '{{if and .State.Running .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1"; }
wait_containers() {
  local name state attempt
  for name; do
    for attempt in {1..45}; do
      state=$(container_state "$name")
      [[ "$state" == healthy || "$state" == running ]] && break
      sleep 2
    done
    if [[ "$state" != healthy && "$state" != running ]]; then
      echo "$name failed to become ready ($state)" >&2
      return 1
    fi
  done
}
start_containers() { docker start "$@" >/dev/null; wait_containers "$@"; }
stop_containers() { docker stop --time 45 "$@" >/dev/null; }
shared=(deephelp-redis golearn-course-mysql)

if [[ "$project" == know-engine ]]; then
  case "$action" in
    start)
      start_containers "${shared[@]}"
      compose up -d --no-build
      if systemctl cat know-engine-mineru.service >/dev/null 2>&1; then
        systemctl start know-engine-mineru.service
        for attempt in {1..45}; do
          if curl -fsS --max-time 3 http://127.0.0.1:18000/health >/dev/null; then break; fi
          sleep 2
        done
        curl -fsS --max-time 5 http://127.0.0.1:18000/health >/dev/null
      fi
      for attempt in {1..45}; do
        if curl -fs --max-time 3 'http://127.0.0.1:19200/_cluster/health?wait_for_status=yellow&timeout=2s' | grep -q '"timed_out":false' &&
           [[ $(curl -sS --max-time 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:19090/ 2>/dev/null) =~ ^(200|403)$ ]]; then
          curl -fsS --max-time 5 -X PUT -H 'Content-Type: application/json' \
            -d '{"index_patterns":["know-engine-vector"],"template":{"settings":{"number_of_replicas":0}}}' \
            http://127.0.0.1:19200/_index_template/know-engine-single-node >/dev/null
          if curl -fsI --max-time 5 http://127.0.0.1:19200/know-engine-vector >/dev/null; then
            curl -fsS --max-time 5 -X PUT -H 'Content-Type: application/json' -d '{"index":{"number_of_replicas":0}}' \
              http://127.0.0.1:19200/know-engine-vector/_settings >/dev/null
          fi
          compose ps
          exit 0
        fi
        sleep 2
      done
      compose ps -a
      echo 'Elasticsearch or the S3 endpoint did not become ready' >&2
      exit 1 ;;
    stop)
      if systemctl cat know-engine-mineru.service >/dev/null 2>&1; then systemctl stop know-engine-mineru.service; fi
      compose stop --timeout 45
      compose ps -a ;;
    status)
      compose ps -a
      if systemctl cat know-engine-mineru.service >/dev/null 2>&1; then
        systemctl show know-engine-mineru.service -p ActiveState -p MainPID -p MemoryCurrent -p MemoryPeak
      fi ;;
  esac
  exit 0
fi

app=/opt/deephelp-app/bin/control.sh
units=(deephelp-modern@app deephelp-modern@business deephelp-modern@retrieval deephelp-modern@rights)
core=(deephelp-etcd deephelp-milvus deephelp-postgres-protocol)
observation_storage=(deephelp-langfuse-postgres-1 deephelp-langfuse-clickhouse-1 deephelp-langfuse-redis-1 deephelp-langfuse-seaweedfs-1)
observation_apps=(deephelp-langfuse-web-1 deephelp-langfuse-worker-1)
metrics=(deephelp-metrics-prometheus-1 deephelp-metrics-grafana-1)

case "$action" in
  start)
    start_containers deephelp-redis
    start_containers deephelp-etcd
    start_containers deephelp-milvus deephelp-postgres-protocol "${observation_storage[@]}"
    start_containers "${observation_apps[@]}"
    bash "$app" start
    start_containers "${metrics[@]}"
    for attempt in {1..20}; do
      if bash "$app" health; then exit 0; fi
      sleep 2
    done
    echo 'DeepHelp application health check timed out' >&2
    exit 1 ;;
  stop)
    bash "$app" stop
    stop_containers "${metrics[@]}" "${observation_apps[@]}"
    stop_containers "${observation_storage[@]}"
    stop_containers deephelp-milvus deephelp-postgres-protocol
    stop_containers deephelp-etcd
    echo 'DeepHelp project containers stopped. Shared Redis and MySQL remain available.' ;;
  status)
    systemctl show -p Id -p ActiveState -p MainPID "${units[@]}"
    for name in "${core[@]}" "${observation_storage[@]}" "${observation_apps[@]}" "${metrics[@]}" "${shared[@]}"; do
      printf '%s %s\n' "$name" "$(container_state "$name")"
    done ;;
esac
