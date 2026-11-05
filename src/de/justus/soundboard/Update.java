package de.justus.soundboard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Update-Pruefung wie bei Claude Limits:
 *  1. Versionsdatei auf der Projektseite  https://cappy0815.github.io/downloads/soundboard.json
 *     {"version":"0.14","page":"…","download":"…apk","sha256":"…"}
 *  2. Rueckfall: neuestes GitHub-Release von cappy0815/soundboard
 * Es wird nichts automatisch installiert – die App zeigt nur an und oeffnet den Download im Browser.
 * Gesendet wird dabei nur eine normale Abfrage (Kennung "Soundboard/<Version>"), keine Daten aus der App.
 */
final class Update {

    static final String VERSION_URL = "https://cappy0815.github.io/downloads/soundboard.json";
    static final String RELEASE_API = "https://api.github.com/repos/cappy0815/soundboard/releases/latest";
    static final String PROJECT_PAGE = "https://cappy0815.github.io/#soundboard";
    static final String GITHUB_PAGE = "https://github.com/cappy0815/soundboard";
    private static final long DAY_MS = 24L * 3600 * 1000;

    interface Done {
        /** im UI-Thread; ok=false: nicht erreichbar */
        void done(boolean ok);
    }

    private static boolean busy;

    private Update() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    static String installed(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean auto(Context c) {
        return sp(c).getBoolean("auto", true);
    }

    static void setAuto(Context c, boolean on) {
        sp(c).edit().putBoolean("auto", on).apply();
    }

    static long lastCheck(Context c) {
        return sp(c).getLong("last", 0);
    }

    static String latest(Context c) {
        return sp(c).getString("latest", "");
    }

    static boolean isBusy() {
        return busy;
    }

    /** gibt es laut letzter Pruefung eine neuere Version? */
    static boolean available(Context c) {
        String l = latest(c);
        return !l.isEmpty() && newer(l, installed(c));
    }

    /** automatische Pruefung faellig (eingeschaltet, letzte Pruefung > 24 h) */
    static boolean due(Context c) {
        return auto(c) && System.currentTimeMillis() - lastCheck(c) > DAY_MS;
    }

    /** Pruefung im Hintergrund */
    static void check(final Context c, final Done cb) {
        if (busy) return;
        busy = true;
        final Context app = c.getApplicationContext();
        final String ua = "Soundboard/" + installed(app);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] r = null; // version, download, page
                try {
                    JSONObject o = new JSONObject(get(VERSION_URL, ua));
                    String v = o.optString("version").trim();
                    if (!v.isEmpty()) r = new String[]{strip(v), o.optString("download"), o.optString("page", PROJECT_PAGE)};
                } catch (Exception ignored) {
                }
                if (r == null) {
                    try {
                        JSONObject o = new JSONObject(get(RELEASE_API, ua));
                        String v = o.optString("tag_name").trim();
                        if (!v.isEmpty()) {
                            String dl = "";
                            JSONArray as = o.optJSONArray("assets");
                            for (int i = 0; as != null && i < as.length(); i++) {
                                JSONObject x = as.getJSONObject(i);
                                if (x.optString("name").toLowerCase().endsWith(".apk")) {
                                    dl = x.optString("browser_download_url");
                                    break;
                                }
                            }
                            r = new String[]{strip(v), dl, o.optString("html_url", GITHUB_PAGE)};
                        }
                    } catch (Exception ignored) {
                    }
                }
                final boolean ok = r != null;
                SharedPreferences.Editor e = sp(app).edit().putLong("last", System.currentTimeMillis());
                if (ok) e.putString("latest", r[0]).putString("download", r[1]).putString("page", r[2]);
                e.apply();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (cb != null) cb.done(ok);
                    }
                });
            }
        }).start();
    }

    /** Download der neuen APK im Browser oeffnen (sonst Projektseite) */
    static void openDownload(Context c) {
        String u = sp(c).getString("download", "");
        open(c, u.isEmpty() ? sp(c).getString("page", PROJECT_PAGE) : u);
    }

    static void open(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            // kein Browser installiert
        }
    }

    /** Statuszeile fuer Einstellungen / Hilfe */
    static String status(Context c) {
        String inst = installed(c);
        if (busy) return "Prüfe auf Updates …";
        if (available(c)) return "Neu: v" + latest(c) + " (installiert: v" + inst + ")";
        long last = lastCheck(c);
        if (last == 0) return "Installiert: v" + inst + " · noch nicht geprüft";
        return "v" + inst + " ist aktuell · geprüft " + new java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY)
                .format(new java.util.Date(last));
    }

    // ------------------------------------------------------------------ intern
    private static String strip(String v) {
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    /** true, wenn a neuer ist als b ("0.14" > "0.13.1", "0.10" > "0.9") */
    static boolean newer(String a, String b) {
        String[] x = strip(a).split("[.-]"), y = strip(b).split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String get(String url, String ua) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(10000);
        h.setUseCaches(false);
        h.setRequestProperty("User-Agent", ua);
        h.setRequestProperty("Accept", "application/vnd.github+json, application/json");
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            InputStream in = h.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0 && total < (1 << 20)) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
