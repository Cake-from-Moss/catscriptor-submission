package org.cfm.catscriptor.export;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Настоящий файловый провайдер только тестового APK; внешнее окно SAF не требуется.
 * Android запускает его в отдельном процессе без classpath основного APK.
 * Поэтому реализация зависит только от платформы, а не от Kotlin stdlib,
 * которую сборщик тестового APK исключает как зависимость приложения.
 */
public final class ExportTestProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("denied".equals(uri.getLastPathSegment())) {
            throw new SecurityException("Тестовый запрет");
        }
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Тестовый провайдер не присоединён к контексту");
        }
        return ParcelFileDescriptor.open(
                new File(context.getCacheDir(), "saf-export-test"),
                ParcelFileDescriptor.parseMode(mode)
        );
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
