package com.example.cleanrecovery.ytdlp;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class MediaMuxerUtil {

    private static final int BUFFER_SIZE = 1024 * 1024;

    private MediaMuxerUtil() {}

    public static void merge(File videoFile, File audioFile, File outputFile) throws IOException {
        if (videoFile == null || audioFile == null || outputFile == null) {
            throw new IOException("merge input is null");
        }
        File tmp = tempOutput(outputFile);
        MediaMuxer muxer = null;
        TrackSource video = null;
        TrackSource audio = null;
        try {
            video = TrackSource.open(videoFile, "video/");
            audio = TrackSource.open(audioFile, "audio/");
            if (video == null || audio == null) {
                throw new IOException("missing video or audio track");
            }
            ensureParent(tmp);
            muxer = new MediaMuxer(tmp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            video.outputTrack = muxer.addTrack(video.format);
            audio.outputTrack = muxer.addTrack(audio.format);
            muxer.start();
            copyTrack(video, muxer);
            copyTrack(audio, muxer);
            closeMuxer(muxer);
            muxer = null;
            replace(tmp, outputFile);
        } finally {
            closeQuietly(video);
            closeQuietly(audio);
            if (muxer != null) {
                closeMuxer(muxer);
            }
            if (tmp.exists() && !tmp.equals(outputFile)) {
                tmp.delete();
            }
        }
    }

    static void remux(File inputFile, File outputFile) throws IOException {
        if (inputFile == null || outputFile == null) {
            throw new IOException("remux input is null");
        }
        File tmp = tempOutput(outputFile);
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(inputFile.getAbsolutePath());
            ensureParent(tmp);
            muxer = new MediaMuxer(tmp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int trackCount = extractor.getTrackCount();
            int[] outTracks = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                outTracks[i] = muxer.addTrack(format);
            }
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = 0; i < trackCount; i++) {
                extractor.selectTrack(i);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                copySelectedTrack(extractor, muxer, outTracks[i], buffer, info);
                extractor.unselectTrack(i);
            }
            closeMuxer(muxer);
            muxer = null;
            replace(tmp, outputFile);
        } finally {
            extractor.release();
            if (muxer != null) {
                closeMuxer(muxer);
            }
            if (tmp.exists() && !tmp.equals(outputFile)) {
                tmp.delete();
            }
        }
    }

    private static void copyTrack(TrackSource source, MediaMuxer muxer) throws IOException {
        source.extractor.selectTrack(source.inputTrack);
        // 高分辨率流的单帧可超过 1MB（实测 4K avc1 崩溃）：必须按轨道
        // KEY_MAX_INPUT_SIZE 分配缓冲，否则 readSampleData 抛 IllegalArgumentException
        int bufferSize = BUFFER_SIZE;
        try {
            bufferSize = Math.max(BUFFER_SIZE,
                    source.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE));
        } catch (Exception ignored) {
            // 格式未声明时退回默认缓冲
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        copySelectedTrack(source.extractor, muxer, source.outputTrack, buffer, info);
        source.extractor.unselectTrack(source.inputTrack);
    }

    private static void copySelectedTrack(MediaExtractor extractor, MediaMuxer muxer,
                                          int outputTrack, ByteBuffer buffer,
                                          MediaCodec.BufferInfo info) {
        while (true) {
            int sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0) {
                break;
            }
            int sampleFlags = extractor.getSampleFlags();
            int bufferFlags = 0;
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                bufferFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && (sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                bufferFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
            }
            info.set(0, sampleSize, extractor.getSampleTime(), bufferFlags);
            muxer.writeSampleData(outputTrack, buffer, info);
            extractor.advance();
        }
    }

    private static File tempOutput(File outputFile) {
        return new File(outputFile.getParentFile(), outputFile.getName() + ".muxing");
    }

    private static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create parent dir: " + parent);
        }
    }

    private static void replace(File tmp, File output) throws IOException {
        if (output.exists() && !output.delete()) {
            throw new IOException("cannot replace existing output: " + output);
        }
        if (!tmp.renameTo(output)) {
            throw new IOException("cannot move muxed output into place");
        }
    }

    private static void closeMuxer(MediaMuxer muxer) {
        try {
            muxer.stop();
        } catch (RuntimeException ignored) {
        }
        muxer.release();
    }

    private static void closeQuietly(TrackSource source) {
        if (source != null) {
            source.extractor.release();
        }
    }

    private static final class TrackSource {
        final MediaExtractor extractor;
        final int inputTrack;
        final MediaFormat format;
        int outputTrack;

        private TrackSource(MediaExtractor extractor, int inputTrack, MediaFormat format) {
            this.extractor = extractor;
            this.inputTrack = inputTrack;
            this.format = format;
        }

        static TrackSource open(File file, String mimePrefix) throws IOException {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith(mimePrefix)) {
                        return new TrackSource(extractor, i, format);
                    }
                }
                extractor.release();
                return null;
            } catch (IOException | RuntimeException e) {
                extractor.release();
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                throw new IOException(e);
            }
        }
    }
}
