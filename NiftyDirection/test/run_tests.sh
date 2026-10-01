#!/bin/bash
# JVM tests: engine scenarios, forecaster, end-to-end collector and forecast runner against a local mock Kite server.
set -e
cd "$(dirname "$0")"
S=$(mktemp -d); SRC=../src/com/krish/niftydirection
javac -nowarn -encoding UTF-8 -d $S $(find shim -name "*.java") $(find $SRC/model $SRC/engine $SRC/forecast -name "*.java") $(ls $SRC/data/*.java | grep -v -E "Prefs|Brain") EngineTest.java CollectorTest.java  ForecastTest.java ForecastRunnerTest.java 2>&1 | grep -v -E "JAVA_TOOL|deprecat|^Note" || true
echo "Engine:";    java -cp $S EngineTest 2>&1 | grep -E "passed|FAIL"
echo "Forecast (1H/3H/6H/1D):"; java -cp $S ForecastTest 2>&1 | grep -E " passed|FAIL"
DAY=$(TZ=Asia/Kolkata date +%F)
python3 mock_kite.py 18778 $DAY & PID=$!; sleep 1
echo "Collector:"; java -cp $S CollectorTest 18778 $DAY $S/data 2>&1 | grep -E "passed|FAIL"
kill $PID
python3 mock_kite.py 18779 $DAY replay & PID=$!; sleep 1
echo "Forecast runner:"; java -cp $S ForecastRunnerTest 18779 $DAY $S/fc 2>&1 | tail -1
kill $PID
