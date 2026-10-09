package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.Mixer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Runs a {@link Mixer} into the default audio line on a daemon thread. No device: the music is just off. */
final class JavaSoundSink implements Runnable {
    private static final int BLOCK = 1024;
    private final Mixer mixer;
    private volatile boolean running = true;
    private Thread thread;

    JavaSoundSink(Mixer mixer) {
        this.mixer = mixer;
    }

    void start() {
        running = true;
        thread = new Thread(this, "GalaxyCraft music");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        AudioFormat fmt = new AudioFormat(Mixer.RATE, 16, 2, true, false);
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, Mixer.RATE / 5 * 4); // ~0.2 s
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            System.err.println("GalaxyCraft: no audio line for the soundtrack: " + e);
            return;
        }
        float[] mix = new float[BLOCK * 2];
        byte[] bytes = new byte[BLOCK * 4];
        try {
            while (running) {
                mixer.render(mix, BLOCK);
                for (int i = 0; i < BLOCK * 2; i++) {
                    int s = Math.round(Math.clamp(mix[i], -1f, 1f) * 32767f);
                    bytes[2 * i] = (byte) s;
                    bytes[2 * i + 1] = (byte) (s >> 8);
                }
                line.write(bytes, 0, bytes.length);
            }
        } finally {
            line.stop();
            line.close();
        }
    }
}
