#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JDK=/usr/lib/jvm/java-25-openjdk/bin
mkdir -p build/classes
# Server libraries are already pinned by the running Paper distribution.
CP=$(find /srv/games/minecraft/libraries -name '*.jar' -print | sort -r | paste -sd:)
CP="$CP:vendor/packetevents-spigot-2.14.0.jar:vendor/JEXT-Reborn_2.2.7.jar:/srv/games/minecraft/plugins/Geyser-Spigot.jar"
"$JDK/javac" -J-Xmx256m --release 25 -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
"$JDK/jar" --create --file build/FeatherDiscos.jar -C build/classes .
printf '%s' "$CP" > build/classpath
sha256sum build/FeatherDiscos.jar
