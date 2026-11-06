/*
 * taudplay-libgdx — Taud songs in libGDX.
 *
 * TaudStream: a TaudPlayer rendered ahead on a thread of its own, read from
 * another (an audio callback, OpenAL's update, a mixer). The two never touch
 * the player at the same time: everything that changes it — the transport,
 * the faders, the master volume — is queued and run on the render thread,
 * between blocks.
 *
 * The render thread keeps `lead` frames ready in a ring of CHUNK-frame
 * blocks. Each block carries what the song did while it was made — the
 * interrupts it fired and the probes at its end — so a reader hears an
 * interrupt, and reads a lane's volume and pan, IN TIME WITH THE MUSIC it is
 * hearing rather than `lead` frames early.
 *
 * Latency of a change: a fader move or a volume change takes effect at the
 * render head, about `lead` frames after the reader's position. A transport
 * change (play, stop, seek, a song change) throws the ring away and is heard
 * at once.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay.gdx;

import com.badlogic.gdx.utils.Disposable;
import net.torvald.taudplay.TaudException;
import net.torvald.taudplay.TaudInterruptListener;
import net.torvald.taudplay.TaudPlayer;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public final class TaudStream implements Disposable {
    /** Frames the render thread keeps ready by default: 8192 (171 ms at 48 kHz). */
    public static final int DEFAULT_LEAD = 8192;

    private static final int CHUNK = TaudPlayer.CHUNK;
    private static final int LANES = TaudPlayer.MAX_VOICES;
    private static final int NINT = TaudPlayer.NUM_INTERRUPTS;

    /** One rendered block and what the song did while it was made. */
    private static final class Block {
        final float[] pcm = new float[CHUNK * 2];
        int interruptMask;
        final int[] interruptArgs = new int[NINT];
        final double[] volume = new double[LANES], pan = new double[LANES];
        int cue, row, bpm, speed;
        long frameAt;           /* stream frame of the block's first sample */
    }

    private final TaudPlayer player;
    private final boolean threaded;
    private final Block[] ring;
    private final Object lock = new Object();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final TaudInterruptListener[] listeners = new TaudInterruptListener[NINT];
    private final Thread thread;

    /* Ring state, under `lock`. */
    private int head, count, headPos;
    private boolean ended = true;       /* the song has halted (or never started) and nothing is left to read */
    private boolean renderEnded = true; /* the render thread has seen the song halt */
    private volatile boolean disposed;
    private int transportPending;        /* transport commands queued but not yet run */
    private long framesRead, underruns;
    private long maxWaitNanos = 50_000_000L;

    /* What the reader has heard, under `lock`. */
    private final double[] heardVolume = new double[LANES], heardPan = new double[LANES];
    private int heardCue, heardRow, heardBpm, heardSpeed;

    /* Render-thread scratch: the block being made. */
    private Block making;
    private long framesMade;

    /** A stream with a render thread keeping DEFAULT_LEAD frames ready. */
    public TaudStream(TaudPlayer player) { this(player, DEFAULT_LEAD, true); }

    /**
     * @param player   the player to stream; once given to a stream, touch it
     *                 only through {@link #runOnRenderThread}
     * @param lead     frames to keep ready (rounded up to whole blocks)
     * @param threaded false renders on the READER's thread, on demand: no
     *                 latency, and every command runs at once on the calling
     *                 thread (the caller keeps the two apart)
     */
    public TaudStream(TaudPlayer player, int lead, boolean threaded) {
        this.player = player;
        this.threaded = threaded;
        int blocks = Math.max(2, (lead + CHUNK - 1) / CHUNK);
        ring = new Block[threaded ? blocks : 1];
        for (int i = 0; i < ring.length; i++) ring[i] = new Block();
        for (int n = 0; n < NINT; n++) {
            final int k = n;
            player.setInterrupt(n, (i, arg) -> {
                Block b = making;
                if (b != null) { b.interruptMask |= 1 << k; b.interruptArgs[k] = arg; }
            });
        }
        java.util.Arrays.fill(heardPan, 0.5);
        if (threaded) {
            thread = new Thread(this::renderLoop, "TaudStream");
            thread.setDaemon(true);
            thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
            thread.start();
        } else {
            thread = null;
        }
    }

    /** A stream of a .taud file's first song at 48 kHz, threaded. */
    public static TaudStream open(byte[] taudFile) throws TaudException {
        return new TaudStream(new TaudPlayer(taudFile));
    }

    public TaudPlayer getPlayer() { return player; }
    public int getSampleRate() { return player.getSampleRate(); }
    public boolean isThreaded() { return threaded; }

    /** How long a read waits for the render thread before it gives up and
     *  reads silence (an underrun). 50 ms by default; Long.MAX_VALUE waits
     *  for as long as it takes (offline rendering). */
    public void setMaxWait(long nanos) { maxWaitNanos = nanos; }

    /* ── commands ── */

    /** Run `job` on the render thread, between blocks — the only safe place
     *  to touch the player while the stream is threaded. */
    public void runOnRenderThread(Consumer<TaudPlayer> job) {
        submit(() -> job.accept(player));
    }

    private void submit(Runnable r) {
        if (!threaded) { synchronized (lock) { r.run(); } return; }
        commands.add(r);
        synchronized (lock) { lock.notifyAll(); }
    }

    /* A transport command: until the render thread has run it, a read waits
     * for it instead of reporting the old state (a play() followed at once by
     * a read must not read "ended"). */
    private void submitTransport(Runnable r) {
        if (!threaded) { synchronized (lock) { r.run(); } return; }
        synchronized (lock) { transportPending++; }
        commands.add(() -> {
            try { r.run(); }
            finally { synchronized (lock) { transportPending--; lock.notifyAll(); } }
        });
        synchronized (lock) { lock.notifyAll(); }
    }

    /** Drop everything buffered: called on the render thread (or, unthreaded,
     *  the caller's) by a transport command. */
    private void flush() {
        synchronized (lock) {
            head = 0;
            count = 0;
            headPos = 0;
            renderEnded = !player.isPlaying();
            ended = renderEnded;
            framesRead = 0;
            framesMade = 0;
            lock.notifyAll();
        }
    }

    /** Play the song from the top. Heard at once. */
    public void play() { submitTransport(() -> { player.play(); flush(); }); }

    /** Stop. Heard at once; isEnded() turns true. */
    public void stop() { submitTransport(() -> { player.stop(); flush(); }); }

    /** Jump to a cue. Heard at once. */
    public void seekCue(int cue) { submitTransport(() -> { player.seekCue(cue); flush(); }); }

    /** Play from the top, then skip `frames` frames (rendered and thrown away
     *  on the render thread, at many times real time). Heard at once. */
    public void playFrom(long frames) {
        submitTransport(() -> {
            player.play();
            long left = frames;
            while (left > 0 && player.renderChunk() != null) left -= CHUNK;
            flush();
            framesRead = frames - Math.max(0, left);
            framesMade = framesRead;
        });
    }

    /** Load another song of the file, stopped. */
    public void selectSong(int index) {
        submitTransport(() -> {
            try { player.selectSong(index); } catch (TaudException ex) { /* out of range: nothing changes */ }
            flush();
        });
    }

    /** Lane `v`'s fader: 1 = as written, 0 = silent, faded over `fadeSeconds`
     *  of rendered time. Takes effect at the render head. */
    public void setVoiceGain(int v, double gain, double fadeSeconds) {
        submit(() -> player.setVoiceGain(v, gain, fadeSeconds));
    }

    /** Every lane's fader at once (gains.length lanes from lane 0). */
    public void setVoiceGains(double[] gains, double fadeSeconds) {
        final double[] g = gains.clone();
        submit(() -> { for (int v = 0; v < g.length; v++) player.setVoiceGain(v, g[v], fadeSeconds); });
    }

    /** Master volume, 0…1. */
    public void setVolume(double gain) { submit(() -> player.setVolume(gain)); }

    /** Register (null unregisters) interrupt `n`'s listener. It runs on the
     *  READING thread, from inside a read, when the reader reaches the block
     *  the interrupt fired in — in time with what is heard. */
    public void setInterrupt(int n, TaudInterruptListener listener) {
        if (n < 0 || n >= NINT) return;
        synchronized (lock) { listeners[n] = listener; }
    }

    /* ── the render thread ── */

    private boolean renderOne(Block b) {
        making = b;
        b.interruptMask = 0;
        float[] pcm = player.renderChunk();
        making = null;
        if (pcm == null) return false;
        System.arraycopy(pcm, 0, b.pcm, 0, CHUNK * 2);
        for (int v = 0; v < LANES; v++) {
            b.volume[v] = player.getVoiceVolume(v);
            b.pan[v] = player.getVoicePan(v);
        }
        b.cue = player.getCue();
        b.row = player.getRow();
        b.bpm = player.getBpm();
        b.speed = player.getSpeed();
        b.frameAt = framesMade;
        framesMade += CHUNK;
        return true;
    }

    private void renderLoop() {
        while (!disposed) {
            Runnable r;
            while ((r = commands.poll()) != null) r.run();
            Block slot;
            synchronized (lock) {
                if ((count == ring.length || renderEnded) && commands.isEmpty() && !disposed) {
                    try { lock.wait(); } catch (InterruptedException ex) { return; }
                    continue;
                }
                if (count == ring.length || renderEnded) continue;
                slot = ring[(head + count) % ring.length];
            }
            boolean ok = renderOne(slot);
            synchronized (lock) {
                if (ok) count++;
                else renderEnded = true;
                lock.notifyAll();
            }
        }
    }

    /* ── the reader ── */

    private interface Sink { void put(float[] pcm, int from, int frames, int at); }

    /** Pull `frames` frames through `sink`; returns the frames that were song. */
    private int pull(int frames, Sink sink) {
        int done = 0;
        int fired = 0;
        int[] firedArgs = null;
        TaudInterruptListener[] ls = null;
        synchronized (lock) {
            boolean forever = maxWaitNanos >= Long.MAX_VALUE / 4;
            long deadline = forever ? 0 : System.nanoTime() + maxWaitNanos;
            while (done < frames) {
                if (count == 0) {
                    if (!threaded) {
                        if (renderEnded) break;
                        Block b = ring[0];
                        if (renderOne(b)) { head = 0; count = 1; headPos = 0; }
                        else { renderEnded = true; break; }
                    } else {
                        if (renderEnded && transportPending == 0) break;
                        long wait = forever ? 1_000_000_000L : deadline - System.nanoTime();
                        if (wait <= 0) { underruns++; break; }
                        try { lock.wait(Math.max(1, wait / 1_000_000L)); } catch (InterruptedException ex) { break; }
                        continue;
                    }
                }
                Block b = ring[head];
                if (headPos == 0 && b.interruptMask != 0) {
                    if (firedArgs == null) firedArgs = new int[NINT];
                    for (int n = 0; n < NINT; n++) {
                        if ((b.interruptMask & (1 << n)) != 0) { fired |= 1 << n; firedArgs[n] = b.interruptArgs[n]; }
                    }
                }
                int take = Math.min(CHUNK - headPos, frames - done);
                sink.put(b.pcm, headPos, take, done);
                headPos += take;
                done += take;
                framesRead += take;
                if (headPos == CHUNK) {
                    System.arraycopy(b.volume, 0, heardVolume, 0, LANES);
                    System.arraycopy(b.pan, 0, heardPan, 0, LANES);
                    heardCue = b.cue; heardRow = b.row; heardBpm = b.bpm; heardSpeed = b.speed;
                    headPos = 0;
                    head = (head + 1) % ring.length;
                    count--;
                    lock.notifyAll();
                }
            }
            ended = count == 0 && renderEnded && transportPending == 0;
            if (fired != 0) ls = listeners.clone();
        }
        if (fired != 0) {
            for (int n = 0; n < NINT; n++) {
                if ((fired & (1 << n)) != 0 && ls[n] != null) ls[n].onInterrupt(n, firedArgs[n]);
            }
        }
        return done;
    }

    /** Read `frames` frames into two planar buffers. Frames past the song's
     *  end (or an underrun) are silence. Returns the frames that were song. */
    public int read(float[] left, float[] right, int off, int frames) {
        int got = pull(frames, (pcm, from, n, at) -> {
            for (int k = 0; k < n; k++) {
                left[off + at + k] = pcm[(from + k) * 2];
                right[off + at + k] = pcm[(from + k) * 2 + 1];
            }
        });
        if (got < frames) {
            java.util.Arrays.fill(left, off + got, off + frames, 0f);
            java.util.Arrays.fill(right, off + got, off + frames, 0f);
        }
        return got;
    }

    /** Read `frames` frames of interleaved stereo float. As read(). */
    public int readInterleaved(float[] dst, int off, int frames) {
        int got = pull(frames, (pcm, from, n, at) -> System.arraycopy(pcm, from * 2, dst, off + at * 2, n * 2));
        if (got < frames) java.util.Arrays.fill(dst, off + got * 2, off + frames * 2, 0f);
        return got;
    }

    /** Read `frames` frames as interleaved signed 16-bit little-endian bytes
     *  (4 bytes a frame), clamped and rounded as the library's s16 writer.
     *  As read(). */
    public int readS16LE(byte[] dst, int off, int frames) {
        int got = pull(frames, (pcm, from, n, at) -> {
            for (int k = 0; k < n * 2; k++) {
                float v = pcm[from * 2 + k];
                double d = (v < -1.0f ? -1.0 : v > 1.0f ? 1.0 : (double) v) * 32767;
                double r = Math.floor(d);
                if (d - r >= 0.5) r += 1.0;
                int s = (int) r;
                int o = off + (at * 2 + k) * 2;
                dst[o] = (byte) s;
                dst[o + 1] = (byte) (s >> 8);
            }
        });
        if (got < frames) java.util.Arrays.fill(dst, off + got * 4, off + frames * 4, (byte) 0);
        return got;
    }

    /* ── what the reader hears ── */

    /** The song has halted and everything it rendered has been read (or it
     *  was stopped, or never played). False from the moment play() (or another
     *  transport change) is called until the render thread has run it. */
    public boolean isEnded() { synchronized (lock) { return ended && transportPending == 0; } }

    /** Frames read since the last transport change. */
    public long getFramesRead() { synchronized (lock) { return framesRead; } }

    /** Reads that ran dry before the render thread caught up. */
    public long getUnderruns() { synchronized (lock) { return underruns; } }

    /** Lane `v`'s volume (0…1) at the end of the last block READ. */
    public double getVoiceVolume(int v) {
        if (v < 0 || v >= LANES) return 0;
        synchronized (lock) { return heardVolume[v]; }
    }

    /** Lane `v`'s pan (0 left … 0.5 … 1 right) at the end of the last block READ. */
    public double getVoicePan(int v) {
        if (v < 0 || v >= LANES) return 0.5;
        synchronized (lock) { return heardPan[v]; }
    }

    public int getCue() { synchronized (lock) { return heardCue; } }
    public int getRow() { synchronized (lock) { return heardRow; } }
    public int getBpm() { synchronized (lock) { return heardBpm; } }
    public int getSpeed() { synchronized (lock) { return heardSpeed; } }

    @Override
    public void dispose() {
        disposed = true;
        synchronized (lock) { lock.notifyAll(); }
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
}
