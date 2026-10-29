package com.glassnotes.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Сохранение файла по запросу страницы.
 *
 * В WebView не работает <a download> со blob-ссылкой, поэтому экспорт бэкапа
 * уходил в никуда. Здесь файл пишется в системные «Загрузки» самостоятельно.
 */
public class SaveBridge {

    private static final String TAG = "GlassNotes";
    private final Context ctx;

    public SaveBridge(Context ctx) {
        this.ctx = ctx;
    }

    @JavascriptInterface
    public boolean saveFile(String name, String mime, String base64) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                if (os == null) return false;
                os.write(data);
                os.close();
                return true;
            }
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return false;
            File out = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.close();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "saveFile: " + t);
            return false;
        }
    }
}
