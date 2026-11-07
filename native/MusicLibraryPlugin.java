package __PACKAGE__;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.*;
import java.util.*;

/** Lets the user pick a music folder, scans it, and copies the playing song to a readable temp file. */
@CapacitorPlugin(name = "MusicLibrary")
public class MusicLibraryPlugin extends Plugin {
    private static final Set<String> EXT = new HashSet<>(Arrays.asList("flac", "mp3", "wav", "ogg", "opus", "m4a", "aac"));
    private int seq = 0;

    private SharedPreferences prefs() { return getContext().getSharedPreferences("library", Context.MODE_PRIVATE); }
    private File libFile() { return new File(getContext().getFilesDir(), "library.json"); }

    private JSObject folderInfo() {
        JSObject o = new JSObject();
        String t = prefs().getString("tree", null);
        if (t == null) return o;
        Uri u = Uri.parse(t);
        boolean ok = false;
        for (android.content.UriPermission p : getContext().getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission()) ok = true;
        }
        if (!ok) return o;
        String id = DocumentsContract.getTreeDocumentId(u);
        int c = id.indexOf(':');
        o.put("uri", t);
        o.put("name", c >= 0 && c < id.length() - 1 ? id.substring(c + 1) : id);
        return o;
    }

    @PluginMethod
    public void getFolder(PluginCall call) { call.resolve(folderInfo()); }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, i, "pickResult");
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult r) {
        if (call == null) return;
        if (r.getResultCode() != Activity.RESULT_OK || r.getData() == null || r.getData().getData() == null) {
            call.reject("cancelled");
            return;
        }
        Uri u = r.getData().getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) { /* ignore */ }
        prefs().edit().putString("tree", u.toString()).apply();
        libFile().delete();
        call.resolve(folderInfo());
    }

    @PluginMethod
    public void scan(PluginCall call) {
        final boolean force = Boolean.TRUE.equals(call.getBoolean("force", false));
        new Thread(() -> {
            try {
                if (!force && libFile().exists()) {
                    try (BufferedReader br = new BufferedReader(new FileReader(libFile()))) {
                        JSObject cached = new JSObject(br.readLine());
                        if (cached.optInt("v") == 2) { call.resolve(cached); return; }
                    } catch (Exception e) { /* rescan below */ }
                }
                String t = prefs().getString("tree", null);
                if (t == null) { call.reject("no folder"); return; }
                Uri tree = Uri.parse(t);
                List<Object[]> docs = new ArrayList<>();
                walk(getContext().getContentResolver(), tree, DocumentsContract.getTreeDocumentId(tree), docs);
                JSArray arr = new JSArray();
                for (Object[] d : docs) arr.put(meta((Uri) d[0], (String) d[1], (Long) d[2]));
                JSObject res = new JSObject();
                res.put("tracks", arr);
                res.put("v", 2);
                try (FileWriter fw = new FileWriter(libFile())) { fw.write(res.toString()); } catch (Exception e) { /* cache is optional */ }
                call.resolve(res);
            } catch (Exception e) {
                call.reject("scan failed: " + e.getMessage());
            }
        }).start();
    }

    private void walk(ContentResolver cr, Uri tree, String docId, List<Object[]> out) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = cr.query(kids, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (Document.MIME_TYPE_DIR.equals(mime)) walk(cr, tree, id, out);
                else if (EXT.contains(ext(name))) out.add(new Object[]{DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3)});
            }
        } catch (Exception e) { /* skip unreadable folder */ }
    }

    private static String ext(String n) {
        int d = n == null ? -1 : n.lastIndexOf('.');
        return d < 0 ? "" : n.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    private JSObject meta(Uri uri, String name, long size) {
        JSObject o = new JSObject();
        String ext = ext(name);
        String title = null, artist = null, album = null;
        double len = 0;
        int rate = 0, bits = 0, ch = 0;
        String art = "";
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(getContext(), uri);
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (artist == null) artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) len = Long.parseLong(d) / 1000.0;
            if (Build.VERSION.SDK_INT >= 31) {
                String sr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                String bp = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE);
                if (sr != null) rate = Integer.parseInt(sr);
                if (bp != null) bits = Integer.parseInt(bp);
            }
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null && pic.length > 0) {
                File dir = new File(getContext().getFilesDir(), "art");
                dir.mkdirs();
                File f = new File(dir, Integer.toHexString(Arrays.hashCode(pic)) + "_" + pic.length + ".img");
                if (!f.exists()) try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(pic); }
                art = f.getAbsolutePath();
            }
        } catch (Exception e) { /* keep defaults */ } finally {
            try { r.release(); } catch (Exception e) { /* ignore */ }
        }
        if ("flac".equals(ext)) {
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                byte[] b = new byte[42];
                int n = 0, k;
                while (n < 42 && (k = in.read(b, n, 42 - n)) > 0) n += k;
                if (n == 42 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') {
                    rate = ((b[18] & 0xFF) << 12) | ((b[19] & 0xFF) << 4) | ((b[20] & 0xFF) >> 4);
                    bits = (((b[20] & 1) << 4) | ((b[21] & 0xFF) >> 4)) + 1;
                    ch = ((b[20] >> 1) & 7) + 1;
                }
            } catch (Exception e) { /* ignore */ }
        }
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        o.put("uri", uri.toString());
        o.put("ext", ext);
        o.put("title", title == null || title.trim().isEmpty() ? base : title);
        o.put("artist", artist == null || artist.trim().isEmpty() ? "Unknown Artist" : artist);
        o.put("album", album == null ? "" : album);
        o.put("length", len);
        o.put("rate", rate);
        o.put("bits", bits);
        o.put("size", size);
        o.put("ch", ch);
        o.put("art", art);
        return o;
    }

    /** Copies the song into app cache so the web player can read it. */
    @PluginMethod
    public void prepare(PluginCall call) {
        final String u = call.getString("uri");
        final String ext = call.getString("ext", "mp3");
        new Thread(() -> {
            try {
                File dir = new File(getContext().getCacheDir(), "play");
                dir.mkdirs();
                File out = new File(dir, "t" + (++seq) + "." + ext);
                try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(u));
                     FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                File[] all = dir.listFiles();
                if (all != null) {
                    Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (int i = 2; i < all.length; i++) all[i].delete();
                }
                JSObject res = new JSObject();
                res.put("path", out.getAbsolutePath());
                call.resolve(res);
            } catch (Exception e) {
                call.reject("copy failed");
            }
        }).start();
    }
}
