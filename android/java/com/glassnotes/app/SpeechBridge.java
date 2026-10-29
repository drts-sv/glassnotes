package com.glassnotes.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Мост распознавания речи: WebView не умеет Web Speech API
 * (Chromium 40417848, статус New), поэтому берём android.speech.SpeechRecognizer —
 * тот же движок, что у системного ввода голосом и Gboard.
 *
 * Страница получает шим window.webkitSpeechRecognition (native-shim.js),
 * который зовёт start/stop здесь, а результаты уходят обратно в
 * window.__nativeSpeech.onResult(id, text, isFinal).
 */
public class SpeechBridge {

    private static final String TAG = "GlassNotes";
    public static final int REQ_MIC = 1001;

    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private String currentId;

    public SpeechBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public boolean isAvailable() {
        try {
            return SpeechRecognizer.isRecognitionAvailable(activity);
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public void start(final String lang, final String id) {
        main.post(new Runnable() {
            @Override
            public void run() {
                startInternal(lang, id);
            }
        });
    }

    @JavascriptInterface
    public void stop() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (recognizer != null) {
                    try {
                        recognizer.stopListening();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    @JavascriptInterface
    public void abort() {
        main.post(new Runnable() {
            @Override
            public void run() {
                release();
                if (currentId != null) {
                    js("window.__nativeSpeech.onEnd(" + q(currentId) + ")");
                    currentId = null;
                }
            }
        });
    }

    private void startInternal(String lang, String id) {
        currentId = id;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            js("window.__nativeSpeech.onError(" + q(id) + ",'not-allowed')");
            js("window.__nativeSpeech.onEnd(" + q(id) + ")");
            currentId = null;
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            js("window.__nativeSpeech.onError(" + q(id) + ",'service-not-allowed')");
            js("window.__nativeSpeech.onEnd(" + q(id) + ")");
            currentId = null;
            return;
        }

        release();
        try {
            recognizer = createRecognizer();
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    js("window.__nativeSpeech.onStart(" + q(currentId) + ")");
                }

                @Override
                public void onBeginningOfSpeech() {
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                }

                @Override
                public void onError(int error) {
                    Log.i(TAG, "SpeechRecognizer error " + error);
                    if (currentId == null) return;
                    js("window.__nativeSpeech.onError(" + q(currentId) + "," + q(codeOf(error)) + ")");
                    js("window.__nativeSpeech.onEnd(" + q(currentId) + ")");
                    currentId = null;
                    release();
                }

                @Override
                public void onResults(Bundle results) {
                    String text = first(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
                    if (currentId == null) return;
                    js("window.__nativeSpeech.onResult(" + q(currentId) + "," + q(text) + ",true)");
                    js("window.__nativeSpeech.onEnd(" + q(currentId) + ")");
                    currentId = null;
                    release();
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    String text = first(partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
                    if (currentId == null || text == null || text.isEmpty()) return;
                    js("window.__nativeSpeech.onResult(" + q(currentId) + "," + q(text) + ",false)");
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, (lang == null || lang.isEmpty()) ? "ru-RU" : lang);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, (lang == null || lang.isEmpty()) ? "ru-RU" : lang);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
            // приоритет локального распознавателя: сеть телефона ненадёжна,
            // а облако Google отдаёт ERROR_NETWORK и ввод пропадает
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
            recognizer.startListening(intent);
        } catch (Throwable t) {
            Log.e(TAG, "start: " + t);
            js("window.__nativeSpeech.onError(" + q(id) + ",'audio-capture')");
            js("window.__nativeSpeech.onEnd(" + q(id) + ")");
            currentId = null;
            release();
        }
    }

    /** Локальный распознаватель, если он есть: не зависит от сети. */
    private SpeechRecognizer createRecognizer() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                    Log.i(TAG, "использую локальный распознаватель (on-device)");
                    return SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
                }
            } catch (Throwable t) {
                Log.w(TAG, "on-device недоступен: " + t);
            }
        }
        Log.i(TAG, "использую облачный распознаватель");
        return SpeechRecognizer.createSpeechRecognizer(activity);
    }

    private void release() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Throwable ignored) {
            }
            recognizer = null;
        }
    }

    private static String first(ArrayList<String> list) {
        return (list != null && !list.isEmpty()) ? list.get(0) : "";
    }

    private static String codeOf(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio-capture";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "not-allowed";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "no-speech";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return "network";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_CLIENT:
                return "aborted";
            default:
                return "unknown";
        }
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private void js(final String call) {
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript("window.__nativeSpeech&&" + call, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
