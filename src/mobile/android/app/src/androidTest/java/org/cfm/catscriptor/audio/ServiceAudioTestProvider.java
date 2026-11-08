package org.cfm.catscriptor.audio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Настоящий WAV через медленный pipe: окно наблюдения не зависит от скорости телефона. */
public final class ServiceAudioTestProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if ("invalid".equals(uri.getLastPathSegment())) {
                        out.write(new byte[] {1, 2, 3});
                        return;
                    }
                    if ("speech".equals(uri.getLastPathSegment())) {
                        try (java.io.InputStream input = getContext().getAssets().open("sherpa-example.wav")) {
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
                        }
                        return;
                    }
                    int samples = "long".equals(uri.getLastPathSegment()) ? 16000000 : 1600000;
                    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                    header.put("RIFF".getBytes("US-ASCII")).putInt(36 + samples * 2);
                    header.put("WAVEfmt ".getBytes("US-ASCII")).putInt(16);
                    header.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
                    header.putShort((short) 2).putShort((short) 16);
                    header.put("data".getBytes("US-ASCII")).putInt(samples * 2);
                    out.write(header.array());
                    byte[] block = new byte[4096];
                    for (int remaining = samples * 2; remaining > 0;) {
                        int count = Math.min(block.length, remaining);
                        out.write(block, 0, count);
                        remaining -= count;
                        Thread.sleep(10);
                    }
                } catch (Exception ignored) {
                    // Закрытие читателем при отмене ожидаемо разрывает pipe.
                }
            }, "service-test-wav");
            writer.setDaemon(true);
            writer.start();
            return pipe[0];
        } catch (java.io.IOException error) {
            throw new FileNotFoundException(error.toString());
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
