# taudplay-libgdx

<p align="center"><em>You are currently looking at the LibGDX version.</em></p>
<p align="center"><a href="https://github.com/Microtone-Project/microtone-taudplay">JavaScript</a> · <a href="https://github.com/Microtone-Project/libtaudplay">C99</a> · <a href="https://github.com/Microtone-Project/taudplay-java">Java</a> · <a href="https://github.com/Microtone-Project/taudplay-godot">Godot 4</a> · LibGDX</p>

---

**Play `.taud` songs in a libGDX game.**

[Microtone](https://microtone.cc) songs as libGDX music, on
[taudplay-java](https://github.com/Microtone-Project/taudplay-java) — the
tracker's own engine, sample for sample. Load a song like an Ogg, and keep its
knobs: a fader per lane, two probes per lane, and the sixteen interrupts the
song fires at the game playing it, all in time with what the player hears.

| | |
|---|---|
| **`TaudStream`** | a song rendered ahead on a thread of its own, read from any other: the piece everything else is built on, and the one to use when your game mixes its own audio |
| **`TaudOpenALMusic`** | a `Music` the lwjgl3 backend streams through OpenAL like an Ogg or a WAV |
| **`TaudMusic`** | a `Music` over `Gdx.audio.newAudioDevice()`: any backend (desktop, Android, iOS) |
| **`TaudGdx`** | the wiring: `registerOpenAL()`, `newMusic(file)`, `streamOf(music)` |

## Build

```
make            # build/taudplay-libgdx.jar and build/taudplay-libgdx-sources.jar
make check      # the stream against the plain player, bit for bit
```

JDK 17 or later. Compile-time dependencies, none of them bundled:
taudplay-java (`TAUDPLAY=…/taudplay.jar`, by default a sibling checkout's
`build/taudplay.jar`), libGDX core (`GDX=…`) and, for `TaudOpenALMusic`, the
lwjgl3 backend (`GDX_LWJGL3=…`); built and tested against libGDX 1.12.1. At run
time the game needs taudplay-java beside this jar; the lwjgl3 backend only if
it uses `TaudOpenALMusic`.

## Play something

```java
TaudGdx.registerOpenAL();                       // once, after the backend is up: teaches it ".taud"
Music music = Gdx.audio.newMusic(Gdx.files.internal("music/theme.taud"));
music.setLooping(true);
music.play();

TaudStream song = TaudGdx.streamOf(music);      // the knobs
song.setVoiceGain(3, 0.0, 2.0);                 // fade lane 4 out over 2 s
```

`TaudGdx.newMusic(file)` picks for you: the OpenAL kind on the lwjgl3
backend, the `AudioDevice` kind anywhere else.

### Mixing it yourself

A game with its own mixer reads the stream directly, in whatever sizes its
callback asks for:

```java
TaudStream song = new TaudStream(new TaudPlayer(bytes));   // starts rendering on its own thread
song.play();

// on the audio thread:
int got = song.read(left, right, 0, frames);   // planar float; readInterleaved, readS16LE too
if (got == 0 && song.isEnded()) { /* the song is over */ }
```

A read past the song's end, or one that runs dry because the render thread
fell behind (an underrun, counted by `getUnderruns()`), is silence; `read`
returns how many frames were song. `isEnded()` is true once the song has halted
and everything it rendered has been read — never in the moment between
`play()` and the render thread running it.

### What it does with time

The render thread keeps `lead` frames ready (8192 by default, 171 ms at
48 kHz) in a ring of 128-frame blocks; a read waits up to 50 ms for it before
it gives up (`setMaxWait`, or `Long.MAX_VALUE` for offline rendering). Every
change to the player runs on the render thread, between blocks — the two
threads never touch the player at once — so:

- a **transport change** (`play`, `stop`, `seekCue`, `playFrom`, `selectSong`)
  throws the ring away and is heard at once;
- a **fader or volume change** (`setVoiceGain`, `setVoiceGains`, `setVolume`)
  takes effect at the render head, about `lead` frames after what is being
  heard — give it a fade and nobody will hear the difference;
- anything else goes through `runOnRenderThread(player -> …)`.

Each block carries what the song did while it was made, so what the reader
sees follows what it hears, not the render head: an **interrupt**
(`setInterrupt(n, listener)`) is delivered on the reading thread, from inside
the read that reaches the block it fired in, and the **probes**
(`getVoiceVolume`, `getVoicePan`, `getCue`, `getRow`, `getBpm`, `getSpeed`)
describe the end of the last block read.

`new TaudStream(player, lead, false)` renders on the reader's own thread
instead, on demand: no latency, no thread, and every command runs at once on
the calling thread (which must then keep the two apart itself).

### The two Musics

`TaudOpenALMusic` extends the lwjgl3 backend's `OpenALMusic`, so OpenAL
streams it exactly as it streams an Ogg: 16-bit stereo at the song's engine
rate, looping and completion included. `TaudMusic` is the portable one: a
thread of its own writes the stream into an `AudioDevice`, which paces it;
`setPan` is an equal-power pan, `setPosition` replays to the position at many
times real time, and the completion listener runs on the render loop
(`Gdx.app.postRunnable`). Both hand over their stream through `getStream()`
(or `TaudGdx.streamOf`).

## Verification

`make check` runs `tests/StreamCheck` on a song (`CORPUS=…`, by default the
JS library's `test/corpus/WHEN.taud`): read through a threaded stream and an
unthreaded one in odd sizes, the song must be `TaudPlayer.renderChunk`'s output
bit for bit; every interrupt must reach the read holding the first frame of
the block it fired in; a fader set through the stream must sound as one set
on the player; and a read straight after `play()` must never report the song
over.

## Licence

LGPL-3.0-or-later — see `COPYING.LESSER` (and `COPYING` for the GPL text it
builds on). You may use this library from a proprietary game; changes *to the
library itself* must be shared. Where the LGPL does not fit, it is also
available under a proprietary licence, as taudplay-java is: write to
<microtone@curioustorvald.com>.

---

Songs are made with **Microtone** — a tracker for the notes a piano cannot
play, free and in your browser at [microtone.cc](https://microtone.cc).
