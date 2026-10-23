#!/bin/sh
# Compiles and runs the weather tests against the real production sources, using only what the
# offline Android build already has: the Kotlin compiler jar from the Gradle cache and the JDK in
# android-runtime. No network, no JUnit, no extra downloads.
#
#   sh tools/weather-budget-test/run.sh
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME=${ANDROID_RUNTIME:-"$ROOT/../android-runtime"}
JAVA_HOME=${JAVA_HOME:-"$RUNTIME/jdk-17.0.20.1+1/Contents/Home"}
CACHE="$RUNTIME/gradle-user/caches/modules-2/files-2.1"
OUT="$ROOT/tools/weather-budget-test/build"

KOTLIN_COMPILER=$(ls "$CACHE"/org.jetbrains.kotlin/kotlin-compiler-embeddable/*/*/kotlin-compiler-embeddable-*.jar | sort | tail -1)
# The stdlib matching the compiler, so the two never disagree.
STDLIB_VERSION=$(basename "$KOTLIN_COMPILER" .jar | sed "s/kotlin-compiler-embeddable-//")
KOTLIN_STDLIB=$(ls "$CACHE"/org.jetbrains.kotlin/kotlin-stdlib/"$STDLIB_VERSION"/*/kotlin-stdlib-"$STDLIB_VERSION".jar | head -1)
# The compiler itself is a Gradle dependency and needs coroutines on its own classpath.
COROUTINES=$(ls "$CACHE"/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/kotlinx-coroutines-core-jvm-*.jar | sort | tail -1)
ANNOTATIONS=$(ls "$CACHE"/org.jetbrains/annotations/*/*/annotations-*.jar | sort -V | tail -1)

if [ -z "$KOTLIN_COMPILER" ] || [ -z "$KOTLIN_STDLIB" ]; then
  echo "Kotlin compiler or stdlib not found in $CACHE" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

echo "compiler: $KOTLIN_COMPILER"
echo "stdlib:   $KOTLIN_STDLIB"

"$JAVA_HOME/bin/java" -cp "$KOTLIN_COMPILER:$KOTLIN_STDLIB:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-reflect -no-stdlib -nowarn -jvm-target 17 \
  -cp "$KOTLIN_STDLIB" \
  -d "$OUT" \
  "$ROOT/app/src/main/java/app/orchard/tv/weather/WeatherModels.kt" \
  "$ROOT/app/src/main/java/app/orchard/tv/weather/WeatherState.kt" \
  "$ROOT/app/src/main/java/app/orchard/tv/weather/WeatherBudget.kt" \
  "$ROOT/app/src/main/java/app/orchard/tv/weather/LocationParser.kt" \
  "$ROOT/tools/weather-budget-test/WeatherBudgetTest.kt"

"$JAVA_HOME/bin/java" -cp "$OUT:$KOTLIN_STDLIB" WeatherBudgetTestKt
