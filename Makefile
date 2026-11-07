# taudplay-java — portable Java Taud replayer.
#
#   make            build/taudplay.jar (library + CLI: java -jar build/taudplay.jar)
#                   and build/taudplay-sources.jar
#   make check      render the test corpus and compare against the JS library
#
# Java float and double arithmetic is strict IEEE 754 (Java 17+); the engine
# never calls Math.fma or StrictMath, so the output is bit-exact on every JVM.

JAVAC   ?= javac
JAR     ?= jar
JAVA    ?= java
RELEASE ?= 17

SRCS := $(wildcard src/net/torvald/taudplay/*.java)
JARFILE = build/taudplay.jar

all: $(JARFILE)

$(JARFILE): $(SRCS)
	rm -rf build/classes
	mkdir -p build/classes
	$(JAVAC) --release $(RELEASE) -encoding UTF-8 -d build/classes $(SRCS)
	$(JAR) --create --file $(JARFILE) --main-class net.torvald.taudplay.Main -C build/classes .
	cd src && $(JAR) --create --file ../build/taudplay-sources.jar net

# Differential tests against the JavaScript library (needs Node and a
# checkout of microtone-taudplay; JS=path overrides ../microtone-taudplay).
# Current V8's Math.pow is the platform's std::pow unless Node is run with
# --no-use-std-math-pow, which keeps V8's own fdlibm pow, the one JsMath
# carries (the scripts add the flag themselves where Node knows it).
JS ?= ../microtone-taudplay
NODE ?= node
export NODE
NODE_POW := $(shell $(NODE) --no-use-std-math-pow -e 0 >/dev/null 2>&1 && echo --no-use-std-math-pow)

TESTS := $(wildcard tests/*.java)

build/tests/.stamp: $(TESTS) $(JARFILE)
	rm -rf build/tests
	mkdir -p build/tests
	$(JAVAC) --release $(RELEASE) -encoding UTF-8 -cp build/classes -d build/tests $(TESTS)
	touch $@

tests: build/tests/.stamp

check: all tests
	mkdir -p tests/out
	$(NODE) $(NODE_POW) tests/jsmath_ref.mjs 200000 > tests/out/jsmath.bin && \
	    $(JAVA) -cp build/classes:build/tests net.torvald.taudplay.JsMathCheck tests/out/jsmath.bin
	$(NODE) tests/wav_ref.mjs $(JS) tests/out/wav && \
	    $(JAVA) -cp build/classes:build/tests net.torvald.taudplay.WavCheck tests/out/wav
	JS=$(JS) sh tests/run.sh
	JS=$(JS) sh tests/scenario.sh $(JS)/test/corpus/WHEN.taud 48000 0
	JS=$(JS) sh tests/scenario.sh $(JS)/test/corpus/slumberjack.taud 44100 1
	JS=$(JS) sh tests/fuzz.sh 1 12 8
	FUZZ_INSTS=formant node tests/gen_fuzz.mjs $(JS) 3 tests/out/formant.taud
	JS=$(JS) sh tests/scenario.sh tests/out/formant.taud 44100 0
	FUZZ_INSTS=opl node tests/gen_fuzz.mjs $(JS) 6 tests/out/opl.taud
	JS=$(JS) sh tests/scenario.sh tests/out/opl.taud 22050 1
	FUZZ_INSTS=psg node tests/gen_fuzz.mjs $(JS) 5 tests/out/psg.taud
	JS=$(JS) sh tests/scenario.sh tests/out/psg.taud 32000 0
	FUZZ_INSTS=formant FUZZ_LYRIC=1 node tests/gen_fuzz.mjs $(JS) 7 tests/out/lyric.taud
	JS=$(JS) sh tests/scenario.sh tests/out/lyric.taud 48000 1
	FUZZ_INSTS=formant FUZZ_LYRIC=1 JS=$(JS) sh tests/fuzz.sh 1 4 8
	FUZZ_INSTS=formant FUZZ_S9=1 JS=$(JS) sh tests/fuzz.sh 1 4 8
	FUZZ_CUTS=1 FUZZ_INSTS=layered JS=$(JS) sh tests/fuzz.sh 1 4 8

clean:
	rm -rf build tests/out

.PHONY: all tests check clean
