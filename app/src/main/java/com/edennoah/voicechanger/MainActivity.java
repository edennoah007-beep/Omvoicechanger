package com.edennoah.voicechanger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int BG = 0xFF0B0B10, CARD = 0xFF17171F, TEXT = 0xFFEDEDF2, DIM = 0xFF9A9AAA;
    private static final int RED = 0xFFD3122A, GOLD = 0xFFF5C518, GREY = 0xFF5A5A66;
    private static final int[] PRESET_COLORS = {GREY, RED, GOLD};
    private static final String[] PRESET_DESC = {
            "Your real voice, no filter.",
            "Deep demonic god voice: two pitched-down layers, distortion, growl, echo and dark reverb.",
            "Crazed, raspy, high Super Sonic voice with an unhinged double, bite and a short echo."
    };

    private SharedPreferences sp;
    private final List<Button> presetButtons = new ArrayList<>();
    private TextView presetDesc, statusText, pitchLabel, intensityLabel, micLabel;
    private Button liveBtn, recBtn, playBtn;
    private ProgressBar meter;
    private MediaPlayer player;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingAction;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 120);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(VoiceService.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        VoiceService.applySettings(this);
        selectPreset(sp.getInt("preset", VoiceEngine.PRESET_2011X), false);
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }
    @Override protected void onDestroy() { super.onDestroy(); if (player != null) player.release(); }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(28), pad, dp(40));
        scroll.addView(root);

        TextView title = text("CHAOS VOICE", 30, TEXT, true);
        title.setLetterSpacing(0.12f);
        root.addView(title);
        root.addView(text("Live voice changer · 2011X & Fleetway Super Sonic", 14, DIM, false));
        space(root, 18);

        // presets
        root.addView(text("CHOOSE YOUR FILTER", 13, DIM, true));
        space(root, 8);
        for (int i = 1; i <= 2; i++) root.addView(presetButton(i, true));
        root.addView(presetButton(0, false));
        presetDesc = text("", 14, DIM, false);
        presetDesc.setPadding(dp(4), dp(8), dp(4), 0);
        root.addView(presetDesc);
        space(root, 16);

        // live controls
        LinearLayout live = card(root);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        live.addView(meter, new LinearLayout.LayoutParams(-1, dp(10)));
        space(live, 10);
        liveBtn = bigButton("START LIVE VOICE", RED);
        liveBtn.setOnClickListener(v -> withMic(VoiceService.monitor ? VoiceService.ACTION_LIVE_OFF : VoiceService.ACTION_LIVE_ON));
        live.addView(liveBtn);
        space(live, 10);

        Switch speaker = new Switch(this);
        speaker.setText("Speaker mode (no headphones, less echo)");
        speaker.setTextColor(TEXT);
        speaker.setChecked(sp.getBoolean("speaker", false));
        speaker.setOnCheckedChangeListener((CompoundButton c, boolean on) -> {
            sp.edit().putBoolean("speaker", on).apply();
            if (VoiceService.running) VoiceService.send(this, VoiceService.ACTION_RESTART_AUDIO);
        });
        live.addView(speaker);
        live.addView(text("Use headphones for live voice — the phone speaker into its own mic causes squealing.", 12, DIM, false));
        space(root, 14);

        // tweaks
        LinearLayout tw = card(root);
        tw.addView(text("TWEAK", 13, DIM, true));
        pitchLabel = text("", 14, TEXT, false);
        tw.addView(pitchLabel);
        tw.addView(slider(120, Math.round(sp.getFloat("pitch", 0f) * 10) + 60, p -> {
            sp.edit().putFloat("pitch", (p - 60) / 10f).apply();
            update();
        }));
        intensityLabel = text("", 14, TEXT, false);
        tw.addView(intensityLabel);
        tw.addView(slider(150, Math.round(sp.getFloat("intensity", 1f) * 100), p -> {
            sp.edit().putFloat("intensity", p / 100f).apply();
            update();
        }));
        micLabel = text("", 14, TEXT, false);
        tw.addView(micLabel);
        tw.addView(slider(40, Math.round(sp.getFloat("mic", 1f) * 10), p -> {
            sp.edit().putFloat("mic", Math.max(1, p) / 10f).apply();
            update();
        }));
        Button reset = smallButton("Reset tweaks", GREY);
        reset.setOnClickListener(v -> {
            sp.edit().putFloat("pitch", 0f).putFloat("intensity", 1f).putFloat("mic", 1f).apply();
            recreate();
        });
        tw.addView(reset);
        space(root, 14);

        // clips
        LinearLayout clips = card(root);
        clips.addView(text("VOICE CLIPS (send as voice messages)", 13, DIM, true));
        space(clips, 8);
        recBtn = bigButton("● RECORD CLIP", 0xFF8B1E2B);
        recBtn.setOnClickListener(v -> withMic(VoiceService.recording ? VoiceService.ACTION_REC_STOP : VoiceService.ACTION_REC_START));
        clips.addView(recBtn);
        space(clips, 8);
        playBtn = smallButton("▶ Play last clip", GREY);
        playBtn.setOnClickListener(v -> playClip());
        clips.addView(playBtn);
        space(clips, 8);
        LinearLayout row1 = row(clips);
        row1.addView(shareButton("WhatsApp", "com.whatsapp", 0xFF1FA855), weight());
        row1.addView(shareButton("Signal", "org.thoughtcrime.securesms", 0xFF3A76F0), weight());
        LinearLayout row2 = row(clips);
        row2.addView(shareButton("Discord", "com.discord", 0xFF5865F2), weight());
        row2.addView(shareButton("Other…", null, GREY), weight());
        space(root, 12);

        statusText = text("", 13, GOLD, false);
        root.addView(statusText);
        space(root, 12);

        Button help = smallButton("How to use in Discord / Roblox / WhatsApp / Signal", 0xFF2A2A36);
        help.setOnClickListener(v -> showHelp());
        root.addView(help);
        return scroll;
    }

    private Button presetButton(int id, boolean big) {
        Button b = new Button(this);
        b.setText(VoiceEngine.PRESET_NAMES[id]);
        b.setAllCaps(true);
        b.setTextSize(big ? 20 : 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(12), dp(big ? 18 : 10), dp(12), dp(big ? 18 : 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> selectPreset(id, true));
        while (presetButtons.size() <= id) presetButtons.add(null);
        presetButtons.set(id, b);
        return b;
    }

    private void selectPreset(int id, boolean save) {
        if (save) {
            sp.edit().putInt("preset", id).apply();
            if (VoiceService.running) { // refresh the notification title
                Intent i = new Intent(this, VoiceService.class)
                        .setAction(VoiceService.ACTION_PRESET).putExtra(VoiceService.EXTRA_PRESET, id);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            }
        }
        for (int i = 0; i < presetButtons.size(); i++) {
            Button b = presetButtons.get(i);
            if (b == null) continue;
            boolean on = i == id;
            int c = PRESET_COLORS[i];
            b.setBackground(round(on ? c : CARD, on ? 0 : c));
            b.setTextColor(on ? (i == VoiceEngine.PRESET_FLEETWAY ? Color.BLACK : Color.WHITE) : c == GREY ? TEXT : c);
        }
        presetDesc.setText(PRESET_DESC[id]);
        update();
    }

    private void update() {
        float pitch = sp.getFloat("pitch", 0f);
        pitchLabel.setText(String.format(java.util.Locale.US, "Pitch tweak: %+.1f semitones", pitch));
        intensityLabel.setText("Effect strength: " + Math.round(sp.getFloat("intensity", 1f) * 100) + "%");
        micLabel.setText(String.format(java.util.Locale.US, "Mic boost: %.1fx", sp.getFloat("mic", 1f)));
        VoiceService.applySettings(this); // the audio thread picks this up on its next 10 ms block
    }

    private void refresh() {
        boolean live = VoiceService.monitor && VoiceService.running;
        liveBtn.setText(live ? "■ STOP LIVE VOICE" : "START LIVE VOICE");
        liveBtn.setBackground(round(live ? 0xFF3A3A46 : RED, 0));
        recBtn.setText(VoiceService.recording ? "■ STOP & SAVE CLIP" : "● RECORD CLIP");
        meter.setProgress(Math.min(100, Math.round(VoiceService.level * 100)));
        File clip = VoiceService.lastClip;
        String st = VoiceService.status;
        statusText.setText(clip != null && !VoiceService.recording && (st == null || st.isEmpty()) ? "Last clip: " + clip.getName() : st);
        int preset = sp.getInt("preset", 1);
        // a preset switched from the notification
        if (presetDesc != null && !PRESET_DESC[preset].contentEquals(presetDesc.getText())) selectPreset(preset, false);
    }

    // ------------------------------------------------------------------ actions

    private void withMic(String action) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = action;
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(perms.toArray(new String[0]), 1);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !sp.getBoolean("asked_notif", false)) {
            sp.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        if (VoiceService.ACTION_REC_START.equals(action)) VoiceService.status = "";
        VoiceService.send(this, action);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 1) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && pendingAction != null) {
                VoiceService.send(this, pendingAction);
            } else {
                Toast.makeText(this, "Microphone permission is needed to change your voice", Toast.LENGTH_LONG).show();
            }
            pendingAction = null;
        }
    }

    private void playClip() {
        File clip = VoiceService.lastClip;
        if (clip == null || !clip.exists()) { toast("Record a clip first"); return; }
        try {
            if (player != null) player.release();
            player = new MediaPlayer();
            player.setDataSource(clip.getPath());
            player.prepare();
            player.start();
        } catch (Exception e) {
            toast("Can't play: " + e.getMessage());
        }
    }

    private Button shareButton(String label, String pkg, int color) {
        Button b = smallButton(label, color);
        b.setOnClickListener(v -> share(pkg));
        return b;
    }

    private void share(String pkg) {
        File clip = VoiceService.lastClip;
        if (clip == null || !clip.exists()) { toast("Record a clip first"); return; }
        Uri uri = ClipProvider.uriFor(clip);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(ClipProvider.mimeFor(clip.getName()));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri(clip.getName(), uri));
        if (pkg != null) {
            send.setPackage(pkg);
            try { startActivity(send); return; } catch (ActivityNotFoundException e) {
                toast(pkgName(pkg) + " isn't installed — pick another app");
                send.setPackage(null);
            }
        }
        startActivity(Intent.createChooser(send, "Send voice clip"));
    }

    private static String pkgName(String pkg) {
        if (pkg.contains("whatsapp")) return "WhatsApp";
        if (pkg.contains("securesms")) return "Signal";
        if (pkg.contains("discord")) return "Discord";
        return "That app";
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Using it with other apps")
                .setMessage(
                        "VOICE MESSAGES (works on any phone)\n" +
                        "Record a clip with a filter, then tap WhatsApp, Signal or Discord to send it.\n\n" +
                        "LIVE CALLS & ROBLOX VOICE CHAT\n" +
                        "Android does not let normal apps replace the microphone that Discord, Roblox, WhatsApp " +
                        "or Signal hear. While another app is in a call it usually takes the mic, so the filter " +
                        "can't reach the call from inside the same phone.\n\n" +
                        "Ways to use it live:\n" +
                        "• Two devices: run Chaos Voice on this phone in Speaker mode (or with an aux/USB cable) " +
                        "next to the mic of the device that's in the call (PC, tablet, console).\n" +
                        "• Rooted phones can route audio with special system mods (not included).\n\n" +
                        "Live mode on its own lets you hear yourself as 2011X or Fleetway through headphones — " +
                        "great for practising, streaming with a cable, or recording.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------------ view helpers

    private interface IntCb { void on(int p); }

    private SeekBar slider(int max, int value, IntCb cb) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(value);
        s.setPadding(dp(8), dp(10), dp(8), dp(14));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { if (fromUser) cb.on(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        return s;
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button bigButton(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(color, 0));
        b.setPadding(0, dp(16), 0, dp(16));
        return b;
    }

    private Button smallButton(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(color == GOLD ? Color.BLACK : Color.WHITE);
        b.setBackground(round(color, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(round(CARD, 0));
        parent.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private void space(LinearLayout parent, int h) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h)));
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
