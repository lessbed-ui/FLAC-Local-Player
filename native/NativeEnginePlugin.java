package __PACKAGE__;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.RequiresApi;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Native lossless player. Decodes with MediaCodec and plays through AudioTrack.
 * With bit-perfect on (Android 14+, USB DAC) it asks the system for a bit-perfect mixer
 * so samples reach the DAC unchanged: no resampling, no mixing, no volume scaling.
 */
@CapacitorPlugin(name = "NativeEngine")
public class NativeEnginePlugin extends Plugin {
    private Player cur;
    private int nextId = 0;

    void emit(JSObject o) { notifyListeners("state", o); }

    static AudioDeviceInfo usb(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) return d;
        }
        return null;
    }

    @RequiresApi(34)
    static int countBp(AudioManager am, AudioDeviceInfo d) {
        int n = 0;
        for (AudioMixerAttributes a : am.getSupportedMixerAttributes(d)) {
            if (a.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) n++;
        }
        return n;
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject o = new JSObject();
        o.put("sdk", Build.VERSION.SDK_INT);
        String name = "";
        int n = 0;
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo d = usb(am);
            if (d != null) {
                CharSequence pn = d.getProductName();
                name = pn == null || pn.length() == 0 ? "USB audio" : pn.toString();
                if (Build.VERSION.SDK_INT >= 34) n = countBp(am, d);
            }
        } catch (Exception e) { /* report what we have */ }
        o.put("usb", name);
        o.put("formats", n);
        call.resolve(o);
    }

    @PluginMethod
    public void load(PluginCall call) {
        String u = call.getString("uri");
        if (u == null) { call.reject("no uri"); return; }
        Integer b = call.getInt("bits", 0);
        Player p = new Player(this, call, cur, ++nextId, Uri.parse(u), Boolean.TRUE.equals(call.getBoolean("bp", false)), b == null ? 0 : b);
        cur = p;
        p.start();
    }

    @PluginMethod
    public void pause(PluginCall call) { if (cur != null) cur.setPaused(true); call.resolve(); }

    @PluginMethod
    public void resume(PluginCall call) { if (cur != null) cur.setPaused(false); call.resolve(); }

    @PluginMethod
    public void seek(PluginCall call) {
        Double ms = call.getDouble("ms", 0.0);
        if (cur != null) cur.seekMs = (long) (ms == null ? 0 : ms);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (cur != null) cur.quit = true;
        cur = null;
        call.resolve();
    }

    static class Player extends Thread {
        private static final long MASK = 0xFFFFFFFFL;
        final NativeEnginePlugin pl;
        final PluginCall call;
        final Player prev;
        final int id;
        final Uri uri;
        final boolean wantBp;
        final int bits;
        volatile boolean quit = false, paused = false;
        volatile long seekMs = -1;
        volatile AudioTrack track;
        MediaExtractor ex;
        MediaCodec codec;
        AudioManager am;
        AudioDeviceInfo dev;
        AudioAttributes aa;
        boolean mixerSet = false, bpActive = false;
        int decEnc = AudioFormat.ENCODING_PCM_16BIT, outEnc = AudioFormat.ENCODING_PCM_16BIT, rate = 0, ch = 2, frameBytes = 4;
        long durUs = 0, baseUs = 0, headBase = 0, framesWritten = 0, lastEmit = 0;
        String note = "";
        ByteBuffer conv;

        Player(NativeEnginePlugin pl, PluginCall call, Player prev, int id, Uri uri, boolean wantBp, int bits) {
            this.pl = pl; this.call = call; this.prev = prev; this.id = id; this.uri = uri; this.wantBp = wantBp; this.bits = bits;
        }

        void setPaused(boolean p) {
            paused = p;
            try {
                AudioTrack t = track;
                if (t != null) { if (p) t.pause(); else t.play(); }
            } catch (Exception e) { /* ignore */ }
        }

        private static void nap(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { /* ignore */ }
        }

        private long head() {
            AudioTrack t = track;
            return t == null ? 0 : (t.getPlaybackHeadPosition() & MASK);
        }

        @Override
        public void run() {
            boolean resolved = false;
            try {
                if (prev != null) { prev.quit = true; prev.join(2500); }
                Context ctx = pl.getContext();
                am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                ex = new MediaExtractor();
                ex.setDataSource(ctx, uri, null);
                MediaFormat f = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat t = ex.getTrackFormat(i);
                    String m = t.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) { ex.selectTrack(i); f = t; break; }
                }
                if (f == null) throw new IllegalStateException("no audio track");
                durUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
                codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
                f.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT);
                codec.configure(f, null, null, 0);
                codec.start();
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean inDone = false, outDone = false;
                while (!quit && !outDone) {
                    long s = seekMs;
                    if (s >= 0) {
                        seekMs = -1;
                        AudioTrack t = track;
                        if (t != null) { t.pause(); t.flush(); headBase = head(); framesWritten = 0; }
                        ex.seekTo(s * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        codec.flush();
                        inDone = false;
                        baseUs = s * 1000;
                        if (t != null && !paused) t.play();
                        emitState(true);
                    }
                    if (paused) { nap(20); emitState(false); continue; }
                    if (!inDone) {
                        int ii = codec.dequeueInputBuffer(2000);
                        if (ii >= 0) {
                            ByteBuffer ib = codec.getInputBuffer(ii);
                            int n = ex.readSampleData(ib, 0);
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inDone = true;
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int oi = codec.dequeueOutputBuffer(bi, 2000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        open(codec.getOutputFormat());
                        if (!resolved) { resolved = true; resolveLoad(); }
                    } else if (oi >= 0) {
                        if (track == null) {
                            open(codec.getOutputFormat());
                            if (!resolved) { resolved = true; resolveLoad(); }
                        }
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        if (bi.size > 0) write(ob, bi.offset, bi.size);
                        boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(oi, false);
                        if (eos) outDone = true;
                    }
                    emitState(false);
                }
                AudioTrack t = track;
                if (outDone && !quit && t != null) {
                    t.stop();
                    long waited = 0;
                    while (!quit && waited < 6000 && ((head() - headBase) & MASK) < framesWritten) { nap(40); waited += 40; }
                    if (!quit) emit(true, false);
                }
            } catch (Throwable e) {
                if (!resolved) pl.getBridge().executeOnMainThread(() -> call.reject("engine: " + e.getMessage()));
                else emit(false, true);
            } finally {
                cleanup();
            }
        }

        private void resolveLoad() {
            JSObject r = new JSObject();
            r.put("id", id);
            r.put("durMs", durUs / 1000);
            r.put("bp", bpActive);
            r.put("note", note);
            r.put("rate", rate);
            r.put("depth", outEnc == AudioFormat.ENCODING_PCM_16BIT ? 16 : outEnc == AudioFormat.ENCODING_PCM_24BIT_PACKED ? 24 : 32);
            String dn = "";
            if (dev != null && dev.getProductName() != null) dn = dev.getProductName().toString();
            r.put("dev", dn);
            call.resolve(r);
        }

        private void open(MediaFormat of) {
            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            decEnc = of.containsKey(MediaFormat.KEY_PCM_ENCODING) ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            if (decEnc != AudioFormat.ENCODING_PCM_16BIT && decEnc != AudioFormat.ENCODING_PCM_FLOAT) {
                throw new IllegalStateException("unsupported decoder output " + decEnc);
            }
            AudioTrack old = track;
            track = null;
            if (old != null) old.release();
            outEnc = decEnc;
            bpActive = false;
            note = "";
            if (wantBp) {
                if (Build.VERSION.SDK_INT < 34) note = "Needs Android 14 or newer";
                else if (decEnc == AudioFormat.ENCODING_PCM_16BIT && bits > 16) note = "This phone’s decoder cut the file to 16-bit";
                else {
                    int e = pickBp();
                    if (e != -1) { outEnc = e; bpActive = true; }
                }
            }
            int mask;
            if (ch == 1) mask = AudioFormat.CHANNEL_OUT_MONO;
            else if (ch == 2) mask = AudioFormat.CHANNEL_OUT_STEREO;
            else if (ch == 6) mask = AudioFormat.CHANNEL_OUT_5POINT1;
            else throw new IllegalStateException("unsupported channel count " + ch);
            int per = outEnc == AudioFormat.ENCODING_PCM_16BIT ? 2 : outEnc == AudioFormat.ENCODING_PCM_24BIT_PACKED ? 3 : 4;
            frameBytes = per * ch;
            int min = AudioTrack.getMinBufferSize(rate, mask, outEnc);
            int buf = Math.max(min > 0 ? min : 0, frameBytes * (rate / 4));
            AudioFormat af = new AudioFormat.Builder().setSampleRate(rate).setEncoding(outEnc).setChannelMask(mask).build();
            AudioTrack t = new AudioTrack.Builder().setAudioAttributes(aa).setAudioFormat(af)
                    .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build();
            if (bpActive && dev != null) t.setPreferredDevice(dev);
            t.setVolume(1f);
            track = t;
            headBase = head();
            framesWritten = 0;
            if (!paused) t.play();
        }

        @RequiresApi(34)
        private int pickBp() {
            dev = usb(am);
            if (dev == null) { note = "Plug in your USB DAC"; return -1; }
            if (ch > 2) { note = "Bit-perfect supports mono and stereo files"; return -1; }
            int mask = ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int b = bits <= 0 ? 24 : bits;
            int[] pref = b <= 16
                    ? new int[]{AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT}
                    : b <= 24
                    ? new int[]{AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT}
                    : new int[]{AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT};
            List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(dev);
            for (int enc : pref) {
                for (AudioMixerAttributes a : list) {
                    if (a.getMixerBehavior() != AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) continue;
                    AudioFormat f = a.getFormat();
                    if (f.getSampleRate() == rate && f.getEncoding() == enc && f.getChannelMask() == mask) {
                        if (am.setPreferredMixerAttributes(aa, dev, a)) { mixerSet = true; return enc; }
                    }
                }
            }
            note = list.isEmpty() ? "This phone doesn’t offer bit-perfect over USB"
                    : "Your DAC doesn’t accept " + (rate / 1000.0) + " kHz at this bit depth exactly";
            return -1;
        }

        private void write(ByteBuffer ob, int off, int size) {
            ob.order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer b;
            if (decEnc == outEnc) {
                ob.position(off);
                ob.limit(off + size);
                b = ob;
            } else {
                b = convert(ob, off, size);
            }
            long frames = b.remaining() / frameBytes;
            AudioTrack t = track;
            while (b.hasRemaining() && !quit && seekMs < 0) {
                if (paused) { nap(20); continue; }
                int pos = b.position();
                int w = t.write(b, b.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                if (w < 0) throw new IllegalStateException("write error " + w);
                if (w == 0) nap(5);
                else b.position(pos + w);
            }
            framesWritten += frames;
        }

        /** Lossless sample-format conversion between decoder output and the output format. */
        private ByteBuffer convert(ByteBuffer src, int off, int size) {
            boolean fl = decEnc == AudioFormat.ENCODING_PCM_FLOAT;
            int n = size / (fl ? 4 : 2);
            int per = outEnc == AudioFormat.ENCODING_PCM_16BIT ? 2 : outEnc == AudioFormat.ENCODING_PCM_24BIT_PACKED ? 3 : 4;
            if (conv == null || conv.capacity() < n * per) conv = ByteBuffer.allocateDirect(n * per).order(ByteOrder.LITTLE_ENDIAN);
            conv.clear();
            for (int i = 0; i < n; i++) {
                float f = fl ? src.getFloat(off + i * 4) : 0f;
                int s = fl ? 0 : src.getShort(off + i * 2);
                if (outEnc == AudioFormat.ENCODING_PCM_16BIT) {
                    int v = Math.round(f * 32768f);
                    conv.putShort((short) Math.max(-32768, Math.min(32767, v)));
                } else if (outEnc == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                    int v = fl ? Math.max(-8388608, Math.min(8388607, Math.round(f * 8388608f))) : (s << 8);
                    conv.put((byte) v).put((byte) (v >> 8)).put((byte) (v >> 16));
                } else if (outEnc == AudioFormat.ENCODING_PCM_32BIT) {
                    int v = fl ? (int) Math.max(-2147483648L, Math.min(2147483647L, Math.round((double) f * 2147483648.0))) : (s << 16);
                    conv.putInt(v);
                } else {
                    conv.putFloat(s / 32768f);
                }
            }
            conv.flip();
            return conv;
        }

        private void emitState(boolean force) {
            long now = System.nanoTime();
            if (!force && now - lastEmit < 250_000_000L) return;
            lastEmit = now;
            emit(false, false);
        }

        private void emit(boolean ended, boolean err) {
            JSObject o = new JSObject();
            long posUs = baseUs + (rate > 0 ? ((head() - headBase) & MASK) * 1000000L / rate : 0);
            if (durUs > 0 && posUs > durUs) posUs = durUs;
            o.put("id", id);
            o.put("pos", posUs / 1000);
            o.put("dur", durUs / 1000);
            o.put("playing", !paused && !ended && !err);
            o.put("ended", ended);
            o.put("error", err);
            pl.emit(o);
        }

        private void cleanup() {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception e) { /* ignore */ }
            try { if (ex != null) ex.release(); } catch (Exception e) { /* ignore */ }
            AudioTrack t = track;
            track = null;
            try { if (t != null) { t.stop(); t.release(); } } catch (Exception e) { /* ignore */ }
            try {
                if (mixerSet && Build.VERSION.SDK_INT >= 34 && dev != null) am.clearPreferredMixerAttributes(aa, dev);
            } catch (Exception e) { /* ignore */ }
        }
    }
}
