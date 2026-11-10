# Host test de `.pueblo` (spec 008 slice 2).
# Compila las clases REALES PuebloProfile + SaveManager contra stubs de Android
# y el jar org.json de la caché de Gradle. No necesita emulador ni POCO.
$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$out = Join-Path $PSScriptRoot "java-build"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$jsonJar = Get-ChildItem -Recurse `
    (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\org.json") `
    -Filter "json-*.jar" -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $jsonJar) {
    # Fallback vendoreado (2026-11-07): la caché de Gradle ya no trae org.json porque
    # la app usa el del framework Android. El jar vive en tools/host-test/lib/.
    $jsonJar = Get-ChildItem (Join-Path $PSScriptRoot "lib") -Filter "json-*.jar" `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jsonJar) {
    throw "No se encontró org.json ni en la caché de Gradle ni en tools/host-test/lib/."
}

$javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path $javac)) { $javac = "javac" }
if (-not (Test-Path $java)) { $java = "java" }

$stubs = Get-ChildItem -Recurse (Join-Path $PSScriptRoot "java\stub") -Filter *.java |
    Select-Object -ExpandProperty FullName
$sources = @($stubs) + @(
    (Join-Path $root "app\src\main\java\com\gpsquest96\emulator\PuebloProfile.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\emulator\SaveManager.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\gps\GeofenceManager.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\gps\OsmAccumulator.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\gps\OsmDataFetcher.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\route\RouteFormatException.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\route\RouteNode.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\route\Route.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\route\RouteGenerator.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\route\RouteValidator.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\route\RouteBundle.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\render\OsmRasterGrid.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\render\SpriteExtractor.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\render\TileAtlas.java"),
    (Join-Path $root "app\src\main\java\com\gpsquest96\emulator\core\EmulationPipeline.java"),
    (Join-Path $PSScriptRoot "java\test\hosttest\PuebloHostTest.java"),
    (Join-Path $PSScriptRoot "java\test\hosttest\GeofenceHostTest.java"),
    (Join-Path $PSScriptRoot "java\test\com\gpsquest96\gps\OsmClassifyHostTest.java"),
    (Join-Path $PSScriptRoot "java\test\hosttest\RouteHostTest.java"),
    (Join-Path $PSScriptRoot "java\test\hosttest\RouteValidatorHostTest.java"),
    (Join-Path $PSScriptRoot "java\test\hosttest\RouteBundleHostTest.java"),
    (Join-Path $PSScriptRoot "java\test\hosttest\RasterGridHostTest.java"),
    (Join-Path $PSScriptRoot "java\test\hosttest\SpriteExtractorHostTest.java")
)

& $javac -encoding UTF-8 -cp $jsonJar -d $out $sources
if ($LASTEXITCODE -ne 0) {
    throw "La compilación de los host tests de Java falló."
}

& $java -cp "$out;$jsonJar" hosttest.PuebloHostTest
$code = $LASTEXITCODE

if ($code -eq 0) {
    & $java -cp "$out;$jsonJar" hosttest.GeofenceHostTest
    $code = $LASTEXITCODE
}

if ($code -eq 0) {
    & $java -cp "$out;$jsonJar" com.gpsquest96.gps.OsmClassifyHostTest
    $code = $LASTEXITCODE
}

if ($code -eq 0) {
    & $java -cp "$out;$jsonJar" hosttest.RouteHostTest
    $code = $LASTEXITCODE
}

if ($code -eq 0) {
    & $java -cp "$out;$jsonJar" hosttest.RouteValidatorHostTest
    $code = $LASTEXITCODE
}

if ($code -eq 0) {
    & $java -cp "$out;$jsonJar" hosttest.RouteBundleHostTest
    $code = $LASTEXITCODE
}

if ($code -eq 0) {
    & $java -cp "$out;$jsonJar" hosttest.RasterGridHostTest
    $code = $LASTEXITCODE
}

if ($code -eq 0) {
    & $java -cp "$out;$jsonJar" hosttest.SpriteExtractorHostTest
    $code = $LASTEXITCODE
}

if ($code -eq 0) {
    Remove-Item -Recurse -Force $out
}
exit $code
