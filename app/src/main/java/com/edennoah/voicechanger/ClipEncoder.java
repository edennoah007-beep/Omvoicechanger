package com.edennoah.voicechanger;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Turns the raw recorded PCM into an .m4a (AAC) clip, falling back to .wav. */
final class ClipEncoder {

    private ClipEncoder() { }

    static File encode(Context c, File pcm, int sr) {
        File dir = new File(c.getCacheDir(), "clips");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        int preset = c.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE).getInt("preset", 1);
        String tag = preset == VoiceEngine.PRESET_FLEETWAY ? "Fleetway" : preset == VoiceEngine.PRESET_2011X ? "2011X" : "Voice";
        File m4a = new File(dir, tag + "_" + stamp + ".m4a");
        try {
            encodeM4a(pcm, m4a, sr);
            if (m4a.length() > 1000) { pcm.delete(); return m4a; }
        } catch (Exception ignored) { }
        m4a.delete();
        File wav = new File(dir, tag + "_" + stamp + ".wav");
        try {
            writeWav(pcm, wav, sr);
            pcm.delete();
            return wav;
        } catch (IOException e) {
            return null;
        }
    }

    private static void encodeM4a(File pcm, File out, int sr) throws IOException {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        MediaMuxer mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        boolean muxStarted = false, inputDone = false, outputDone = false;
        long samplesQueued = 0;
        byte[] chunk = new byte[8192];
        try (InputStream in = new BufferedInputStream(new FileInputStream(pcm))) {
            while (!outputDone) {
                if (!inputDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii);
                        b.clear();
                        int max = Math.min(b.remaining(), chunk.length) & ~1;
                        int r = readFully(in, chunk, max);
                        long pts = samplesQueued * 1000000L / sr;
                        if (r <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            b.put(chunk, 0, r);
                            codec.queueInputBuffer(ii, 0, r, pts, 0);
                            samplesQueued += r / 2;
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(codec.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted && ob != null) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) { }
            codec.release();
            try { if (muxStarted) mux.stop(); } catch (Exception ignored) { }
            mux.release();
        }
    }

    private static int readFully(InputStream in, byte[] b, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(b, total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total & ~1;
    }

    private static void writeWav(File pcm, File wav, int sr) throws IOException {
        int dataLen = (int) pcm.length();
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(wav));
             InputStream in = new BufferedInputStream(new FileInputStream(pcm))) {
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + dataLen)); o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16));
            o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(sr)); o.writeInt(Integer.reverseBytes(sr * 2));
            o.writeShort(Short.reverseBytes((short) 2)); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(dataLen));
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        }
    }

    /** Copies the clip to the public Music/ChaosVoice folder (Android 10+). */
    static boolean saveToMusic(Context c, File clip) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, clip.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, ClipProvider.mimeFor(clip.getName()));
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/ChaosVoice");
            Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            try (OutputStream os = cr.openOutputStream(uri); InputStream in = new FileInputStream(clip)) {
                if (os == null) return false;
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
