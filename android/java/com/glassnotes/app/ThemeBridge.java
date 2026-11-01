package com.glassnotes.app;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Material You: цвета системной палитры (они выводятся из обоев пользователя) отдаём в CSS-переменные
 * приложения. Ресурсы android.R.color.system_* существуют только с Android 12, поэтому на старых
 * версиях возвращаем пустую строку — приложение просто остаётся на своей палитре.
 */
public class ThemeBridge {

    private final Context ctx;

    public ThemeBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** JSON с цветами палитры либо пустая строка, если система их не даёт. */
    @JavascriptInterface
    public String palette() {
        if (Build.VERSION.SDK_INT < 31) return "";
        try {
            int accentDark = first("system_accent1_200", "system_accent1_500");
            int accentLight = first("system_accent1_600", "system_accent1_500");
            int bgStart = first("system_neutral1_900", "system_neutral1_800");
            int bgEnd = first("system_neutral1_1000", "system_neutral1_900");
            int surface = first("system_neutral2_800", "system_neutral1_800");
            int border = first("system_neutral2_200", "system_neutral1_200");
            if (accentDark == 0 && accentLight == 0) return "";

            JSONObject o = new JSONObject();
            if (accentDark != 0) o.put("accentDark", hex(accentDark));
            if (accentLight != 0) o.put("accentLight", hex(accentLight));
            if (bgStart != 0) o.put("bgStart", rgba(bgStart, 0.95));
            if (bgEnd != 0) o.put("bgEnd", rgba(bgEnd, 1.0));
            if (surface != 0) o.put("surface", rgba(surface, 0.55));
            if (border != 0) o.put("surfaceBorder", rgba(border, 0.22));
            return o.toString();
        } catch (Throwable t) {
            Log.w("GlassNotes", "палитра системы: " + t);
            return "";
        }
    }

    /** Первый найденный цвет из списка имён: набор ключей отличается между версиями Android. */
    private int first(String... names) {
        for (String name : names) {
            int id = ctx.getResources().getIdentifier(name, "color", "android");
            if (id == 0) continue;
            try {
                int value = ctx.getColor(id);
                if (value != 0) return value;
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private String hex(int color) {
        return String.format(Locale.US, "#%02x%02x%02x", Color.red(color), Color.green(color), Color.blue(color));
    }

    private String rgba(int color, double alpha) {
        return String.format(Locale.US, "rgba(%d, %d, %d, %.2f)",
                Color.red(color), Color.green(color), Color.blue(color), alpha);
    }
}
