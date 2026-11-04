package com.glassnotes.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Обёртка Glass Notes: WebView + локальный HTTP-сервер из assets.
 *
 * Реальный origin http://127.0.0.1:PORT нужен, потому что на file:// в WebView
 * не работают IndexedDB, localStorage и service worker (а приложение живёт на них).
 */
public class MainActivity extends Activity {

    private static final String TAG = "GlassNotes";
    public static final int REQ_PERMS = 1000;

    private WebView webView;
    /**
     * Постоянный origin страницы. Раньше приложение поднимало локальный HTTP-сервер на 127.0.0.1,
     * и хранилище WebView (IndexedDB, localStorage) зависело от его порта: порт сменился — заметки
     * «пропадали». Теперь заметки и настройки лежат в базе приложения, а страницу отдаём прямо из
     * assets по этому адресу: https — доверенный источник, поэтому доступны и localStorage, и
     * service worker, а порта и потока сервера больше нет.
     */
    private static final String ASSET_ORIGIN = "https://appassets.androidplatform.net/";
    private SpeechBridge speechBridge;

    /** Прогрев распознавателя отложенно: не мешает загрузке страницы. */
    private void warmUpRecognizer() {
        webView.postDelayed(() -> {
            try {
                if (speechBridge != null) speechBridge.warmUp();
            } catch (Throwable ignored) {
            }
        }, 4000);
    }

    /** Первый запуск: тянем модель фоном, если есть Wi-Fi. */
    private void autoDownloadModel() {
        webView.postDelayed(() -> {
            try {
                if (speechBridge != null && speechBridge.autoDownloadModelIfPossible()) {
                    android.util.Log.i("GlassNotes", "начата автодокачка модели GigaAM");
                }
            } catch (Throwable ignored) {
            }
        }, 3000);
    }
    private android.webkit.ValueCallback<android.net.Uri[]> filePathCallback;
    private static final int REQ_FILE = 1002;

    public static final String EXTRA_NOTE_ID = "note_id";
    public static final String EXTRA_NOTE_MODE = "note_mode";
    /** «Показать» из уведомления утреннего ревью: открыть приложение сразу на сплэше. */
    public static final String EXTRA_REVIEW = "review_open";

    /** Действие из уведомления, которое ждёт обработки в JS (пусто, если ничего нет). */
    private static volatile String pendingAction = "";

    static String takePendingAction() {
        String action = pendingAction;
        pendingAction = "";
        return action;
    }

    /** Тап по уведомлению или кнопка «Выполнено» приносят сюда id заметки. */
    private void captureAction(Intent intent) {
        try {
            if (intent != null && intent.getBooleanExtra(EXTRA_REVIEW, false)) {
                intent.removeExtra(EXTRA_REVIEW);
                pendingAction = "{\"review\":1}";
                android.util.Log.i("GlassNotes", "действие из уведомления: показать утреннее ревью");
                nudgePage();
                return;
            }
            if (intent == null || !intent.hasExtra(EXTRA_NOTE_ID)) return;
            long id = intent.getLongExtra(EXTRA_NOTE_ID, 0L);
            int mode = intent.getIntExtra(EXTRA_NOTE_MODE, 0);
            intent.removeExtra(EXTRA_NOTE_ID);
            if (id <= 0L || mode == 0) return;
            pendingAction = "{\"id\":" + id + ",\"mode\":" + mode + "}";
            android.util.Log.i("GlassNotes", "действие из уведомления: " + pendingAction);
            // Страница могла быть уже загружена (приложение на переднем плане) — тогда
            // visibilitychange не сработает, и кнопка «Выполнено» выглядела нерабочей.
            nudgePage();
        } catch (Throwable ignored) {
        }
    }

    /** Заставляем страницу забрать действие: сама она об этом не узнает (visibilitychange не сработает). */
    private void nudgePage() {
        if (webView == null) return;
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(
                            "(function(){ if (typeof consumeNativeAction === 'function') consumeNativeAction(); })()",
                            null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureAction(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        captureAction(getIntent());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureAction(getIntent());

        // отладка WebView (chrome://inspect / CDP через adb forward) — нужна для проверки на устройстве
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView = new WebView(this);
        // Прозрачные фон и окно: за приложением видны обои рабочего стола, а карточки лежат
        // «на стекле» — без подложки и рамки вокруг всего приложения.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setLoadWithOverviewMode(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 26) {
            ws.setSafeBrowsingEnabled(false);
        }

        // статус-бар, вырез камеры и панель навигации уезжают в CSS-переменные:
        // без этого на Pixel 10a шапка приложения лезет под строку состояния
        webView.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(android.view.View v, android.view.WindowInsets insets) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                pushInsets(top, bottom);
                return insets;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            /** Отдаём файлы из assets: index.html, sw.js, скрипты, лицензии. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(ASSET_ORIGIN)) return serveAsset(url.substring(ASSET_ORIGIN.length()));
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Слушатель insets успевает сработать раньше, чем появится JS-контекст,
                // и тогда значения теряются. Поэтому после загрузки берём их напрямую
                // из окна и, сбросив кэш, отправляем заново.
                webView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        pushCurrentInsets();
                    }
                }, 300);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String host = url.getHost();
                if (host != null && (host.equals("127.0.0.1") || host.equals("localhost"))) {
                    return false;
                }
                // всё внешнее — в системный браузер
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Throwable ignored) {
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                            request.grant(new String[]{res});
                        } else {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, SpeechBridge.REQ_MIC);
                            request.deny();
                        }
                        return;
                    }
                }
                request.deny();
            }

            @Override
            public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<android.net.Uri[]> callback,
                                             FileChooserParams params) {
                // WebView без этого метода молча игнорирует input[type=file] — импорт бэкапа не открывался
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    android.content.Intent intent = params.createIntent();
                    intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(new NotificationBridge(this), "NativeNotify");
        webView.addJavascriptInterface(new ReminderScheduler(this), "NativeAlarms");
        webView.addJavascriptInterface(new HapticBridge(this), "NativeHaptic");
        webView.addJavascriptInterface(new ThemeBridge(this), "NativeTheme");
        webView.addJavascriptInterface(new SaveBridge(this), "NativeSave");
        // заметки и настройки — в базе приложения, а не в хранилище WebView (оно привязано к порту)
        webView.addJavascriptInterface(StoreBridge.get(this), "NativeStore");
        speechBridge = new SpeechBridge(this, webView);
        autoDownloadModel();
        warmUpRecognizer();
        webView.addJavascriptInterface(speechBridge, "NativeSpeech");

        setContentView(webView);
        NotificationBridge.ensureChannel(this);
        requestStartupPermissions();

        webView.loadUrl(ASSET_ORIGIN + "index.html");
    }

    private void requestStartupPermissions() {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            android.net.Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getDataString() != null) {
                    result = new android.net.Uri[]{android.net.Uri.parse(data.getDataString())};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new android.net.Uri[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private int lastTopInset = -1, lastBottomInset = -1;

    /** Файл из assets как ответ WebView. Кодировку ставим только тексту, двоичные отдаём как есть. */
    private WebResourceResponse serveAsset(String path) {
        try {
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.isEmpty() || path.endsWith("/")) path = path + "index.html";
            InputStream in = getAssets().open(path);
            String mime = mimeOf(path);
            boolean text = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json");
            WebResourceResponse resp = new WebResourceResponse(mime, text ? "utf-8" : null, in);
            // Без кэша: файлы лежат в APK, после обновления нужно отдавать свежие, а не старые.
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("Cache-Control", "no-store");
            resp.setResponseHeaders(h);
            return resp;
        } catch (Throwable t) {
            Log.w(TAG, "нет в assets: " + path);
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    new java.util.HashMap<String, String>(),
                    new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".txt")) return "text/plain";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private void pushCurrentInsets() {
        int top = 0, bottom = 0;
        try {
            android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
            if (wi != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets b = wi.getInsets(
                            android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    top = b.top;
                    bottom = b.bottom;
                } else {
                    top = wi.getSystemWindowInsetTop();
                    bottom = wi.getSystemWindowInsetBottom();
                }
            }
        } catch (Throwable ignored) {
        }
        lastTopInset = -1;
        lastBottomInset = -1;
        pushInsets(top, bottom);
    }

    private void pushInsets(int topPx, int bottomPx) {
        // В CSS нужны css-px, а WindowInsets отдаёт device px: без деления на плотность
        // статус-бар раздувался в 2.6 раза и над шапкой висело ~2 см пустоты.
        float density = getResources().getDisplayMetrics().density;
        int top = Math.round(topPx / density);
        int bottom = Math.round(bottomPx / density);
        if (top == lastTopInset && bottom == lastBottomInset) return;
        lastTopInset = top;
        lastBottomInset = bottom;
        Log.i(TAG, "инсеты: верх " + top + "px, низ " + bottom + "px (device " + topPx + "/" + bottomPx + ")");
        final String js = "document.documentElement.style.setProperty('--inset-top','" + top + "px');"
                + "document.documentElement.style.setProperty('--inset-bottom','" + bottom + "px');";
        if (webView == null) return;
        webView.post(new Runnable() {
            @Override
            public void run() {
                try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (speechBridge != null) speechBridge.releaseEngine();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
