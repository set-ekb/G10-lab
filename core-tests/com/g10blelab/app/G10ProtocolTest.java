package com.g10blelab.app;

import java.util.Arrays;

public final class G10ProtocolTest {
    public static void main(String[] args) {
        byte[] packet = hex("24 22 01 25 F2 12 00 00 00 00 00 00 00 00 E5 08 19 00 40 00");
        check(G10Protocol.isTelemetry(packet), "recorded G10 packet");
        check(!G10Protocol.isTelemetry(Arrays.copyOf(packet, 19)), "truncated");
        check(!G10Protocol.isTelemetry(Arrays.copyOf(packet, 21)), "unknown long layout");
        for (int header : new int[] {0x55, 0x5a, 0xf1, 0xff}) {
            byte[] other = packet.clone(); other[0] = (byte) header;
            check(!G10Protocol.isTelemetry(other), "other family remains passive");
        }
        byte[] invalid = packet.clone(); invalid[4] = invalid[5] = 0;
        check(!G10Protocol.isTelemetry(invalid), "invalid voltage");
        check(G10Protocol.canSendMode(1, 0, false, true, 1000, -1, 1100), "fresh stopped data");
        check(!G10Protocol.canSendMode(1, 0, false, true, 1000, -1, 3001), "stale stopped data");
        check(!G10Protocol.canSendMode(1, 0, false, false, 1000, -1, 1100), "subscription not ready");
        check(!G10Protocol.canSendMode(1, 0, true, true, 1000, -1, 1100), "motion flag");
        check(!G10Protocol.canSendMode(1, 1, false, true, 1000, -1, 1100), "moving speed");
        check(!G10Protocol.canSendMode(1, 0, false, true, 1000, 1050, 1100), "rapid repeat");
        check(!G10Protocol.canSendMode(4, 0, false, true, 1000, -1, 1100), "unknown command");
        check(!G10Protocol.isFresh(-1, 1000) && !G10Protocol.isFresh(1000, 999), "invalid clocks");
        for (int i = 1; i <= 3; i++) {
            check(Arrays.equals(G10Protocol.modeCommand(i), new byte[] {(byte) 0xf0, 0x4c, 3, (byte) i}), "known payload");
        }
        System.out.println("G10ProtocolTest: OK");
    }
    private static byte[] hex(String s) {
        String[] parts = s.split(" "); byte[] p = new byte[parts.length];
        for (int i = 0; i < p.length; i++) p[i] = (byte) Integer.parseInt(parts[i], 16);
        return p;
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
}
