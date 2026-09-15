package com.example.cleanrecovery.experimental;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;

/** Audio exists only in RAM during an explicit foreground experiment. */
public final class AcousticEngine {
    public interface Listener {
        void onResult(AcousticDetector.Result result);
        void onStopped(String reason);
    }
    private final AudioManager manager;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile String reason = "实验已停止";
    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change < 0) stop("音频被其他应用占用，实验已停止");
    };
    public AcousticEngine(Context context, Listener listener) {
        manager = context.getSystemService(AudioManager.class); this.listener=listener;
    }
    public void start() { new Thread(this::run, "AcousticExperiment").start(); }
    public boolean isStopping() { return stopped; }
    public void stop(String message) { reason=message; stopped=true; }
    @SuppressWarnings("deprecation")
    private void run() {
        AudioRecord record=null; AudioTrack track=null;
        boolean focused=false;
        try {
            if (stopped) return;
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC)==0) throw new IllegalStateException("媒体音量为零，请设置较低音量后重试");
            focused = manager.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focused) throw new IllegalStateException("无法获得音频使用权");
            AudioDeviceInfo speaker=null, microphone=null;
            for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if (d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker=d;
            for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
                if (d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC) microphone=d;
            if (speaker==null || microphone==null) throw new IllegalStateException("需要内置扬声器和麦克风");
            int min = AudioRecord.getMinBufferSize(AcousticDetector.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min<=0) throw new IllegalStateException("本机不支持 48 kHz 录音");
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AcousticDetector.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, AcousticDetector.SIZE*4));
            if (record.getState()!=AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("麦克风初始化失败");
            record.setPreferredDevice(microphone);
            short[] tone = new short[4800];
            for (int i=0; i<tone.length; i++) tone[i]=(short)(2400*Math.sin(2*Math.PI*AcousticDetector.CARRIER*i/AcousticDetector.RATE));
            track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    new AudioFormat.Builder().setSampleRate(AcousticDetector.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(), tone.length*2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState()!=AudioTrack.STATE_NO_STATIC_DATA) throw new IllegalStateException("扬声器初始化失败");
            track.setPreferredDevice(speaker);
            if (track.write(tone,0,tone.length)!=tone.length || track.setLoopPoints(0,tone.length,-1)!=AudioTrack.SUCCESS)
                throw new IllegalStateException("无法播放探测声波");
            if (stopped) return;
            record.startRecording(); track.play();
            short[] buffer = new short[AcousticDetector.SIZE];
            AcousticDetector detector = new AcousticDetector();
            long start=SystemClock.elapsedRealtime();
            while (!stopped) {
                if (SystemClock.elapsedRealtime()-start > 5*60*1000) { reason="本轮已达 5 分钟，实验已停止"; break; }
                int offset=0;
                long frameStart=SystemClock.elapsedRealtime();
                while (offset<buffer.length && !stopped) {
                    int n=record.read(buffer, offset, buffer.length-offset, AudioRecord.READ_NON_BLOCKING);
                    if (n<0) throw new IllegalStateException("录音中断（"+n+"）");
                    if (n==0) {
                        if (SystemClock.elapsedRealtime()-frameStart > 2000) throw new IllegalStateException("录音等待超时");
                        SystemClock.sleep(8);
                    } else offset+=n;
                }
                if (stopped) break;
                AudioDeviceInfo routed=track.getRoutedDevice();
                AudioDeviceInfo input=record.getRoutedDevice();
                if (routed==null || routed.getType()!=AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        || input==null || input.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    throw new IllegalStateException("音频路由改变，请断开耳机或蓝牙后重试");
                listener.onResult(detector.accept(buffer, SystemClock.elapsedRealtime()));
            }
        } catch (Exception e) { if (!stopped) reason="无法检测："+e.getMessage(); }
        finally {
            if (track!=null) { try { track.stop(); } catch (Exception ignored) { } track.release(); }
            if (record!=null) { try { record.stop(); } catch (Exception ignored) { } record.release(); }
            if (focused) manager.abandonAudioFocus(focus);
            stopped=true; listener.onStopped(reason);
        }
    }
}
