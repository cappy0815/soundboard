package de.justus.soundboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Bilder fuer Kacheln und Wappen: werden beim Auswaehlen verkleinert in den App-Speicher kopiert
 * (files/img/*.png). So braucht die App keinen Dauerzugriff auf die Galerie, und die Sicherung kann sie mitnehmen.
 */
final class Images {

    private static final int MAX_PX = 720;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Images() {
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "img");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Grund, warum das letzte importImage fehlschlug (fuer die Meldung) */
    static String lastError = "";

    /** Bild aus der Auswahl uebernehmen; liefert den Dateinamen oder null (Grund in lastError) */
    static String importImage(Context c, Uri u) {
        lastError = "";
        try {
            Bitmap bmp = null;
            // ab Android 9: ImageDecoder (kann auch HEIC/HEIF von Handykameras), verkleinert direkt beim Laden
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                try {
                    android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(c.getContentResolver(), u);
                    bmp = android.graphics.ImageDecoder.decodeBitmap(src, new android.graphics.ImageDecoder.OnHeaderDecodedListener() {
                        @Override
                        public void onHeaderDecoded(android.graphics.ImageDecoder d, android.graphics.ImageDecoder.ImageInfo info,
                                                    android.graphics.ImageDecoder.Source s) {
                            int m = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                            if (m > MAX_PX) {
                                float f = MAX_PX / (float) m;
                                d.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * f)),
                                        Math.max(1, Math.round(info.getSize().getHeight() * f)));
                            }
                            d.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE); // Canvas braucht Software-Bitmaps
                        }
                    });
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    bmp = null;
                }
            }
            if (bmp == null) bmp = decodeClassic(c, u);
            if (bmp == null) {
                if (lastError.isEmpty()) lastError = "Format nicht lesbar";
                return null;
            }
            int m = Math.max(bmp.getWidth(), bmp.getHeight());
            if (m > MAX_PX) {
                float f = MAX_PX / (float) m;
                bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * f), Math.round(bmp.getHeight() * f), true);
            }
            String name = UUID.randomUUID().toString().substring(0, 12) + ".png";
            FileOutputStream out = new FileOutputStream(new File(dir(c), name));
            boolean ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, out); // PNG: Wappen mit transparentem Rand bleiben transparent
            out.close();
            if (!ok) {
                lastError = "Speichern fehlgeschlagen";
                return null;
            }
            CACHE.put(name, bmp);
            return name;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return null;
        }
    }

    private static Bitmap decodeClassic(Context c, Uri u) {
        try {
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in, null, bo);
            in.close();
            int sample = 1;
            while (Math.max(bo.outWidth, bo.outHeight) / (sample * 2) >= MAX_PX) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = c.getContentResolver().openInputStream(u);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            return bmp;
        } catch (Exception e) {
            if (lastError.isEmpty()) lastError = e.getClass().getSimpleName();
            return null;
        }
    }

    static Bitmap get(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        Bitmap b = CACHE.get(name);
        if (b != null) return b;
        b = BitmapFactory.decodeFile(new File(dir(c), name).getPath());
        if (b != null) CACHE.put(name, b);
        return b;
    }

    /** nicht mehr verwendete Bilder loeschen */
    static void cleanup(Context c, Board board) {
        Set<String> used = used(board);
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) if (!used.contains(f.getName())) f.delete();
    }

    static Set<String> used(Board board) {
        Set<String> used = new HashSet<>();
        for (Board.Preset p : board.presets) {
            if (!p.logo.isEmpty()) used.add(p.logo);
            for (Board.Btn b : p.btns) if (!b.image.isEmpty()) used.add(b.image);
        }
        return used;
    }

    // ------------------------------------------------------------------ fuer die Sicherung
    static String toBase64(Context c, String name) {
        try {
            File f = new File(dir(c), name);
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    static void fromBase64(Context c, String name, String data) {
        try {
            if (name.contains("/") || name.contains("..")) return;
            FileOutputStream out = new FileOutputStream(new File(dir(c), name));
            out.write(Base64.decode(data, Base64.DEFAULT));
            out.close();
            CACHE.remove(name);
        } catch (Exception ignored) {
        }
    }
}
