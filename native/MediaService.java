package __PACKAGE__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;

/**
 * Foreground service that keeps the app alive while music plays and shows
 * lock-screen / notification controls through a MediaSession.
 */
public class MediaService extends Service {
    static final String A_UPDATE = "mp.update";
    static final String A_PREV = "mp.prev";
    static final String A_TOGGLE = "mp.toggle";
    static final String A_NEXT = "mp.next";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 4242;

    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private Bitmap art;
    private String title = "";
    private String artist = "";
    private String album = "";
    private boolean playing = false;
    private boolean foreground = false;
    private long pos = 0;
    private long dur = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        session = new MediaSession(this, "MusicPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { NativeAudioPlugin.emit("play"); }
            @Override public void onPause() { NativeAudioPlugin.emit("pause"); }
            @Override public void onStop() { NativeAudioPlugin.emit("pause"); }
            @Override public void onSkipToNext() { NativeAudioPlugin.emit("next"); }
            @Override public void onSkipToPrevious() { NativeAudioPlugin.emit("prev"); }
            @Override public void onSeekTo(long p) { NativeAudioPlugin.emitSeek(p / 1000.0); }
        });
        session.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musicplayer:playback");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (A_UPDATE.equals(action)) {
            title = nz(intent.getStringExtra("title"));
            artist = nz(intent.getStringExtra("artist"));
            album = nz(intent.getStringExtra("album"));
            playing = intent.getBooleanExtra("playing", false);
            pos = intent.getLongExtra("pos", 0L);
            dur = intent.getLongExtra("dur", 0L);
            art = decode(intent.getStringExtra("art"));
            apply();
        } else if (foreground) {
            if (A_PREV.equals(action)) NativeAudioPlugin.emit("prev");
            else if (A_TOGGLE.equals(action)) NativeAudioPlugin.emit("toggle");
            else if (A_NEXT.equals(action)) NativeAudioPlugin.emit("next");
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void apply() {
        updateSession();
        Notification n = buildNotification();
        if (!foreground) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                foreground = true;
            } catch (Exception e) {
                stopSelf();
                return;
            }
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, n);
        }
        if (wakeLock != null) {
            if (playing && !wakeLock.isHeld()) wakeLock.acquire();
            else if (!playing && wakeLock.isHeld()) wakeLock.release();
        }
    }

    private void updateSession() {
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(mb.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO;
        PlaybackState ps = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f)
                .build();
        session.setPlaybackState(ps);
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        int icon = getResources().getIdentifier("ic_stat_music", "drawable", getPackageName());
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText(album.isEmpty() ? null : album)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openAppIntent())
                .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(A_PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", actionIntent(A_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(A_NEXT, 3))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(action);
        return PendingIntent.getService(this, code, i, piFlags());
    }

    private PendingIntent openAppIntent() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i == null) i = new Intent();
        return PendingIntent.getActivity(this, 0, i, piFlags());
    }

    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private static Bitmap decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] d = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(d, 0, d.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try { stopForeground(true); } catch (Exception e) { /* ignore */ }
        foreground = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
