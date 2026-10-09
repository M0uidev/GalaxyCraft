package dev.moui.galaxycraft.music;

/** A stream of stereo float frames (L, R interleaved) at its own sample rate. */
public interface PcmSource extends AutoCloseable {
    int rate();

    /** Writes up to frames stereo frames at out[offsetFrames * 2 ...]; returns how many (0: the end). */
    int read(float[] out, int offsetFrames, int frames);

    /** True if it never ends by itself (it loops). */
    default boolean loops() {
        return false;
    }

    /** Its length in frames, -1 if unknown or endless. */
    default long lengthFrames() {
        return -1;
    }

    long positionFrames();

    @Override
    void close();
}
