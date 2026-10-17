#!/usr/bin/env bash
set -euo pipefail
[[ ${1:-} == --restart && $EUID == 0 ]] || { echo 'Use sudo -n ./ops/rollback.sh --restart'; exit 2; }
project=$(cd "$(dirname "$0")/.." && pwd)
backup=$(cat "$project/ops/last-backup.txt")
[[ $backup == /srv/games/minecraft-plugin-backups/featherdiscos-* && -f "$backup/URLCustomDiscs-v2.7.2-1.21-1.21.8.jar" ]]
mc=/srv/games/minecraft
systemctl stop minecraft.service
mkdir -p "$backup/replacement-at-rollback"
for name in FeatherDiscos.jar JEXT-Reborn_2.2.7.jar packetevents-spigot-2.14.0.jar FeatherDiscos JukeboxExtendedReborn; do
    if [[ -e "$mc/plugins/$name" ]]; then mv "$mc/plugins/$name" "$backup/replacement-at-rollback/"; fi
done
cp -a "$backup/URLCustomDiscs-v2.7.2-1.21-1.21.8.jar" "$backup/URLCustomDiscs" "$mc/plugins/"
cp -a "$backup/customdiscs.sk" "$mc/plugins/Skript/scripts/customdiscs.sk"
chown minecraft:minecraft "$mc/plugins/Skript/scripts/customdiscs.sk"
chown -R minecraft:minecraft "$mc/plugins/URLCustomDiscs" "$mc/plugins/URLCustomDiscs-v2.7.2-1.21-1.21.8.jar"
# Restore the exact pre-task nginx configuration (wiki remains available).
cp "$project/ops/nginx.conf.before" /etc/nginx/nginx.conf
nginx -t
systemctl reload nginx.service
systemctl start minecraft.service
printf 'Original plugin restored. New data preserved in %s\n' "$backup/replacement-at-rollback"
