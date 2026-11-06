package __PACKAGE__;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeAudioPlugin.class);
        super.onCreate(savedInstanceState);
        WebView wv = safeWebView();
        if (wv != null) wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
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
