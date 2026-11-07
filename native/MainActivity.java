package __PACKAGE__;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeAudioPlugin.class);
        registerPlugin(MusicLibraryPlugin.class);
        super.onCreate(savedInstanceState);
        WebView wv = safeWebView();
        if (wv != null) wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
        goFullscreen();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    /** Hide the status and navigation bars (swipe from an edge to peek at them). */
    private void goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    /** Back button / back gesture: let the web UI close its current screen first; otherwise go to the home screen and keep playing. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        WebView wv = safeWebView();
        if (wv == null) { moveTaskToBack(true); return; }
        wv.evaluateJavascript("(function(){return !!(window.__back&&window.__back())})()", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    // Android pauses the web view when the screen locks; keep it running while music plays.
    @Override
    public void onPause() {
        super.onPause();
        keepAlive();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepAlive();
    }

    private void keepAlive() {
        if (!NativeAudioPlugin.playing) return;
        WebView wv = safeWebView();
        if (wv != null) {
            wv.onResume();
            wv.resumeTimers();
        }
    }

    private WebView safeWebView() {
        return getBridge() != null ? getBridge().getWebView() : null;
    }
}
