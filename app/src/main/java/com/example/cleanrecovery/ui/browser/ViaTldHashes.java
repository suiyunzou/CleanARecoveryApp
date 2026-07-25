package com.example.cleanrecovery.ui.browser;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Exact perfect-hash membership recovered from VIA's ViaHashSets source.
 *
 * <p>The sorted hashes are delta/varint encoded only to keep the Java source
 * and dex compact. Membership and hash arithmetic are unchanged.</p>
 */
final class ViaTldHashes {
    private static final int MULTIPLIER = 1540483477;
    private static final int HASH_COUNT = 1594;
    private static final String ENCODED_DELTAS =
            "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";

    private static final int[] TLD_HASHES = decodeHashes();

    private ViaTldHashes() {
    }

    static boolean isKnownTld(String value) {
        if (value == null || value.length() < 2 || value.length() > 24) {
            return false;
        }
        return Arrays.binarySearch(
                TLD_HASHES, viaHash(value.toLowerCase(Locale.ROOT))) >= 0;
    }

    private static int viaHash(String value) {
        int state = 0;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte next : bytes) {
            int product = (state ^ (next & 0xff)) * MULTIPLIER;
            int positive = product & Integer.MAX_VALUE;
            state = positive ^ (positive >>> 15);
        }
        return state;
    }

    private static int[] decodeHashes() {
        byte[] deltas = decodeBase64(ENCODED_DELTAS);
        int[] hashes = new int[HASH_COUNT];
        int hash = 0;
        int value = 0;
        int shift = 0;
        int output = 0;
        for (byte encoded : deltas) {
            int next = encoded & 0xff;
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                hash += value;
                hashes[output++] = hash;
                value = 0;
                shift = 0;
            } else {
                shift += 7;
            }
        }
        if (output != HASH_COUNT || shift != 0) {
            throw new IllegalStateException("Invalid VIA TLD hash data");
        }
        return hashes;
    }

    private static byte[] decodeBase64(String value) {
        int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        byte[] output = new byte[value.length() / 4 * 3 - padding];
        int outputAt = 0;
        int accumulator = 0;
        int bits = 0;
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (next == '=') {
                break;
            }
            int decoded;
            if (next >= 'A' && next <= 'Z') {
                decoded = next - 'A';
            } else if (next >= 'a' && next <= 'z') {
                decoded = next - 'a' + 26;
            } else if (next >= '0' && next <= '9') {
                decoded = next - '0' + 52;
            } else if (next == '+') {
                decoded = 62;
            } else if (next == '/') {
                decoded = 63;
            } else {
                throw new IllegalStateException("Invalid base64 character");
            }
            accumulator = (accumulator << 6) | decoded;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                output[outputAt++] = (byte) (accumulator >>> bits);
                accumulator &= (1 << bits) - 1;
            }
        }
        if (outputAt != output.length) {
            throw new IllegalStateException("Invalid base64 length");
        }
        return output;
    }
}
