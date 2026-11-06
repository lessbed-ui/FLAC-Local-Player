package __PACKAGE__;

import android.content.Intent;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Bridge between the web player and the Android background-playback service. */
@CapacitorPlugin(name = "NativeAudio")
public class NativeAudioPlugin extends Plugin {
    static NativeAudioPlugin instance;
    static volatile boolean playing = false;

    @Override
    public void load() {
        instance = this;
    }

    @PluginMethod
    public void update(PluginCall call) {
        boolean isPlaying = Boolean.TRUE.equals(call.getBoolean("playing", false));
        playing = isPlaying;
        Intent i = new Intent(getContext(), MediaService.class);
        i.setAction(MediaService.A_UPDATE);
        i.putExtra("title", call.getString("title", ""));
        i.putExtra("artist", call.getString("artist", ""));
        i.putExtra("album", call.getString("album", ""));
        i.putExtra("art", call.getString("art", ""));
        i.putExtra("playing", isPlaying);
        i.putExtra("pos", (long) (call.getDouble("position", 0.0) * 1000));
        i.putExtra("dur", (long) (call.getDouble("duration", 0.0) * 1000));
        try {
            ContextCompat.startForegroundService(getContext(), i);
        } catch (Exception e) {
            // Android refused to start the service; playback just won't survive in the background.
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        playing = false;
        try {
            getContext().stopService(new Intent(getContext(), MediaService.class));
        } catch (Exception e) {
            // ignore
        }
        call.resolve();
    }

    static void emit(String action) {
        if (instance == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        instance.notifyListeners("action", o);
    }

    static void emitSeek(double seconds) {
        if (instance == null) return;
        JSObject o = new JSObject();
        o.put("action", "seek");
        o.put("position", seconds);
        instance.notifyListeners("action", o);
    }
}
