/*
 * taudplay-libgdx — wiring.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;

public final class TaudGdx {
    private TaudGdx() {}

    /** This library's version (taudplay-java's is TaudPlayer.VERSION). */
    public static final String VERSION = "1.0.0";

    /** Teach the lwjgl3 backend the `.taud` extension, so Gdx.audio.newMusic
     *  opens a Taud song as a TaudOpenALMusic. Returns false (and changes
     *  nothing) on any other backend, or before Gdx.audio exists. */
    public static boolean registerOpenAL() {
        try {
            Object audio = Gdx.audio;
            if (audio == null) return false;
            Class<?> openAl = Class.forName("com.badlogic.gdx.backends.lwjgl3.audio.OpenALLwjgl3Audio");
            if (!openAl.isInstance(audio)) return false;
            return OpenAl.register(audio);
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    /* Kept apart so that nothing lwjgl3 is loaded until it is known to exist. */
    private static final class OpenAl {
        static boolean register(Object audio) {
            ((com.badlogic.gdx.backends.lwjgl3.audio.OpenALLwjgl3Audio) audio).registerMusic("taud", TaudOpenALMusic.class);
            return true;
        }
    }

    /** A .taud file as Music: the OpenAL kind when the backend is lwjgl3 (and
     *  registerOpenAL() ran), else the portable AudioDevice kind. */
    public static Music newMusic(FileHandle file) {
        if (registerOpenAL()) return Gdx.audio.newMusic(file);
        return new TaudMusic(file);
    }

    /** The stream behind a Music this library made, or null. */
    public static TaudStream streamOf(Music music) {
        if (music instanceof TaudMusic) return ((TaudMusic) music).getStream();
        try {
            if (music instanceof TaudOpenALMusic) return ((TaudOpenALMusic) music).getStream();
        } catch (LinkageError ex) { /* no lwjgl3 backend */ }
        return null;
    }
}
