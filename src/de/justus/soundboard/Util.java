package de.justus.soundboard;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Kleinkram: Farben, Formatierung, Dateinamen, Ordner einlesen. */
final class Util {

    private Util() {
    }

    // ------------------------------------------------------------------ Farben
    static final int BG = Color.rgb(13, 16, 22);          // Hintergrund
    static final int SURFACE = Color.rgb(24, 28, 36);     // Leisten
    static final int SURFACE2 = Color.rgb(34, 39, 49);
    static final int TEXT = Color.rgb(236, 238, 242);
    static final int MUTED = Color.rgb(142, 148, 160);
    static final int RED = Color.rgb(229, 72, 77);
    static final int GREEN = Color.rgb(48, 164, 108);
    static final int AMBER = Color.rgb(255, 197, 61);

    /** Kachelfarben */
    static final int[] PALETTE = {
            Color.rgb(229, 72, 77),    // Rot
            Color.rgb(247, 107, 21),   // Orange
            Color.rgb(255, 197, 61),   // Gelb
            Color.rgb(48, 164, 108),   // Gruen
            Color.rgb(18, 165, 148),   // Tuerkis
            Color.rgb(62, 99, 221),    // Blau
            Color.rgb(142, 78, 198),   // Violett
            Color.rgb(214, 64, 159),   // Pink
            Color.rgb(139, 141, 152),  // Grau
            Color.rgb(230, 232, 236),  // Weiss
    };
    static final String[] PALETTE_NAMES = {"Rot", "Orange", "Gelb", "Grün", "Türkis", "Blau", "Violett", "Pink", "Grau", "Weiß"};

    static int color(int idx) {
        return PALETTE[Math.max(0, Math.min(PALETTE.length - 1, idx))];
    }

    /** helle Kachelfarben brauchen dunkle Schrift */
    static boolean isLight(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 170;
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static int alpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    // ------------------------------------------------------------------ Formatierung
    static float dbToLin(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    static String fmtDb(float db) {
        if (Math.abs(db) < 0.05f) return "0,0 dB";
        return String.format(Locale.GERMANY, "%+.1f dB", db);
    }

    static String fmtSec(float s) {
        if (s <= 0) return "aus";
        return String.format(Locale.GERMANY, s == Math.round(s) ? "%.0f s" : "%.1f s", s);
    }

    /** 83500 ms -> "1:23" */
    static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long s = (ms + 500) / 1000;
        return s / 60 + ":" + String.format(Locale.GERMANY, "%02d", s % 60);
    }

    static String stripExt(String n) {
        if (n == null) return "";
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    // ------------------------------------------------------------------ Dateien
    static String displayName(Context c, Uri u) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        String s = u.getLastPathSegment();
        return s == null ? "Titel" : s.substring(s.lastIndexOf('/') + 1);
    }

    static boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".wav")
                || n.endsWith(".ogg") || n.endsWith(".flac") || n.endsWith(".opus") || n.endsWith(".mp4");
    }

    /** Name eines per Ordnerauswahl freigegebenen Ordners */
    static String treeName(Context c, Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            return displayName(c, doc);
        } catch (Exception e) {
            return "Ordner";
        }
    }

    /**
     * Liest alle Audiodateien eines Ordners (ohne Unterordner), alphabetisch sortiert.
     * Liefert null, wenn der Ordner nicht (mehr) lesbar ist.
     */
    static List<Board.Track> scanFolder(Context c, String treeUri) {
        List<Board.Track> out = new ArrayList<>();
        Cursor cur = null;
        try {
            Uri tree = Uri.parse(treeUri);
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
            ContentResolver cr = c.getContentResolver();
            cur = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                if (!isAudio(name, mime)) continue;
                Board.Track t = new Board.Track(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name);
                t.src = treeUri;
                out.add(t);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cur != null) cur.close();
        }
        Collections.sort(out, new Comparator<Board.Track>() {
            @Override
            public int compare(Board.Track a, Board.Track b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    /**
     * MP3-Tags (Titel, Interpret) lesen. Fehlen sie oder ist die Datei nicht lesbar,
     * bleibt title leer und die App zeigt den Dateinamen.
     */
    static void readTags(Context c, Board.Track t) {
        android.media.MediaMetadataRetriever mr = new android.media.MediaMetadataRetriever();
        try {
            mr.setDataSource(c, Uri.parse(t.uri));
            String ti = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
            String ar = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (ar == null || ar.trim().isEmpty()) ar = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            t.title = ti == null ? "" : ti.trim();
            t.artist = ar == null ? "" : ar.trim();
            t.tagged = true;
        } catch (Exception e) {
            t.tagged = true; // nicht immer wieder versuchen
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
    }
}
