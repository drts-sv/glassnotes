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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;

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
    private AssetServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // отладка WebView (chrome://inspect / CDP через adb forward) — нужна для проверки на устройстве
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0A0C12"));
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

        webView.setWebViewClient(new WebViewClient() {
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
        });

        webView.addJavascriptInterface(new NotificationBridge(this), "NativeNotify");
        webView.addJavascriptInterface(new SpeechBridge(this, webView), "NativeSpeech");

        setContentView(webView);
        NotificationBridge.ensureChannel(this);
        requestStartupPermissions();

        try {
            server = new AssetServer(getAssets());
            int port = server.start();
            webView.loadUrl("http://127.0.0.1:" + port + "/index.html");
        } catch (IOException e) {
            Log.e(TAG, "не поднялся локальный сервер", e);
            webView.loadData("<h2>Не удалось запустить локальный сервер</h2><p>" + e.getMessage() + "</p>",
                    "text/html; charset=utf-8", null);
        }
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
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
