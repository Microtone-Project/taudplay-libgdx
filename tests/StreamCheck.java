/* TaudStream against the plain TaudPlayer: a stream read in odd-sized
 * pieces, threaded and not, must be the player's own render bit for bit;
 * its interrupts must reach the reader on the block they fired in; and a
 * fader set through an unthreaded stream must sound as one set on the player.
 *   java ... StreamCheck FILE.taud [SECONDS] */
import net.torvald.taudplay.TaudPlayer;
import net.torvald.taudplay.gdx.TaudStream;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class StreamCheck {
    static int fails;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static float[] direct(byte[] file, int frames, List<String> ints, boolean fader) throws Exception {
        TaudPlayer p = new TaudPlayer(file);
        int[] block = { 0 };
        for (int n = 0; n < 16; n++) p.setInterrupt(n, (k, a) -> ints.add(k + ":" + a + "@" + block[0]));
        p.play();
        if (fader) { p.setVoiceGain(0, 0.25, 0.5); p.setVoiceGain(2, 0.0, 0); }
        float[] out = new float[frames * 2];
        float[] b;
        int at = 0;
        while (at < frames && (b = p.renderChunk()) != null) {
            int n = Math.min(TaudPlayer.CHUNK, frames - at);
            System.arraycopy(b, 0, out, at * 2, n * 2);
            at += TaudPlayer.CHUNK;
            block[0]++;
        }
        return out;
    }

    static float[] viaStream(byte[] file, int frames, boolean threaded, List<String> ints, boolean fader) throws Exception {
        TaudStream s = new TaudStream(new TaudPlayer(file), 4096, threaded);
        s.setMaxWait(Long.MAX_VALUE);
        long[] read = { 0, 0 };
        for (int n = 0; n < 16; n++) s.setInterrupt(n, (k, a) -> ints.add(k + ":" + a + "@" + read[0] + "-" + read[1]));
        s.play();
        if (fader) { s.setVoiceGain(0, 0.25, 0.5); s.setVoiceGain(2, 0.0, 0); }
        float[] l = new float[frames], r = new float[frames];
        int[] sizes = { 1, 127, 128, 129, 1000, 4096, 777 };
        int at = 0, k = 0;
        while (at < frames) {
            int n = Math.min(sizes[k++ % sizes.length], frames - at);
            read[0] = at;
            read[1] = at + n;
            s.read(l, r, at, n);
            at += n;
        }
        s.dispose();
        float[] out = new float[frames * 2];
        for (int i = 0; i < frames; i++) { out[i * 2] = l[i]; out[i * 2 + 1] = r[i]; }
        return out;
    }

    /** Every interrupt the player fired, in order, each delivered by the read
     *  that held the first frame of the block it fired in. */
    static boolean onTheirBlocks(List<String> ref, List<String> got) {
        if (ref.size() != got.size()) return false;
        for (int i = 0; i < ref.size(); i++) {
            String[] r = ref.get(i).split("@"), g = got.get(i).split("@");
            if (!r[0].equals(g[0])) return false;
            long first = Long.parseLong(r[1]) * TaudPlayer.CHUNK;
            String[] span = g[1].split("-");
            if (first < Long.parseLong(span[0]) || first >= Long.parseLong(span[1])) return false;
        }
        return true;
    }

    static boolean same(float[] a, float[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) return false;
        return true;
    }

    public static void main(String[] argv) throws Exception {
        byte[] file = Files.readAllBytes(Paths.get(argv[0]));
        double seconds = argv.length > 1 ? Double.parseDouble(argv[1]) : 20;
        int frames = (int) (seconds * TaudPlayer.DEFAULT_RATE);
        List<String> i0 = new ArrayList<>(), i1 = new ArrayList<>(), i2 = new ArrayList<>();
        float[] ref = direct(file, frames, i0, false);
        check("threaded stream = player", same(ref, viaStream(file, frames, true, i1, false)));
        check("unthreaded stream = player", same(ref, viaStream(file, frames, false, i2, false)));
        check("interrupts on their blocks (" + i0.size() + " fired)", onTheirBlocks(i0, i1) && onTheirBlocks(i0, i2));
        float[] refF = direct(file, frames, new ArrayList<>(), true);
        check("fader through an unthreaded stream = on the player", same(refF, viaStream(file, frames, false, new ArrayList<>(), true)));
        /* A read straight after play() must wait for the render thread to
         * run it, never report the song over (the race a mixer that takes a
         * dry read for the end would hit). */
        TaudPlayer p = new TaudPlayer(file);
        boolean neverEnded = true, allRead = true;
        TaudStream s = new TaudStream(p);
        float[] l = new float[1152], r = new float[1152];
        for (int i = 0; i < 200; i++) {
            s.play();
            if (s.isEnded()) neverEnded = false;
            if (s.read(l, r, 0, l.length) == 0) allRead = false;
            s.stop();
        }
        check("play() then read at once: not ended, frames read", neverEnded && allRead);
        long t0 = System.nanoTime();
        while (!s.isEnded() && System.nanoTime() - t0 < 1_000_000_000L) Thread.sleep(1);
        check("stop(): ended", s.isEnded());
        s.dispose();
        System.exit(fails == 0 ? 0 : 1);
    }
}
