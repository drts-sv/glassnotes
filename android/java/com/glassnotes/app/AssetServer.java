package com.glassnotes.app;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Крошечный HTTP-сервер на 127.0.0.1, отдающий файлы из assets.
 *
 * Нужен ради реального origin: на file:// в WebView не работают IndexedDB,
 * localStorage и service worker, а http://127.0.0.1 — «потенциально доверенный»
 * источник, где всё это доступно.
 */
public class AssetServer {

    private static final String TAG = "GlassNotes";
    private final AssetManager assets;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket socket;
    private volatile boolean running = false;
    private int port = 0;

    public AssetServer(AssetManager assets) {
        this.assets = assets;
    }

    /** Порт фиксирован намеренно: origin = схема + хост + порт, а IndexedDB и
     *  localStorage привязаны к origin. Со случайным портом (0) все заметки
     *  и настройки терялись при каждом запуске приложения. */
    private static final int PREFERRED_PORT = 8734;
    private static final int PORT_ATTEMPTS = 4;
    /** Повторы на тот же порт: после перезапуска сокет бывает в TIME_WAIT, а прошлый экземпляр
     *  приложения успевает дожить пару секунд. Ждём до шести секунд, прежде чем уехать на соседний
     *  порт, — иначе пользователь видит пустой список, хотя его заметки целы на старом адресе. */
    private static final int PORT_RETRIES = 24;
    private static final long PORT_RETRY_MS = 250;

    /** Последний поднятый порт: приложение показывает предупреждение, если он не основной. */
    public static volatile int LAST_PORT = 0;

    public int start() throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        socket = bindFirstFree(loopback);
        port = socket.getLocalPort();
        running = true;
        Thread t = new Thread(this::loop, "asset-server");
        t.setDaemon(true);
        t.start();
        LAST_PORT = port;
        Log.i(TAG, "AssetServer на порту " + port);
        return port;
    }

    /**
     * Порт — это origin, а к origin привязаны IndexedDB и localStorage: уезд на соседний порт
     * выглядит для пользователя как «все заметки пропали». Поэтому SO_REUSEADDR и повторы,
     * а уход на другой порт — только как последний вариант, с предупреждением в логе.
     */
    private ServerSocket bindFirstFree(InetAddress loopback) throws IOException {
        IOException last = null;
        for (int i = 0; i < PORT_ATTEMPTS; i++) {
            int candidate = PREFERRED_PORT + i;
            int tries = (i == 0) ? PORT_RETRIES : 1;
            for (int attempt = 0; attempt < tries; attempt++) {
                ServerSocket candidateSocket = new ServerSocket();
                try {
                    candidateSocket.setReuseAddress(true);
                    candidateSocket.bind(new InetSocketAddress(loopback, candidate), 16);
                    if (i > 0) {
                        Log.w(TAG, "порт " + PREFERRED_PORT + " занят, поднялись на " + candidate
                                + " — заметки лежат в 127.0.0.1:" + PREFERRED_PORT);
                    }
                    return candidateSocket;
                } catch (IOException e) {
                    last = e;
                    try {
                        candidateSocket.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        Thread.sleep(PORT_RETRY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            Log.w(TAG, "порт " + candidate + " занят: " + (last != null ? last.getMessage() : "?"));
        }
        ServerSocket fallback = new ServerSocket();
        fallback.setReuseAddress(true);
        fallback.bind(new InetSocketAddress(loopback, 0), 16);
        Log.w(TAG, "запасной случайный порт: " + fallback.getLocalPort());
        return fallback;
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void loop() {
        while (running) {
            try {
                final Socket client = socket.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept: " + e.getMessage());
            }
        }
    }

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            while (true) {
                String line = in.readLine();
                if (line == null || line.isEmpty()) break;
            }

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            String name = path.startsWith("/") ? path.substring(1) : path;

            if (name.contains("..")) {
                respond(s, 404, "text/plain", "not found".getBytes("UTF-8"));
                return;
            }

            byte[] data;
            try (InputStream is = assets.open(name)) {
                data = readAll(is);
            } catch (IOException e) {
                respond(s, 404, "text/plain", "not found".getBytes("UTF-8"));
                return;
            }

            if (name.equals("index.html")) {
                data = injectShim(data);
            }
            respond(s, 200, mimeOf(name), data);
        } catch (Exception e) {
            Log.w(TAG, "handle: " + e);
        }
    }

    /** Подставляет шимы нативных API перед инлайн-скриптом приложения. */
    private byte[] injectShim(byte[] html) throws IOException {
        String text = new String(html, "UTF-8");
        int at = text.indexOf("<script>");
        if (at >= 0) {
            text = text.substring(0, at)
                    + "<script src=\"native-shim.js\"></script>\n"
                    + text.substring(at);
        }
        return text.getBytes("UTF-8");
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, is.available()));
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void respond(Socket s, int code, String mime, byte[] body) throws IOException {
        OutputStream out = s.getOutputStream();
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " Not Found").append("\r\n");
        head.append("Content-Type: ").append(mime).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        return "application/octet-stream";
    }
}
