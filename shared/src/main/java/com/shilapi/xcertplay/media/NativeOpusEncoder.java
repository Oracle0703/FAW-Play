package com.shilapi.xcertplay.media;

/** libopus fallback for ROMs that decode Opus but do not expose a MediaCodec encoder. */
public final class NativeOpusEncoder implements java.io.Closeable {
    private static final boolean LOADED;
    static {
        boolean loaded;
        try { System.loadLibrary("diplay_opus"); loaded = true; }
        catch (LinkageError | SecurityException unavailable) { loaded = false; }
        LOADED = loaded;
    }
    private long handle;

    public NativeOpusEncoder(int bitrate) {
        if (LOADED) handle = nativeCreate(bitrate);
    }
    public static boolean isAvailable() { return LOADED; }
    public synchronized boolean isOpen() { return handle != 0; }

    public synchronized byte[] encode(byte[] pcm) {
        if (handle == 0) return null;
        if (pcm == null || pcm.length != 1920)
            throw new IllegalArgumentException("Opus requires one 20ms 48kHz mono PCM frame");
        return nativeEncode(handle, pcm);
    }

    @Override public synchronized void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }

    private static native long nativeCreate(int bitrate);
    private static native byte[] nativeEncode(long handle, byte[] pcm);
    private static native void nativeDestroy(long handle);
}
