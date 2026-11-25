package com.edennoah.voicechanger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Foreground service that owns the microphone: mic -> VoiceEngine -> speaker/headphones (live)
 * and/or -> clip file (recording). Keeps working while you switch to other apps.
 */
public class VoiceService extends Service {

    public static final String ACTION_LIVE_ON = "live_on";
    public static final String ACTION_LIVE_OFF = "live_off";
    public static final String ACTION_REC_START = "rec_start";
    public static final String ACTION_REC_STOP = "rec_stop";
    public static final String ACTION_PRESET = "preset";
    public static final String ACTION_RESTART_AUDIO = "restart_audio";
    public static final String ACTION_STOP_ALL = "stop_all";
    public static final String EXTRA_PRESET = "preset_id";

    public static final String PREFS = "voice_prefs";
    public static final int SR = 48000;
    private static final int BLOCK = 480; // 10 ms
    private static final String CHANNEL = "voice_live";
    private static final int NOTIF_ID = 2011;

    public static final VoiceEngine ENGINE = new VoiceEngine(SR);

    // State shared with the UI
    public static volatile boolean running;
    public static volatile boolean monitor;
    public static volatile boolean recording;
    public static volatile float level;
    public static volatile File lastClip;
    public static volatile String status = "";

    private Thread audioThread;
    private volatile boolean threadRun;
    private OutputStream pcmOut;
    private File pcmFile;
    private long recSamples;

    // ------------------------------------------------------------------ settings

    public static void applySettings(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        int preset = sp.getInt("preset", VoiceEngine.PRESET_2011X);
        float pitch = sp.getFloat("pitch", 0f);
        float intensity = sp.getFloat("intensity", 1f);
        float mic = sp.getFloat("mic", 1f);
        ENGINE.setParams(VoiceEngine.withTweaks(VoiceEngine.preset(preset), pitch, intensity));
        ENGINE.setMicGain(mic);
    }

    public static void send(Context c, String action) {
        Intent i = new Intent(c, VoiceService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Live voice changer", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String a = intent != null ? intent.getAction() : null;
        if (a == null) a = "";
        switch (a) {
            case ACTION_LIVE_ON: monitor = true; break;
            case ACTION_LIVE_OFF: monitor = false; break;
            case ACTION_REC_START: startRecording(); break;
            case ACTION_REC_STOP: stopRecording(); break;
            case ACTION_PRESET:
                int id = intent.getIntExtra(EXTRA_PRESET, VoiceEngine.PRESET_2011X);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("preset", id).apply();
                break;
            case ACTION_RESTART_AUDIO: stopAudio(); break;
            case ACTION_STOP_ALL: monitor = false; if (recording) stopRecording(); break;
        }
        applySettings(this);

        if (!monitor && !recording) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (audioThread == null) startAudio();
        goForeground(); // refresh notification text
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAudio();
        if (recording) stopRecording();
        running = false; monitor = false; level = 0f;
        super.onDestroy();
    }

    private void shutdown() {
        stopAudio();
        running = false; level = 0f;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        int preset = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("preset", VoiceEngine.PRESET_2011X);
        String title = (monitor ? "LIVE: " : recording ? "Recording: " : "") + VoiceEngine.PRESET_NAMES[preset];
        String text = recording ? "Recording a voice clip… tap Stop in the app to save" : "Mic → voice filter → your headphones/speaker";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        b.addAction(new Notification.Action.Builder(null, "2011X", presetIntent(VoiceEngine.PRESET_2011X)).build());
        b.addAction(new Notification.Action.Builder(null, "Fleetway", presetIntent(VoiceEngine.PRESET_FLEETWAY)).build());
        Intent stop = new Intent(this, VoiceService.class).setAction(ACTION_STOP_ALL);
        b.addAction(new Notification.Action.Builder(null, "Stop",
                PendingIntent.getService(this, 9, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        return b.build();
    }

    private PendingIntent presetIntent(int id) {
        Intent i = new Intent(this, VoiceService.class).setAction(ACTION_PRESET).putExtra(EXTRA_PRESET, id);
        return PendingIntent.getService(this, 100 + id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ------------------------------------------------------------------ recording

    private synchronized void startRecording() {
        if (recording) return;
        try {
            File dir = new File(getCacheDir(), "clips");
            dir.mkdirs();
            pcmFile = new File(dir, "recording.pcm");
            pcmOut = new BufferedOutputStream(new FileOutputStream(pcmFile), 65536);
            recSamples = 0;
            recording = true;
            status = "Recording…";
        } catch (IOException e) {
            status = "Could not start recording: " + e.getMessage();
        }
    }

    private void stopRecording() {
        synchronized (this) {
            if (!recording) return;
            recording = false;
            try { pcmOut.close(); } catch (IOException ignored) { }
            pcmOut = null;
        }
        final File pcm = pcmFile;
        final long samples = recSamples;
        status = "Saving clip…";
        new Thread(() -> {
            if (samples < SR / 4) { status = "Clip too short"; return; }
            File clip = ClipEncoder.encode(getApplicationContext(), pcm, SR);
            if (clip != null) {
                lastClip = clip;
                boolean saved = ClipEncoder.saveToMusic(getApplicationContext(), clip);
                status = "Saved " + clip.getName() + (saved ? " (also in Music/ChaosVoice)" : "");
            } else {
                status = "Saving failed";
            }
        }, "clip-encode").start();
    }

    // ------------------------------------------------------------------ audio loop

    private void startAudio() {
        threadRun = true;
        running = true;
        audioThread = new Thread(this::audioLoop, "voice-audio");
        audioThread.start();
    }

    private void stopAudio() {
        threadRun = false;
        Thread t = audioThread;
        audioThread = null;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException ignored) { }
        }
    }

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        boolean speakerMode = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("speaker", false);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);

        int source = speakerMode ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC;
        int usage = speakerMode ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA;

        int minRec = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int minPlay = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        AudioTrack track = null;
        try {
            rec = new AudioRecord(source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minRec, BLOCK * 2 * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                status = "Microphone busy or not allowed";
                return;
            }
            AudioTrack.Builder tb = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(minPlay, BLOCK * 2 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            track = tb.build();
            if (speakerMode && am != null) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            }

            rec.startRecording();
            track.play();
            status = "Running";

            short[] s = new short[BLOCK];
            short[] silence = new short[BLOCK];
            float[] f = new float[BLOCK];
            float peakSmooth = 0f;
            while (threadRun) {
                int n = rec.read(s, 0, BLOCK);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) f[i] = s[i] / 32768f;
                ENGINE.process(f, n);
                float pk = 0f;
                for (int i = 0; i < n; i++) {
                    float v = f[i];
                    float a = v < 0 ? -v : v;
                    if (a > pk) pk = a;
                    int q = (int) (v * 32767f);
                    s[i] = (short) (q > 32767 ? 32767 : (q < -32768 ? -32768 : q));
                }
                peakSmooth = Math.max(pk, peakSmooth * 0.85f);
                level = peakSmooth;

                if (monitor) track.write(s, 0, n); else track.write(silence, 0, n);

                if (recording) {
                    synchronized (this) {
                        if (recording && pcmOut != null) {
                            try {
                                byte[] bytes = new byte[n * 2];
                                for (int i = 0; i < n; i++) {
                                    bytes[2 * i] = (byte) (s[i] & 0xff);
                                    bytes[2 * i + 1] = (byte) ((s[i] >> 8) & 0xff);
                                }
                                pcmOut.write(bytes);
                                recSamples += n;
                            } catch (IOException e) {
                                status = "Recording error: " + e.getMessage();
                            }
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            status = "Microphone permission is missing";
        } catch (Exception e) {
            status = "Audio error: " + e.getMessage();
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            if (track != null) { try { track.stop(); } catch (Exception ignored) { } track.release(); }
            if (speakerMode && am != null) {
                am.setSpeakerphoneOn(false);
                am.setMode(AudioManager.MODE_NORMAL);
            }
            level = 0f;
            if (threadRun) running = false; // ended on its own (error)
        }
    }
}
