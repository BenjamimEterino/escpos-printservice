package mz.escpos.printservice;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Servidor HTTP mínimo (sem dependências) para impressão silenciosa: qualquer app na mesma
 * máquina/rede envia um pedido HTTP e o recibo sai na impressora sem qualquer diálogo do Android.
 *
 * Endpoints:
 *  GET  /health                → {"status":"ok"}
 *  GET  /printers              → lista de impressoras configuradas
 *  POST /print[?printer=ID]    → corpo = PDF (Content-Type: application/pdf) ou bytes ESC/POS crus
 *                                 (qualquer outro Content-Type, ex.: application/octet-stream)
 *
 * Cabeçalho opcional "X-Api-Key" se BridgeConfig.getApiKey() não estiver vazio.
 */
final class PrintBridgeServer {
    private static final String TAG = "EscPosBridge";
    private static final long MAX_BODY = 25L * 1024 * 1024; // 25 MB
    private static final int JOB_TIMEOUT_S = 40;

    private final Context ctx;
    private final ExecutorService connections = Executors.newCachedThreadPool();
    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor();
    private ServerSocket serverSocket;
    private volatile boolean running;

    PrintBridgeServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    synchronized void start(int port) throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port)); // todas as interfaces (Wi-Fi/Ethernet/localhost)
        running = true;
        Thread accept = new Thread(this::acceptLoop, "escpos-bridge-accept");
        accept.setDaemon(true);
        accept.start();
        Log.i(TAG, "Servidor de impressão silenciosa a ouvir na porta " + port);
    }

    synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        connections.shutdownNow();
        printExecutor.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                connections.execute(() -> handleConnection(s));
            } catch (IOException e) {
                if (running) Log.w(TAG, "Falha ao aceitar ligação: " + e.getMessage());
            }
        }
    }

    private void handleConnection(Socket sock) {
        try {
            sock.setSoTimeout(JOB_TIMEOUT_S * 1000 + 5000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 4096);
            OutputStream out = sock.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) {
                writeResponse(out, 400, "text/plain; charset=utf-8", "Pedido inválido".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String method = parts[0];
            String rawPath = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.put(line.substring(0, idx).trim().toLowerCase(Locale.US), line.substring(idx + 1).trim());
            }

            String path = rawPath;
            String query = "";
            int q = rawPath.indexOf('?');
            if (q >= 0) {
                path = rawPath.substring(0, q);
                query = rawPath.substring(q + 1);
            }
            Map<String, String> params = parseQuery(query);

            if ("OPTIONS".equalsIgnoreCase(method)) {
                writeCorsPreflight(out);
                return;
            }

            byte[] body = new byte[0];
            String cl = headers.get("content-length");
            if (cl != null) {
                long len;
                try {
                    len = Long.parseLong(cl.trim());
                } catch (NumberFormatException e) {
                    writeJson(out, 400, "{\"status\":\"error\",\"message\":\"Content-Length inválido\"}");
                    return;
                }
                if (len > MAX_BODY) {
                    writeJson(out, 413, "{\"status\":\"error\",\"message\":\"Corpo demasiado grande\"}");
                    return;
                }
                body = readFully(in, (int) len);
            }

            route(method, path, params, headers, body, out);
        } catch (IOException e) {
            Log.w(TAG, "Ligação falhou: " + e.getMessage());
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(String method, String path, Map<String, String> params, Map<String, String> headers,
                        byte[] body, OutputStream out) throws IOException {
        String apiKey = BridgeConfig.getApiKey(ctx);
        if (!apiKey.isEmpty()) {
            String provided = headers.get("x-api-key");
            if (provided == null || !constantTimeEquals(provided, apiKey)) {
                writeJson(out, 401, "{\"status\":\"error\",\"message\":\"Chave de API inválida ou em falta\"}");
                return;
            }
        }

        if ("GET".equalsIgnoreCase(method) && "/health".equals(path)) {
            writeJson(out, 200, "{\"status\":\"ok\"}");
            return;
        }
        if ("GET".equalsIgnoreCase(method) && "/printers".equals(path)) {
            writeJson(out, 200, printersJson());
            return;
        }
        if ("POST".equalsIgnoreCase(method) && "/print".equals(path)) {
            handlePrint(params, headers, body, out);
            return;
        }
        writeJson(out, 404, "{\"status\":\"error\",\"message\":\"Não encontrado\"}");
    }

    private void handlePrint(Map<String, String> params, Map<String, String> headers, byte[] body, OutputStream out)
            throws IOException {
        if (body.length == 0) {
            writeJson(out, 400, "{\"status\":\"error\",\"message\":\"Corpo vazio\"}");
            return;
        }

        String printerId = params.get("printer");
        PrinterConfig.Entry printer = printerId != null
                ? PrinterConfig.find(ctx, printerId)
                : PrinterConfig.findDefault(ctx);
        if (printer == null) {
            writeJson(out, 400, "{\"status\":\"error\",\"message\":"
                    + jsonString("Impressora não encontrada. Indique ?printer=ID (ver GET /printers) "
                    + "ou defina uma impressora predefinida na app.") + "}");
            return;
        }

        String contentType = headers.getOrDefault("content-type", "");
        boolean isPdf = contentType.toLowerCase(Locale.US).contains("pdf");
        final PrinterConfig.Entry target = printer;
        final byte[] payload = body;

        Future<Void> future = printExecutor.submit((Callable<Void>) () -> {
            if (isPdf) {
                PrintJobRunner.printPdfBytes(ctx, target, PrinterConfig.loadOptions(ctx), payload, 1);
            } else {
                PrintJobRunner.printRawBytes(ctx, target, payload);
            }
            return null;
        });
        try {
            future.get(JOB_TIMEOUT_S, TimeUnit.SECONDS);
            writeJson(out, 200, "{\"status\":\"ok\"}");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            Log.e(TAG, "Falha ao imprimir via bridge", cause);
            String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : "Falha ao imprimir";
            writeJson(out, 502, "{\"status\":\"error\",\"message\":" + jsonString(msg) + "}");
        } catch (TimeoutException te) {
            future.cancel(true);
            writeJson(out, 504, "{\"status\":\"error\",\"message\":\"Tempo esgotado a imprimir\"}");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            writeJson(out, 500, "{\"status\":\"error\",\"message\":\"Interrompido\"}");
        }
    }

    private String printersJson() {
        List<PrinterConfig.Entry> list = PrinterConfig.load(ctx);
        String def = PrinterConfig.getDefaultPrinterId(ctx);
        boolean singleIsDefault = def == null && list.size() == 1;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            PrinterConfig.Entry e = list.get(i);
            if (i > 0) sb.append(',');
            sb.append('{')
                    .append("\"id\":").append(jsonString(e.id)).append(',')
                    .append("\"name\":").append(jsonString(e.name)).append(',')
                    .append("\"type\":").append(jsonString(e.type)).append(',')
                    .append("\"widthMm\":").append(e.paperWidthMm).append(',')
                    .append("\"default\":").append(e.id.equals(def) || singleIsDefault)
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    // ---------- HTTP mínimo ----------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c == '\r') continue;
            buf.write(c);
        }
        if (!any) return null; // EOF sem dados
        return buf.toString("UTF-8");
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new EOFException("Corpo incompleto");
            off += n;
        }
        return buf;
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        if (q == null || q.isEmpty()) return map;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, "UTF-8");
                String v = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                map.put(k, v);
            } catch (UnsupportedEncodingException ignored) {
            }
        }
        return map;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void writeJson(OutputStream out, int status, String json) throws IOException {
        writeResponse(out, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void writeCorsPreflight(OutputStream out) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 204 No Content\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        h.append("Access-Control-Allow-Headers: Content-Type, X-Api-Key\r\n");
        h.append("Access-Control-Max-Age: 600\r\n");
        h.append("Content-Length: 0\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 504: return "Gateway Timeout";
            default: return "Unknown";
        }
    }
}
