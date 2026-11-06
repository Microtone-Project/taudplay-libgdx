/*
 * taudplay-libgdx — a Taud song as a libGDX Music, on any backend.
 *
 * Plays through Gdx.audio.newAudioDevice(): a thread of its own writes what
 * the song's TaudStream renders into the device, which blocks it at the
 * device's pace. Works wherever libGDX has an AudioDevice (desktop, Android,
 * iOS). On the lwjgl3 backend TaudOpenALMusic is the lighter choice (OpenAL
 * streams it like an Ogg); this one is the portable one.
 *
 * Beyond Music, getStream() hands over the song's knobs: the lane faders,
 * the probes and the interrupts.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;
import net.torvald.taudplay.TaudConfig;
import net.torvald.taudplay.TaudException;
import net.torvald.taudplay.TaudPlayer;

public class TaudMusic implements Music {
    private static final int FRAMES = 1024;

    private final TaudStream stream;
    private AudioDevice device;
    private Thread writer;
    private volatile boolean playing, looping, disposed;
    private volatile float volume = 1, pan = 0;
    private volatile OnCompletionListener onCompletion;
    private boolean started;

    public TaudMusic(FileHandle file) { this(file.readBytes(), null); }

    public TaudMusic(byte[] taudFile, TaudConfig cfg) {
        try {
            stream = new TaudStream(new TaudPlayer(taudFile, cfg));
        } catch (TaudException ex) {
            throw new GdxRuntimeException("Cannot open Taud song: " + ex.getMessage(), ex);
        }
    }

    public TaudMusic(TaudStream stream) { this.stream = stream; }

    /** The song's knobs, probes and interrupts. */
    public TaudStream getStream() { return stream; }

    @Override
    public synchronized void play() {
        if (disposed || playing) return;
        if (!started || stream.isEnded()) { stream.play(); started = true; }
        if (device == null) device = Gdx.audio.newAudioDevice(stream.getSampleRate(), false);
        device.setVolume(volume);
        playing = true;
        writer = new Thread(this::writeLoop, "TaudMusic");
        writer.setDaemon(true);
        writer.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
        writer.start();
    }

    private void writeLoop() {
        float[] buf = new float[FRAMES * 2];
        while (playing && !disposed) {
            int got = stream.readInterleaved(buf, 0, FRAMES);
            float p = pan;
            if (p != 0) {
                /* Equal-power balance, -1 (left) … 1 (right). */
                double a = (p + 1) * Math.PI / 4;
                float gl = (float) (Math.cos(a) * Math.sqrt(2)), gr = (float) (Math.sin(a) * Math.sqrt(2));
                for (int i = 0; i < FRAMES; i++) { buf[i * 2] *= Math.min(1f, gl); buf[i * 2 + 1] *= Math.min(1f, gr); }
            }
            device.writeSamples(buf, 0, FRAMES * 2);
            if (got < FRAMES && stream.isEnded()) {
                if (looping) { stream.play(); continue; }
                playing = false;
                OnCompletionListener l = onCompletion;
                if (l != null) {
                    if (Gdx.app != null) Gdx.app.postRunnable(() -> l.onCompletion(this));
                    else l.onCompletion(this);
                }
            }
        }
    }

    private void halt() {
        playing = false;
        Thread t = writer;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        writer = null;
    }

    @Override public synchronized void pause() { halt(); }

    @Override
    public synchronized void stop() {
        halt();
        stream.stop();
        started = false;
    }

    @Override public boolean isPlaying() { return playing; }
    @Override public void setLooping(boolean isLooping) { looping = isLooping; }
    @Override public boolean isLooping() { return looping; }

    @Override
    public void setVolume(float volume) {
        this.volume = volume;
        AudioDevice d = device;
        if (d != null) d.setVolume(volume);
    }

    @Override public float getVolume() { return volume; }

    @Override
    public void setPan(float pan, float volume) {
        this.pan = Math.max(-1f, Math.min(1f, pan));
        setVolume(volume);
    }

    /** Seconds from the top: the song is rendered to there (at many times
     *  real time) and played on from it. */
    @Override
    public void setPosition(float position) {
        stream.playFrom((long) (Math.max(0f, position) * stream.getSampleRate()));
        started = true;
    }

    @Override public float getPosition() { return (float) stream.getFramesRead() / stream.getSampleRate(); }

    @Override
    public synchronized void dispose() {
        halt();
        disposed = true;
        if (device != null) device.dispose();
        device = null;
        stream.dispose();
    }

    @Override public void setOnCompletionListener(OnCompletionListener listener) { onCompletion = listener; }
}
