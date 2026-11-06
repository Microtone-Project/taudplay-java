#!/bin/sh
# API scenario: JS TaudRenderer vs the Java API, same calls in the same order.
#   sh tests/scenario.sh FILE [RATE] [BINAURAL]
JS=${JS:-../microtone-taudplay}
NODE=${NODE:-node}
$NODE --no-use-std-math-pow -e 0 2>/dev/null && NODE="$NODE --no-use-std-math-pow"
JAVA=${JAVA:-java}
CP="build/classes:build/tests"
OUT=${OUT:-tests/out/scen}
mkdir -p "$OUT"
f=$1; rate=${2:-48000}; bin=${3:-0}
$NODE tests/scenario_ref.mjs "$JS" "$f" "$rate" "$bin" "$OUT/js" || exit 1
$JAVA -cp $CP net.torvald.taudplay.Scenario "$f" "$rate" "$bin" "$OUT/j" 0 || exit 1
cp "$OUT/j.log" "$OUT/j0.log"
$JAVA -cp $CP net.torvald.taudplay.Scenario "$f" "$rate" "$bin" "$OUT/j" 1 || exit 1
status=0
printf 'float:'; $JAVA -cp $CP net.torvald.taudplay.Compare "$OUT/js.f32" "$OUT/j.f32" || status=1
if cmp -s "$OUT/js.u8" "$OUT/j.u8"; then echo "u8: identical ($(wc -c < "$OUT/j.u8") bytes)"; else echo "u8: DIFFER"; status=1; fi
if cmp -s "$OUT/js.log" "$OUT/j0.log" && cmp -s "$OUT/js.log" "$OUT/j.log"; then echo "log: identical ($(wc -l < "$OUT/j.log") lines, $(grep -c '^int' "$OUT/j.log") interrupts)"; else echo "log: DIFFER"; diff "$OUT/js.log" "$OUT/j0.log" | head -5; status=1; fi
exit $status
