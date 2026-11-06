#!/bin/sh
# Differential fuzzing: generate a random song per seed, render it through the
# JS library and the Java port, and require bit-identical output.
#   sh tests/fuzz.sh FIRST_SEED LAST_SEED [SECONDS]
# OUT=dir runs a copy in parallel with another (each needs its own).
JS=${JS:-../microtone-taudplay}
NODE=${NODE:-node}
$NODE --no-use-std-math-pow -e 0 2>/dev/null && NODE="$NODE --no-use-std-math-pow"
JAVA=${JAVA:-java}
TAUDPLAY=${TAUDPLAY:-"$JAVA -jar build/taudplay.jar"}
COMPARE="$JAVA -cp build/tests net.torvald.taudplay.Compare"
OUT=${OUT:-tests/out/fuzz}
mkdir -p "$OUT"
first=${1:-1}; last=${2:-20}; secs=${3:-20}
fail=0
s=$first
while [ "$s" -le "$last" ]; do
  f="$OUT/fuzz$s.taud"
  node tests/gen_fuzz.mjs "$JS" "$s" "$f" 2>/dev/null
  n=$($TAUDPLAY "$f" | grep -c '^  \[')
  k=0
  while [ "$k" -lt "$n" ]; do
    for mode in fold binaural; do
      extra=""; [ "$mode" = binaural ] && extra="--binaural"
      $NODE tests/ref_render.mjs "$JS" "$f" "$k" "$secs" "$OUT/r.f32" $mode 2>"$OUT/r.err" || { echo "seed $s song $k ($mode): JS failed: $(tail -1 $OUT/r.err)"; continue; }
      $TAUDPLAY "$f" -s "$k" -t "$secs" --raw "$OUT/j.f32" $extra 2>/dev/null || { echo "seed $s song $k ($mode): Java failed"; fail=1; continue; }
      printf 'seed %s song %s (%s):' "$s" "$k" "$mode"
      $COMPARE "$OUT/r.f32" "$OUT/j.f32" || fail=1
    done
    k=$((k + 1))
  done
  s=$((s + 1))
done
exit $fail
