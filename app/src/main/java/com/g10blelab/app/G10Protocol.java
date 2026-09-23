package com.g10blelab.app;

/** Only the 20-byte 24 22 stream observed on our G10, not every FFF2 device. */
public final class G10Protocol {
    public static final long FRESH_MS = 2000;
    public static final long COMMAND_INTERVAL_MS = 750;
    private G10Protocol() { }

    public static boolean isTelemetry(byte[] p) {
        if (p == null || p.length != 20 || u8(p[0]) != 0x24 || u8(p[1]) != 0x22) return false;
        double voltage = (u8(p[4]) | u8(p[5]) << 8) / 100.0;
        return voltage >= 20 && voltage <= 80 && u8(p[12]) <= 100;
    }

    public static boolean isFresh(long receivedMs, long nowMs) {
        return receivedMs >= 0 && nowMs >= receivedMs && nowMs - receivedMs <= FRESH_MS;
    }

    public static boolean canSendMode(int mode, int speed, boolean moving, boolean ready,
                                      long receivedMs, long lastCommandMs, long nowMs) {
        return mode >= 1 && mode <= 3 && ready && speed == 0 && !moving &&
                isFresh(receivedMs, nowMs) && (lastCommandMs < 0 ||
                (nowMs >= lastCommandMs && nowMs - lastCommandMs >= COMMAND_INTERVAL_MS));
    }

    public static byte[] modeCommand(int mode) {
        if (mode < 1 || mode > 3) throw new IllegalArgumentException("Unknown mode");
        return new byte[] {(byte) 0xF0, 0x4C, 0x03, (byte) mode};
    }

    private static int u8(byte b) { return b & 0xff; }
}
