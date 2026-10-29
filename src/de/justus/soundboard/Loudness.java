package de.justus.soundboard;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Misst die Lautheit eines Titels als mittleren RMS-Pegel in dBFS (0 dBFS = Vollaussteuerung).
 *
 * Verfahren: Bei Titeln ueber einer Minute werden 6 Ausschnitte zu je 5 Sekunden ueber den Titel verteilt
 * dekodiert (geht schnell), kuerzere Titel komplett. Gemessen wird in Bloecken von 0,4 s; Bloecke leiser als
 * -50 dBFS (Stille, Ausklang) zaehlen nicht mit. Das ist eine Naeherung, kein normgerechtes LUFS-Messverfahren
 * (keine Frequenzgewichtung) - fuer das Angleichen von Musiktiteln untereinander reicht es.
 */
final class Loudness {

    private static final long SEG_US = 5_000_000L;
    private static final int SEGMENTS = 6;
    private static final double GATE = Math.pow(10, -50 / 10.0); // -50 dBFS als Energie

    private Loudness() {
    }

    /** RMS-Pegel in dBFS oder NaN, wenn die Datei nicht lesbar ist */
    static float measure(Context c, String uri) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(c, Uri.parse(uri), null);
            int track = -1;
            MediaFormat f = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat tf = ex.getTrackFormat(i);
                String mime = tf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    f = tf;
                    break;
                }
            }
            if (track < 0) return Float.NaN;
            ex.selectTrack(track);
            long durUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            int rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            dec.configure(f, null, null, 0);
            dec.start();

            Acc acc = new Acc(rate, ch);
            if (durUs > 60_000_000L) {
                for (int s = 0; s < SEGMENTS; s++) {
                    long start = durUs * (2 * s + 1) / (2 * SEGMENTS) - SEG_US / 2;
                    if (start < 0) start = 0;
                    ex.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    dec.flush();
                    decode(ex, dec, acc, start, start + SEG_US);
                }
            } else {
                decode(ex, dec, acc, 0, Long.MAX_VALUE);
            }
            if (acc.blocks == 0) return Float.NaN;
            return (float) (10 * Math.log10(acc.energy / acc.blocks));
        } catch (Throwable e) {
            return Float.NaN;
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    dec.release();
                } catch (Throwable ignored) {
                }
            }
            ex.release();
        }
    }

    /** Sammelt Energie je 0,4-s-Block */
    private static final class Acc {
        int rate, ch;
        boolean isFloat;
        double blockSum;
        long blockN, blockLen;
        double energy;
        long blocks;

        Acc(int rate, int ch) {
            set(rate, ch);
        }

        void set(int rate, int ch) {
            this.rate = Math.max(8000, rate);
            this.ch = Math.max(1, ch);
            blockLen = (long) (this.rate * 0.4) * this.ch;
        }

        void add(double s) { // s normiert auf -1..1
            blockSum += s * s;
            if (++blockN >= blockLen) {
                double e = blockSum / blockN;
                if (e > GATE) {
                    energy += e;
                    blocks++;
                }
                blockSum = 0;
                blockN = 0;
            }
        }

        void endSegment() { // angebrochenen Block verwerfen (Segmentgrenze)
            blockSum = 0;
            blockN = 0;
        }
    }

    private static void decode(MediaExtractor ex, MediaCodec dec, Acc acc, long fromUs, long toUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inEos = false, outEos = false;
        int guard = 0;
        while (!outEos && guard++ < 200_000) {
            if (!inEos) {
                int ii = dec.dequeueInputBuffer(10_000);
                if (ii >= 0) {
                    ByteBuffer ib = dec.getInputBuffer(ii);
                    int sz = ib == null ? -1 : ex.readSampleData(ib, 0);
                    long t = ex.getSampleTime();
                    if (sz < 0 || t > toUs) {
                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEos = true;
                    } else {
                        dec.queueInputBuffer(ii, 0, sz, t, 0);
                        ex.advance();
                    }
                }
            }
            int oi = dec.dequeueOutputBuffer(info, 10_000);
            if (oi >= 0) {
                ByteBuffer ob = dec.getOutputBuffer(oi);
                if (ob != null && info.size > 0 && info.presentationTimeUs >= fromUs && info.presentationTimeUs <= toUs) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    ByteBuffer b = ob.slice().order(ByteOrder.nativeOrder());
                    if (acc.isFloat) {
                        FloatBuffer fb = b.asFloatBuffer();
                        while (fb.hasRemaining()) acc.add(fb.get());
                    } else {
                        ShortBuffer sb = b.asShortBuffer();
                        while (sb.hasRemaining()) acc.add(sb.get() / 32768.0);
                    }
                }
                dec.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = dec.getOutputFormat();
                int r = of.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? of.getInteger(MediaFormat.KEY_SAMPLE_RATE) : acc.rate;
                int c = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : acc.ch;
                acc.set(r, c);
                acc.isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
            }
        }
        acc.endSegment();
    }
}
