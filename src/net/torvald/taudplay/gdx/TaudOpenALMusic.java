/*
 * taudplay-libgdx — a Taud song as an OpenAL-streamed Music on the lwjgl3
 * backend, the way libGDX streams an Ogg or a WAV.
 *
 * Register it once (TaudGdx.registerOpenAL()) and Gdx.audio.newMusic() opens
 * a .taud file like any other music file — and so does anything built on
 * the backend's own OpenALMusic.read(byte[]) (16-bit stereo at the song's
 * engine rate, 48 kHz). getStream() hands over the song's knobs.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay.gdx;

import com.badlogic.gdx.backends.lwjgl3.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALMusic;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;
import net.torvald.taudplay.TaudException;
import net.torvald.taudplay.TaudPlayer;

public class TaudOpenALMusic extends OpenALMusic {
    private final TaudStream stream;

    public TaudOpenALMusic(OpenALLwjgl3Audio audio, FileHandle file) {
        super(audio, file);
        try {
            stream = new TaudStream(new TaudPlayer(file.readBytes()));
        } catch (TaudException ex) {
            throw new GdxRuntimeException("Cannot open Taud song " + file + ": " + ex.getMessage(), ex);
        }
        stream.play();
        setup(2, stream.getSampleRate());
    }

    /** The song's knobs, probes and interrupts. */
    public TaudStream getStream() { return stream; }

    /** Bytes of 16-bit little-endian stereo; 0 once the song has ended. A
     *  read that ran dry while the song plays on (an underrun) is a buffer of
     *  silence, never 0 — OpenALMusic would take 0 for the end. */
    @Override
    public int read(byte[] buffer) {
        int frames = buffer.length / 4;
        int got = stream.readS16LE(buffer, 0, frames);
        if (got == 0 && !stream.isEnded()) return frames * 4;
        return got * 4;
    }

    /** From the top again (OpenALMusic calls this on stop() and to loop). */
    @Override
    public void reset() { stream.play(); }

    @Override
    public void dispose() {
        super.dispose();
        stream.dispose();
    }
}
