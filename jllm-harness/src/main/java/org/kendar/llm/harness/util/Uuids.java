package org.kendar.llm.harness.util;

import java.security.SecureRandom;
import java.util.UUID;

/** UUID version 7 (RFC 9562): the leading 48 bits are the unix time in ms, so ids sort in creation order. */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastMillis;
    private static int sequence;

    private Uuids() {
    }

    /** Strictly increasing within this process, also for ids created in the same millisecond. */
    public static synchronized UUID v7() {
        long now = System.currentTimeMillis();
        if (now > lastMillis) {
            lastMillis = now;
            sequence = RANDOM.nextInt(1 << 11);
        } else if (++sequence > 0xFFF) {
            lastMillis++;
            sequence = 0;
        }
        long msb = (lastMillis << 16) | 0x7000L | sequence;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public static String v7String() {
        return v7().toString();
    }
}
