#!/usr/bin/env bash
# Deploy after isolated tests. Requires explicit --restart and root.
set -euo pipefail
[[ ${1:-} == --restart && $EUID == 0 ]] || { echo 'Use sudo -n ./ops/deploy.sh --restart'; exit 2; }
project=$(cd "$(dirname "$0")/.." && pwd)
mc=/srv/games/minecraft
backup=/srv/games/minecraft-plugin-backups/featherdiscos-$(date +%Y%m%d-%H%M%S)
[[ -f "$project/build/release/plugins/FeatherDiscos.jar" ]]
[[ ! -e "$mc/plugins/FeatherDiscos.jar" ]] || { echo 'Already deployed. Review before upgrading.'; exit 2; }
[[ $(systemctl is-active featherdiscos-test.service || true) != active ]] || { echo 'Stop isolated test first.'; exit 2; }
mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$mc/plugins/URLCustomDiscs-v2.7.2-1.21-1.21.8.jar" "$mc/plugins/URLCustomDiscs" "$backup/"
cp -a "$mc/plugins/Skript/scripts/customdiscs.sk" "$backup/"
cp -a "$mc/server.properties" "$mc/plugins/Geyser-Spigot/config.yml" "$backup/"
cp -a /etc/nginx/nginx.conf "$backup/nginx.conf"
printf '%s\n' "$backup" > "$project/ops/last-backup.txt"
# minecraft-stop performs save-all and waits for the JVM, up to 240 seconds.
trap 'echo "Deployment failed; restoring original plugin"; "$project/ops/rollback.sh" --restart' ERR
systemctl stop minecraft.service
install -o minecraft -g minecraft -m 644 "$project/build/release/plugins/FeatherDiscos.jar" "$mc/plugins/"
install -o minecraft -g minecraft -m 644 "$project/build/release/plugins/JEXT-Reborn_2.2.7.jar" "$mc/plugins/"
install -o minecraft -g minecraft -m 644 "$project/build/release/plugins/packetevents-spigot-2.14.0.jar" "$mc/plugins/"
cp -a "$project/build/release/plugins/FeatherDiscos" "$project/build/release/plugins/JukeboxExtendedReborn" "$mc/plugins/"
chown -R minecraft:minecraft "$mc/plugins/FeatherDiscos" "$mc/plugins/JukeboxExtendedReborn"
# Old jar + data have been backed up; keep only the replacement in plugins/.
rm "$mc/plugins/URLCustomDiscs-v2.7.2-1.21-1.21.8.jar"
mv "$mc/plugins/URLCustomDiscs" "$backup/original-data"
mv "$mc/plugins/Skript/scripts/customdiscs.sk" "$backup/original-customdiscs.sk"
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/nginx/nginx.conf')
s=p.read_text()
assert 'proxy_pass http://127.0.0.1:19880/;' in s
p.write_text(s.replace('proxy_pass http://127.0.0.1:19880/;','proxy_pass http://127.0.0.1:9880/;'))
PY
nginx -t
systemctl reload nginx.service
systemctl start minecraft.service
trap - ERR
printf 'Installed. Backup: %s\n' "$backup"
