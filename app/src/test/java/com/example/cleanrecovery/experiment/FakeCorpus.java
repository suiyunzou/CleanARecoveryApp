package com.example.cleanrecovery.experiment;

import java.util.Arrays;
import java.util.List;

public final class FakeCorpus {
    private FakeCorpus() {
    }

    public static List<GroundTruthEntry> groundTruth() {
        return Arrays.asList(
                new GroundTruthEntry(
                        "gt-photo-1",
                        "/storage/emulated/0/DCIM/deleted/photo1.jpg",
                        "abc1111111111111111111111111111111111111111111111111111111111",
                        "",
                        true,
                        false
                ),
                new GroundTruthEntry(
                        "gt-photo-2",
                        "/storage/emulated/0/DCIM/deleted/photo2.jpg",
                        "abc2222222222222222222222222222222222222222222222222222222222",
                        "",
                        true,
                        false
                ),
                new GroundTruthEntry(
                        "gt-thumb-1",
                        "/storage/emulated/0/DCIM/deleted/photo1_thumb.jpg",
                        "different-sha-thumb",
                        "phash-thumb-1",
                        false,
                        true
                )
        );
    }

    public static byte[] minimalJpegBytes() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xD9
        };
    }

    public static byte[] multiJpegBlob() {
        byte[] first = minimalJpegBytes();
        byte[] second = minimalJpegBytes();
        byte[] blob = new byte[4 + first.length + second.length];
        blob[0] = 0x00;
        blob[1] = 0x01;
        blob[2] = 0x02;
        blob[3] = 0x03;
        System.arraycopy(first, 0, blob, 4, first.length);
        System.arraycopy(second, 0, blob, 4 + first.length, second.length);
        return blob;
    }

    public static byte[] corruptedJpegBytes() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x02,
                0x13, 0x37,
                (byte) 0xFF, (byte) 0xD9
        };
    }
}
