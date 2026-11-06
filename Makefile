# taudplay-libgdx — Taud songs in libGDX, on taudplay-java.
#
#   make            build/taudplay-libgdx.jar
#   make check      the stream against the plain player, bit for bit
#
# Compile-time dependencies (nothing is bundled): taudplay-java, libGDX core
# and, for TaudOpenALMusic, the lwjgl3 backend. The defaults find a sibling
# taudplay-java checkout and Terrarum's lib/; override as needed.

JAVAC   ?= javac
JAR     ?= jar
JAVA    ?= java
RELEASE ?= 17

TAUDPLAY    ?= ../taudplay-java/build/taudplay.jar
GDX         ?= ../Terrarum/lib/gdx-1.12.1.jar
GDX_LWJGL3  ?= ../Terrarum/lib/gdx-backend-lwjgl3-1.12.1.jar
CP = $(TAUDPLAY):$(GDX):$(GDX_LWJGL3)

SRCS := $(wildcard src/net/torvald/taudplay/gdx/*.java)
JARFILE = build/taudplay-libgdx.jar

all: $(JARFILE)

$(JARFILE): $(SRCS)
	rm -rf build/classes
	mkdir -p build/classes
	$(JAVAC) --release $(RELEASE) -encoding UTF-8 -cp $(CP) -d build/classes $(SRCS)
	$(JAR) --create --file $(JARFILE) -C build/classes .
	cd src && $(JAR) --create --file ../build/taudplay-libgdx-sources.jar net

CORPUS ?= ../microtone-taudplay/test/corpus/WHEN.taud

check: $(JARFILE) tests/StreamCheck.java
	mkdir -p build/tests
	$(JAVAC) --release $(RELEASE) -encoding UTF-8 -cp $(CP):$(JARFILE) -d build/tests tests/StreamCheck.java
	$(JAVA) -cp $(CP):$(JARFILE):build/tests StreamCheck $(CORPUS)

clean:
	rm -rf build

.PHONY: all check clean
