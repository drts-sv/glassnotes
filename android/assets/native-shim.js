/* Нативные шимы для APK-обёртки Glass Notes.
 *
 * WebView не даёт двух вещей, на которых стоит приложение:
 *   1. Web Speech API (распознавание речи) — Chromium 40417848, статус New;
 *   2. Notification API — уведомлений нет вообще.
 * Обе закрываются мостами NativeSpeech / NativeNotify (см. java/…/SpeechBridge.java
 * и NotificationBridge.java). Этот файл подставляется первым — до инлайн-скрипта
 * приложения, поэтому initVoice() видит уже готовый window.webkitSpeechRecognition.
 */
(function () {
  'use strict';

  /* ---------- Notification → системное уведомление Android ---------- */
  if (typeof window.Notification === 'undefined' && window.NativeNotify) {
    function WebNotification(title, options) {
      options = options || {};
      var ok = false;
      try {
        ok = window.NativeNotify.show(String(title || ''), String(options.body || ''), String(options.tag || ''));
      } catch (e) { ok = false; }
      if (!ok) throw new Error('Notification permission denied');
      this.title = String(title || '');
      this.body = String(options.body || '');
      this.tag = String(options.tag || '');
      this.data = options.data;
      this.close = function () {};
    }
    Object.defineProperty(WebNotification, 'permission', {
      get: function () {
        try { return window.NativeNotify.permission() || 'default'; } catch (e) { return 'default'; }
      },
      configurable: true
    });
    WebNotification.requestPermission = function (cb) {
      var state = 'default';
      try { state = window.NativeNotify.requestPermission() || 'default'; } catch (e) {}
      if (typeof cb === 'function') cb(state);
      return Promise.resolve(state);
    };
    WebNotification.maxActions = 0;
    window.Notification = WebNotification;
  }

  /* ---------- SpeechRecognition → android.speech ---------- */
  var hasNativeSpeech = !!(window.NativeSpeech && window.NativeSpeech.isAvailable && window.NativeSpeech.isAvailable());
  var alreadyHas = (typeof window.SpeechRecognition !== 'undefined') || (typeof window.webkitSpeechRecognition !== 'undefined');

  if (!alreadyHas && hasNativeSpeech) {
    var registry = {};
    var seq = 0;

    function SR() {
      this.lang = 'ru-RU';
      this.continuous = false;
      this.interimResults = true;
      this.maxAlternatives = 1;
      this._id = null;
      this._handlers = {};
    }
    SR.prototype.addEventListener = function (type, fn) {
      (this._handlers[type] = this._handlers[type] || []).push(fn);
    };
    SR.prototype.removeEventListener = function (type, fn) {
      var list = this._handlers[type] || [];
      var i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    };
    SR.prototype._fire = function (type, event) {
      event = event || {};
      event.type = type;
      var list = (this._handlers[type] || []).slice();
      for (var i = 0; i < list.length; i++) {
        try { list[i].call(this, event); } catch (e) { console.warn(e); }
      }
      var inline = this['on' + type];
      if (typeof inline === 'function') {
        try { inline.call(this, event); } catch (e) { console.warn(e); }
      }
    };
    SR.prototype.start = function () {
      this._id = 'sr' + (++seq);
      registry[this._id] = this;
      try {
        window.NativeSpeech.start(this.lang || 'ru-RU', this._id);
      } catch (e) {
        this._fire('error', { error: 'aborted' });
        this._fire('end');
        delete registry[this._id];
      }
    };
    SR.prototype.stop = function () {
      try { window.NativeSpeech.stop(); } catch (e) {}
    };
    SR.prototype.abort = function () {
      try { window.NativeSpeech.abort(); } catch (e) {}
    };

    window.__nativeSpeech = {
      onStart: function (id) {
        var s = registry[id];
        if (s) s._fire('start');
      },
      onResult: function (id, text, isFinal) {
        var s = registry[id];
        if (!s) return;
        var alternative = { transcript: text || '', confidence: 1 };
        var results = [{ 0: alternative, length: 1, isFinal: !!isFinal }];
        s._fire('result', { resultIndex: 0, results: results });
      },
      onError: function (id, code) {
        var s = registry[id];
        if (s) s._fire('error', { error: code || 'unknown' });
      },
      onEnd: function (id) {
        var s = registry[id];
        if (s) s._fire('end');
        delete registry[id];
      }
    };

    window.SpeechRecognition = SR;
    window.webkitSpeechRecognition = SR;
    console.log('[native-shim] распознавание речи подключено через android.speech');
  }

  if (!hasNativeSpeech) {
    console.log('[native-shim] android.speech недоступен — голосовой ввод не будет работать');
  }
})();
