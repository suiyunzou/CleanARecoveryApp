package com.example.cleanrecovery.experiment.jpeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JpegStructureParser {
    public static final int MARKER_SOI = 0xFFD8;
    public static final int MARKER_EOI = 0xFFD9;
    public static final int MARKER_SOS = 0xFFDA;

    public enum SegmentKind {
        SOI,
        APP,
        DQT,
        SOF,
        DHT,
        SOS,
        RST,
        EOI,
        OTHER,
        INVALID
    }

    public static final class Segment {
        public final int offset;
        public final int marker;
        public final SegmentKind kind;
        public final int payloadLength;
        public final String detail;

        public Segment(int offset, int marker, SegmentKind kind, int payloadLength, String detail) {
            this.offset = offset;
            this.marker = marker;
            this.kind = kind;
            this.payloadLength = payloadLength;
            this.detail = detail;
        }
    }

    public static final class ParseResult {
        public final boolean valid;
        public final int endOffset;
        public final List<Segment> segments;
        public final String failureReason;
        public final boolean sawSos;

        public ParseResult(boolean valid, int endOffset, List<Segment> segments, String failureReason, boolean sawSos) {
            this.valid = valid;
            this.endOffset = endOffset;
            this.segments = segments;
            this.failureReason = failureReason;
            this.sawSos = sawSos;
        }
    }

    public ParseResult parse(byte[] data, int startOffset) {
        if (data == null || startOffset < 0 || startOffset + 1 >= data.length) {
            return invalid(startOffset, "buffer_too_small", false);
        }
        if (readMarker(data, startOffset) != MARKER_SOI) {
            return invalid(startOffset, "missing_soi", false);
        }

        ArrayList<Segment> segments = new ArrayList<>();
        segments.add(new Segment(startOffset, MARKER_SOI, SegmentKind.SOI, 0, "SOI"));
        int offset = startOffset + 2;
        boolean sawSos = false;

        while (offset + 1 < data.length) {
            if (data[offset] != (byte) 0xFF) {
                if (!sawSos) {
                    return invalid(startOffset, "unexpected_non_marker_at_" + offset, sawSos);
                }
                offset = skipEntropyData(data, offset);
                if (offset < 0) {
                    return invalid(startOffset, "entropy_without_eoi", sawSos);
                }
                if (readMarker(data, offset) == MARKER_EOI) {
                    segments.add(new Segment(offset, MARKER_EOI, SegmentKind.EOI, 0, "EOI"));
                    return new ParseResult(true, offset + 2, segments, "", sawSos);
                }
                continue;
            }

            int marker = readMarker(data, offset);
            if (marker == MARKER_EOI) {
                segments.add(new Segment(offset, MARKER_EOI, SegmentKind.EOI, 0, "EOI"));
                return new ParseResult(true, offset + 2, segments, "", sawSos);
            }
            if (marker == MARKER_SOS) {
                sawSos = true;
                int payloadLength = readSegmentLength(data, offset + 2);
                segments.add(new Segment(offset, marker, SegmentKind.SOS, payloadLength, "SOS"));
                offset += 2 + payloadLength;
                continue;
            }
            if (isRstMarker(marker)) {
                segments.add(new Segment(offset, marker, SegmentKind.RST, 0, "RST"));
                offset += 2;
                continue;
            }
            if (offset + 3 >= data.length) {
                return invalid(startOffset, "truncated_segment_header", sawSos);
            }
            int payloadLength = readSegmentLength(data, offset + 2);
            if (payloadLength < 2 || offset + 2 + payloadLength > data.length) {
                return invalid(startOffset, "invalid_segment_length_" + payloadLength, sawSos);
            }
            SegmentKind kind = classify(marker);
            segments.add(new Segment(offset, marker, kind, payloadLength, markerName(marker)));
            offset += 2 + payloadLength;
        }
        return invalid(startOffset, "missing_eoi", sawSos);
    }

    public List<Integer> findSoiOffsets(byte[] data) {
        ArrayList<Integer> offsets = new ArrayList<>();
        if (data == null) {
            return offsets;
        }
        for (int index = 0; index + 1 < data.length; index++) {
            if (readMarker(data, index) == MARKER_SOI) {
                offsets.add(index);
            }
        }
        return offsets;
    }

    private static int skipEntropyData(byte[] data, int offset) {
        for (int index = offset; index + 1 < data.length; index++) {
            if (data[index] == (byte) 0xFF) {
                if (data[index + 1] == 0x00) {
                    index++;
                    continue;
                }
                return index;
            }
        }
        return -1;
    }

    private static int readMarker(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readSegmentLength(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static boolean isRstMarker(int marker) {
        return marker >= 0xFFD0 && marker <= 0xFFD7;
    }

    private static SegmentKind classify(int marker) {
        if (marker >= 0xFFE0 && marker <= 0xFFEF) {
            return SegmentKind.APP;
        }
        if (marker == 0xFFDB) {
            return SegmentKind.DQT;
        }
        if (marker == 0xFFC0 || marker == 0xFFC2) {
            return SegmentKind.SOF;
        }
        if (marker == 0xFFC4) {
            return SegmentKind.DHT;
        }
        return SegmentKind.OTHER;
    }

    private static String markerName(int marker) {
        return String.format(Locale.US, "0x%04X", marker);
    }

    private static ParseResult invalid(int startOffset, String reason, boolean sawSos) {
        return new ParseResult(false, startOffset, List.of(), reason, sawSos);
    }
}
