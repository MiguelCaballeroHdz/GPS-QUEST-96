#!/bin/sh
# Host test de Java (spec 004 y resto): equivalente en Linux de run-java.ps1.
# Compila las clases REALES contra stubs de Android y el jar org.json
# vendoreado en lib/. No necesita emulador, POCO ni Gradle.
# Uso: sh tools/host-test/run-java.sh   (sale con 1 al primer test que falle)
set -e

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT="$ROOT/tools/host-test/java-build"
rm -rf "$OUT"
mkdir -p "$OUT"

JSON_JAR="$ROOT/tools/host-test/lib/json-20231013.jar"
if [ ! -f "$JSON_JAR" ]; then
    echo "No se encontró org.json en tools/host-test/lib/." >&2
    exit 1
fi

JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}

# shellcheck disable=SC2046
$JAVAC -encoding UTF-8 -cp "$JSON_JAR" -d "$OUT" \
    $(find "$ROOT/tools/host-test/java/stub" -name '*.java') \
    "$ROOT/app/src/main/java/com/gpsquest96/emulator/PuebloProfile.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/emulator/SaveManager.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/gps/GeofenceManager.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/gps/OsmAccumulator.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/gps/OsmDataFetcher.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/render/IllustratedMap.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/route/RouteFormatException.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/route/RouteNode.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/route/Route.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/route/RouteGenerator.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/route/RouteValidator.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/route/RouteProgress.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/route/RouteAutoAction.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/route/RouteBundle.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/render/SpriteExtractor.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/avatar/AvatarData.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/avatar/AvatarSprites.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/render/TileAtlas.java" \
    "$ROOT/app/src/main/java/com/gpsquest96/emulator/core/EmulationPipeline.java" \
    "$ROOT/tools/host-test/java/test/hosttest/PuebloHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/GeofenceHostTest.java" \
    "$ROOT/tools/host-test/java/test/com/gpsquest96/gps/OsmClassifyHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/RouteHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/RouteValidatorHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/RouteProgressHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/RouteEscapeHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/RouteAutoActionHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/RouteBundleHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/IllustratedMapHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/SpriteExtractorHostTest.java" \
    "$ROOT/tools/host-test/java/test/hosttest/AvatarSpritesHostTest.java"

CP="$OUT:$JSON_JAR"
$JAVA -cp "$CP" hosttest.PuebloHostTest
$JAVA -cp "$CP" hosttest.GeofenceHostTest
$JAVA -cp "$CP" com.gpsquest96.gps.OsmClassifyHostTest
$JAVA -cp "$CP" hosttest.RouteHostTest
$JAVA -cp "$CP" hosttest.RouteValidatorHostTest
$JAVA -cp "$CP" hosttest.RouteProgressHostTest
$JAVA -cp "$CP" hosttest.RouteEscapeHostTest
$JAVA -cp "$CP" hosttest.RouteAutoActionHostTest
$JAVA -cp "$CP" hosttest.RouteBundleHostTest
$JAVA -cp "$CP" hosttest.IllustratedMapHostTest
$JAVA -cp "$CP" hosttest.SpriteExtractorHostTest
$JAVA -cp "$CP" hosttest.AvatarSpritesHostTest

rm -rf "$OUT"
echo "HOST TESTS JAVA OK"
