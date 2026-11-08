#!/usr/bin/env bash
set -euo pipefail
umask 077

stage=$(cd -- "${1:?Pass the staging directory}" && pwd -P)
root=/srv/know-engine
install -d -m 0700 "$root" "$root/config" "$root/bin"
exec 9>"$root/provision.lock"
flock -x 9
install -m 0600 "$stage/compose.yaml" "$root/compose.yaml"
install -m 0644 "$stage/schema.sql" "$root/schema.sql"

if [[ ! -f "$root/config/client.properties" ]]; then
  {
    printf 'KNOW_ENGINE_JDBC_URL=jdbc:mysql://127.0.0.1:23306/know_engine?rewriteBatchedStatements=true\n'
    printf 'KNOW_ENGINE_DB_USER=know_engine\nKNOW_ENGINE_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'KNOW_ENGINE_REDIS_PORT=26379\nKNOW_ENGINE_REDIS_USERNAME=know_engine\nKNOW_ENGINE_REDIS_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'KNOW_ENGINE_S3_ENDPOINT=http://127.0.0.1:19090\nKNOW_ENGINE_S3_ACCESS_KEY=know-engine\nKNOW_ENGINE_S3_SECRET_KEY=%s\n' "$(openssl rand -hex 24)"
    printf 'KNOW_ENGINE_ELASTICSEARCH_URL=http://127.0.0.1:19200\n'
  } > "$root/config/client.properties"
fi
property() { sed -n "s/^$1=//p" "$root/config/client.properties"; }
db_password=$(property KNOW_ENGINE_DB_PASSWORD)
redis_password=$(property KNOW_ENGINE_REDIS_PASSWORD)
s3_secret=$(property KNOW_ENGINE_S3_SECRET_KEY)
for secret in "$db_password" "$redis_password" "$s3_secret"; do
  [[ "$secret" =~ ^[a-f0-9]{48}$ ]]
done
printf 'AWS_ACCESS_KEY_ID=know-engine\nAWS_SECRET_ACCESS_KEY=%s\n' "$s3_secret" > "$root/config/s3.env"

# Reuse the existing MySQL server while keeping schema, account and grants separate.
{
  printf 'CREATE DATABASE IF NOT EXISTS know_engine CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n'
  printf "CREATE USER IF NOT EXISTS 'know_engine'@'%%' IDENTIFIED BY '%s';\n" "$db_password"
  printf "GRANT SELECT, INSERT, UPDATE, DELETE ON know_engine.* TO 'know_engine'@'%%';\nUSE know_engine;\n"
  cat "$root/schema.sql"
} | docker exec -i golearn-course-mysql sh -c 'export MYSQL_PWD=$(cat "$MYSQL_ROOT_PASSWORD_FILE"); exec mysql --protocol=socket -uroot --default-character-set=utf8mb4'
echo 'MySQL schema and app user ready'

redis_admin() {
  docker exec deephelp-redis sh -c 'export REDISCLI_AUTH=$(cat /run/secrets/redis-admin); exec redis-cli -e --user deephelp_admin "$@"' sh "$@"
}
redis_hash=$(printf %s "$redis_password" | sha256sum | cut -d' ' -f1)
redis_rules=(on "#$redis_hash" '~know-engine:*'
  '~redisson_unlock_latch:{know-engine:*}*' '~redisson_lock__channel:{know-engine:*}*'
  '&redisson_lock__channel:{know-engine:*}*' +@connection +info +@pubsub +eval +evalsha '+script|load' '+script|exists'
  +get +set +setex +del +exists +expire +pexpire +ttl +pttl +hexists +hget +hset +hincrby)
acl=/srv/deephelp-infra/.secrets/redis.acl
if ! grep -q '^user know_engine ' "$acl"; then
  # This ACL file is bind-mounted as a file. Append the new user without replacing the inode.
  printf '\nuser know_engine %s\n' "${redis_rules[*]}" >> "$acl"
fi
redis_admin ACL SETUSER know_engine reset "${redis_rules[@]}" >/dev/null
echo 'Shared Redis app ACL ready without restart'
