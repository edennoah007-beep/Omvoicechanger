package com.edennoah.voicechanger;

/**
 * Real-time voice effect engine (pure Java, no Android imports so it can be tested on a PC).
 *
 * Signal chain:
 *   mic gain -> noise gate -> high-pass -> [pitch layer 1 + pitch layer 2 + dry]
 *   -> drive (saturation) -> ring-mod growl -> EQ (low shelf, presence, air, low-pass)
 *   -> echo -> reverb -> soft limiter
 */
public final class VoiceEngine {

    /** All the knobs of one voice filter. */
    public static final class Params {
        public String name = "Normal";
        // Pitch layers (semitones) and their levels
        public float pitch1 = 0f, gain1 = 0f;
        public float pitch2 = 0f, gain2 = 0f;
        public float dry = 1f;
        public int windowMs1 = 40, windowMs2 = 40;
        // Saturation: 1 = clean, higher = more grit
        public float drive = 1f;
        // Ring modulator (growl / robotic wobble)
        public float ringHz = 0f, ringMix = 0f;
        // EQ
        public float hpHz = 60f, lpHz = 16000f;
        public float lowShelfHz = 150f, lowShelfDb = 0f;
        public float presenceHz = 2800f, presenceDb = 0f, presenceQ = 0.9f;
        public float airHz = 7000f, airDb = 0f;
        // Echo
        public float echoMs = 0f, echoFb = 0f, echoMix = 0f;
        // Reverb
        public float reverbMix = 0f, reverbSize = 0.7f, reverbDamp = 0.4f;
        // Noise gate threshold in dBFS (very negative = off)
        public float gateDb = -60f;
        public float outGain = 1f;

        public Params copy() {
            Params p = new Params();
            p.name = name; p.pitch1 = pitch1; p.gain1 = gain1; p.pitch2 = pitch2; p.gain2 = gain2;
            p.dry = dry; p.windowMs1 = windowMs1; p.windowMs2 = windowMs2; p.drive = drive;
            p.ringHz = ringHz; p.ringMix = ringMix; p.hpHz = hpHz; p.lpHz = lpHz;
            p.lowShelfHz = lowShelfHz; p.lowShelfDb = lowShelfDb; p.presenceHz = presenceHz;
            p.presenceDb = presenceDb; p.presenceQ = presenceQ; p.airHz = airHz; p.airDb = airDb;
            p.echoMs = echoMs; p.echoFb = echoFb; p.echoMix = echoMix; p.reverbMix = reverbMix;
            p.reverbSize = reverbSize; p.reverbDamp = reverbDamp; p.gateDb = gateDb; p.outGain = outGain;
            return p;
        }
    }

    // ------------------------------------------------------------------ presets

    public static final int PRESET_NORMAL = 0;
    public static final int PRESET_2011X = 1;
    public static final int PRESET_FLEETWAY = 2;
    public static final String[] PRESET_NAMES = {"Normal (off)", "2011X", "Fleetway Super Sonic"};

    /**
     * 2011X (Sonic.exe): deep, demonic, distorted, echoing "god voice".
     * Two pitched-down layers (-7 and -12 st) stacked, a faint ghost of the real voice,
     * heavy saturation, slow ring-mod growl, boosted lows, dark low-pass, long echo + big dark reverb.
     */
    public static Params preset2011X() {
        Params p = new Params();
        p.name = "2011X";
        p.pitch1 = -7f;  p.gain1 = 0.85f; p.windowMs1 = 50;
        p.pitch2 = -12f; p.gain2 = 0.55f; p.windowMs2 = 60;
        p.dry = 0.12f;
        p.drive = 3.2f;
        p.ringHz = 34f; p.ringMix = 0.22f;
        p.hpHz = 55f; p.lpHz = 5200f;
        p.lowShelfHz = 160f; p.lowShelfDb = 5f;
        p.presenceHz = 1200f; p.presenceDb = 2f; p.presenceQ = 0.8f;
        p.airHz = 7000f; p.airDb = -4f;
        p.echoMs = 190f; p.echoFb = 0.28f; p.echoMix = 0.22f;
        p.reverbMix = 0.28f; p.reverbSize = 0.72f; p.reverbDamp = 0.6f;
        p.gateDb = -48f;
        p.outGain = 0.9f;
        return p;
    }

    /**
     * Fleetway Super Sonic: crazed, raspy, higher-than-Sonic voice with an unhinged double.
     * Main layer +3 st (Sonic sits about +2..+4 st), detuned double at +4.5 st, raspy saturation,
     * high-pass at 180 Hz, strong 3 kHz bite + air, short slap echo, small bright room.
     */
    public static Params presetFleetway() {
        Params p = new Params();
        p.name = "Fleetway Super Sonic";
        p.pitch1 = 3f;   p.gain1 = 0.9f;  p.windowMs1 = 30;
        p.pitch2 = 4.5f; p.gain2 = 0.38f; p.windowMs2 = 24;
        p.dry = 0f;
        p.drive = 2.3f;
        p.ringHz = 0f; p.ringMix = 0f;
        p.hpHz = 180f; p.lpHz = 12000f;
        p.lowShelfHz = 300f; p.lowShelfDb = -2f;
        p.presenceHz = 3000f; p.presenceDb = 5f; p.presenceQ = 0.9f;
        p.airHz = 7000f; p.airDb = 2.5f;
        p.echoMs = 95f; p.echoFb = 0.18f; p.echoMix = 0.16f;
        p.reverbMix = 0.13f; p.reverbSize = 0.45f; p.reverbDamp = 0.25f;
        p.gateDb = -50f;
        p.outGain = 0.95f;
        return p;
    }

    public static Params presetNormal() {
        Params p = new Params();
        p.name = "Normal";
        return p;
    }

    public static Params preset(int id) {
        switch (id) {
            case PRESET_2011X: return preset2011X();
            case PRESET_FLEETWAY: return presetFleetway();
            default: return presetNormal();
        }
    }

    /**
     * Builds the active params from a preset plus the user's tweak sliders.
     * @param pitchTweak semitones added to both pitch layers (-6..+6)
     * @param intensity  0..1.5 scale for grit, growl, echo and reverb (1 = preset as designed)
     */
    public static Params withTweaks(Params base, float pitchTweak, float intensity) {
        Params p = base.copy();
        if (base.gain1 > 0f || base.gain2 > 0f) {
            p.pitch1 += pitchTweak;
            p.pitch2 += pitchTweak;
        } else if (pitchTweak != 0f) {
            // Normal preset: the tweak slider acts as a simple pitch shifter
            p.pitch1 = pitchTweak; p.gain1 = 1f; p.dry = 0f;
        }
        p.drive = 1f + (base.drive - 1f) * intensity;
        p.ringMix = clamp(base.ringMix * intensity, 0f, 0.6f);
        p.echoMix = clamp(base.echoMix * intensity, 0f, 0.6f);
        p.echoFb = clamp(base.echoFb * (0.5f + 0.5f * intensity), 0f, 0.7f);
        p.reverbMix = clamp(base.reverbMix * intensity, 0f, 0.6f);
        return p;
    }

    // ------------------------------------------------------------------ state

    private final int sr;
    private volatile Params pending;
    private Params p;
    private float micGain = 1f;

    private final PitchShifter shift1, shift2;
    private final Biquad hp = new Biquad(), lp = new Biquad(), lowShelf = new Biquad(),
            presence = new Biquad(), air = new Biquad(), postDriveLp = new Biquad();
    private final float[] echoBuf;
    private int echoPos;
    private final Reverb reverb;
    private double ringPhase;
    private float gateEnv, gateGain = 1f;

    public VoiceEngine(int sampleRate) {
        sr = sampleRate;
        shift1 = new PitchShifter(sampleRate);
        shift2 = new PitchShifter(sampleRate);
        echoBuf = new float[sampleRate]; // up to 1 s
        reverb = new Reverb(sampleRate);
        p = presetNormal();
        applyParams(p);
    }

    /** Thread-safe: takes effect at the start of the next buffer. */
    public void setParams(Params params) { pending = params.copy(); }

    public void setMicGain(float g) { micGain = g; }

    public String currentName() { return p.name; }

    private void applyParams(Params n) {
        p = n;
        shift1.setWindow(n.windowMs1);
        shift2.setWindow(n.windowMs2);
        hp.highPass(sr, n.hpHz, 0.707f);
        lp.lowPass(sr, Math.min(n.lpHz, sr * 0.45f), 0.707f);
        postDriveLp.lowPass(sr, Math.min(9000f, sr * 0.45f), 0.6f);
        lowShelf.lowShelf(sr, n.lowShelfHz, n.lowShelfDb);
        presence.peaking(sr, n.presenceHz, n.presenceQ, n.presenceDb);
        air.highShelf(sr, n.airHz, n.airDb);
        reverb.set(n.reverbSize, n.reverbDamp);
    }

    /** Processes samples in place (float, -1..1). */
    public void process(float[] buf, int n) {
        Params np = pending;
        if (np != null) { pending = null; applyParams(np); }
        final Params q = p;
        final float r1 = (float) Math.pow(2.0, q.pitch1 / 12.0);
        final float r2 = (float) Math.pow(2.0, q.pitch2 / 12.0);
        final float gateThr = (float) Math.pow(10.0, q.gateDb / 20.0);
        final float atk = 1f - (float) Math.exp(-1.0 / (0.002 * sr));
        final float rel = 1f - (float) Math.exp(-1.0 / (0.120 * sr));
        final float envRel = 1f - (float) Math.exp(-1.0 / (0.050 * sr));
        final double ringInc = 2.0 * Math.PI * q.ringHz / sr;
        final float driveNorm = q.drive > 1.01f ? (float) (1.0 / Math.tanh(q.drive)) : 1f;
        int echoLen = (int) (q.echoMs * 0.001f * sr);
        if (echoLen >= echoBuf.length) echoLen = echoBuf.length - 1;
        final boolean bypass = q.gain1 == 0f && q.gain2 == 0f && q.dry == 1f && q.drive <= 1.01f
                && q.ringMix == 0f && q.echoMix == 0f && q.reverbMix == 0f;

        for (int i = 0; i < n; i++) {
            float x = buf[i] * micGain;

            // noise gate (keeps hiss from being distorted into noise)
            float a = Math.abs(x);
            gateEnv += (a > gateEnv ? atk : envRel) * (a - gateEnv);
            float target = gateEnv > gateThr ? 1f : 0f;
            gateGain += (target > gateGain ? atk : rel) * (target - gateGain);
            x *= gateGain;

            if (bypass) { buf[i] = softLimit(x); continue; }

            x = hp.process(x);

            float y = q.dry * x;
            if (q.gain1 > 0f) y += q.gain1 * shift1.process(x, r1); else shift1.push(x);
            if (q.gain2 > 0f) y += q.gain2 * shift2.process(x, r2); else shift2.push(x);

            if (q.drive > 1.01f) {
                y = (float) Math.tanh(y * q.drive) * driveNorm * 0.8f;
                y = postDriveLp.process(y); // tame fizz
            }

            if (q.ringMix > 0f) {
                y *= (1f - q.ringMix) + q.ringMix * (float) Math.sin(ringPhase);
                ringPhase += ringInc;
                if (ringPhase > 2.0 * Math.PI) ringPhase -= 2.0 * Math.PI;
            }

            y = lowShelf.process(y);
            y = presence.process(y);
            y = air.process(y);
            y = lp.process(y);

            if (q.echoMix > 0f && echoLen > 0) {
                int rp = echoPos - echoLen; if (rp < 0) rp += echoBuf.length;
                float e = echoBuf[rp];
                echoBuf[echoPos] = y + e * q.echoFb;
                if (++echoPos >= echoBuf.length) echoPos = 0;
                y += e * q.echoMix;
            }

            if (q.reverbMix > 0f) {
                y += reverb.process(y) * q.reverbMix;
            }

            buf[i] = softLimit(y * q.outGain);
        }
    }

    private static float softLimit(float x) {
        if (x > 0.9f || x < -0.9f) return (float) Math.tanh(x);
        return x;
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // ------------------------------------------------------------------ building blocks

    /** Low-latency dual-tap delay-line pitch shifter with sin^2 crossfades. */
    static final class PitchShifter {
        private final float[] buf;
        private final int mask;
        private int w;
        private float window;
        private float phase;
        private final int sr;

        PitchShifter(int sampleRate) {
            sr = sampleRate;
            int size = 1; while (size < sampleRate / 4) size <<= 1; // > 250 ms
            buf = new float[size];
            mask = size - 1;
            setWindow(40);
        }

        void setWindow(int ms) { window = Math.max(256, ms * 0.001f * sr); }

        void push(float x) { buf[w] = x; w = (w + 1) & mask; }

        float process(float x, float ratio) {
            buf[w] = x;
            phase += (1f - ratio) / window;
            phase -= (float) Math.floor(phase);
            float p2 = phase + 0.5f; if (p2 >= 1f) p2 -= 1f;
            float s1 = (float) Math.sin(Math.PI * phase);
            float s2 = (float) Math.sin(Math.PI * p2);
            float out = read(phase * window + 1f) * s1 * s1 + read(p2 * window + 1f) * s2 * s2;
            w = (w + 1) & mask;
            return out;
        }

        private float read(float delay) {
            float pos = w - delay;
            int i0 = (int) Math.floor(pos);
            float frac = pos - i0;
            float a = buf[i0 & mask], b = buf[(i0 + 1) & mask];
            return a + (b - a) * frac;
        }
    }

    /** RBJ cookbook biquad. */
    static final class Biquad {
        float b0 = 1, b1, b2, a1, a2, z1, z2;

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        private void set(double b0_, double b1_, double b2_, double a0, double a1_, double a2_) {
            b0 = (float) (b0_ / a0); b1 = (float) (b1_ / a0); b2 = (float) (b2_ / a0);
            a1 = (float) (a1_ / a0); a2 = (float) (a2_ / a0);
        }

        void lowPass(int sr, float f, float q) {
            double w0 = 2 * Math.PI * f / sr, c = Math.cos(w0), al = Math.sin(w0) / (2 * q);
            set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
        }

        void highPass(int sr, float f, float q) {
            double w0 = 2 * Math.PI * f / sr, c = Math.cos(w0), al = Math.sin(w0) / (2 * q);
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
        }

        void peaking(int sr, float f, float q, float db) {
            double A = Math.pow(10, db / 40.0), w0 = 2 * Math.PI * f / sr;
            double c = Math.cos(w0), al = Math.sin(w0) / (2 * q);
            set(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
        }

        void lowShelf(int sr, float f, float db) {
            double A = Math.pow(10, db / 40.0), w0 = 2 * Math.PI * f / sr;
            double c = Math.cos(w0), s = Math.sin(w0), al = s / 2 * Math.sqrt(2), sq = 2 * Math.sqrt(A) * al;
            set(A * ((A + 1) - (A - 1) * c + sq), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sq),
                (A + 1) + (A - 1) * c + sq, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sq);
        }

        void highShelf(int sr, float f, float db) {
            double A = Math.pow(10, db / 40.0), w0 = 2 * Math.PI * f / sr;
            double c = Math.cos(w0), s = Math.sin(w0), al = s / 2 * Math.sqrt(2), sq = 2 * Math.sqrt(A) * al;
            set(A * ((A + 1) + (A - 1) * c + sq), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sq),
                (A + 1) - (A - 1) * c + sq, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sq);
        }
    }

    /** Small mono Freeverb-style reverb: 4 damped combs + 2 allpasses. */
    static final class Reverb {
        private final float[][] comb = new float[4][];
        private final int[] combPos = new int[4];
        private final float[] combStore = new float[4];
        private final float[][] ap = new float[2][];
        private final int[] apPos = new int[2];
        private float fb = 0.8f, damp = 0.3f;

        Reverb(int sr) {
            int[] cl = {1557, 1617, 1491, 1422};
            int[] al = {556, 441};
            double s = sr / 44100.0;
            for (int i = 0; i < 4; i++) comb[i] = new float[(int) (cl[i] * s)];
            for (int i = 0; i < 2; i++) ap[i] = new float[(int) (al[i] * s)];
        }

        void set(float size, float d) { fb = 0.7f + 0.28f * size; damp = d; }

        float process(float x) {
            float in = x * 0.25f, out = 0f;
            for (int i = 0; i < 4; i++) {
                float[] b = comb[i];
                float y = b[combPos[i]];
                combStore[i] = y * (1f - damp) + combStore[i] * damp;
                b[combPos[i]] = in + combStore[i] * fb;
                if (++combPos[i] >= b.length) combPos[i] = 0;
                out += y;
            }
            for (int i = 0; i < 2; i++) {
                float[] b = ap[i];
                float bo = b[apPos[i]];
                b[apPos[i]] = out + bo * 0.5f;
                if (++apPos[i] >= b.length) apPos[i] = 0;
                out = bo - out;
            }
            return out;
        }
    }
}
