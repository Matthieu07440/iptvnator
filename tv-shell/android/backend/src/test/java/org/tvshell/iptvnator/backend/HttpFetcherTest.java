package org.tvshell.iptvnator.backend;

import static org.tvshell.iptvnator.backend.TestMain.assertEquals;
import static org.tvshell.iptvnator.backend.TestMain.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Mirrors apps/web-backend validated-http-client.spec.ts: identity headers stop at the origin boundary. */
public class HttpFetcherTest {

    private static Map<String, String> identity() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer portal-token");
        headers.put("cOoKiE", "mac=00:1A:79:00:00:01");
        headers.put("Proxy-Authorization", "Basic x");
        headers.put("sN", "serial");
        headers.put("User-Agent", "Mozilla/5.0");
        return headers;
    }

    private static Map<String, String> redirected(String from, String location) throws MalformedURLException {
        URL sent = new URL(from);
        return HttpFetcher.headersForRedirect(identity(), sent, new URL(sent, location));
    }

    public void testBodyCapFollowsTheHeapAndRejectsEarly() throws Exception {
        assertTrue("cap at most 64 MB", HttpFetcher.MAX_BODY_BYTES <= 64L * 1024 * 1024);
        assertTrue("cap at most an eighth of the heap", HttpFetcher.MAX_BODY_BYTES <= Runtime.getRuntime().maxMemory() / 8);
        byte[] ten = "0123456789".getBytes("UTF-8");
        assertEquals("declared size sizes the buffer", 10, Integer.valueOf(HttpFetcher.readAll(new ByteArrayInputStream(ten), 10, 10).length));
        assertEquals("unknown size", 10, Integer.valueOf(HttpFetcher.readAll(new ByteArrayInputStream(ten), -1, 10).length));
        try {
            HttpFetcher.readAll(new ByteArrayInputStream(ten), 11, 10);
            throw new TestMain.AssertionFailed("declared over the cap must not read a byte");
        } catch (HttpFetcher.TooLargeException expected) {
            // refused before reading
        }
        try {
            HttpFetcher.readAll(new ByteArrayInputStream(ten), -1, 9);
            throw new TestMain.AssertionFailed("body over the cap must stop");
        } catch (HttpFetcher.TooLargeException expected) {
            // refused while reading
        }
        ProviderException e = HttpFetcher.tooLarge();
        assertEquals("code", "ERR_RESPONSE_TOO_LARGE", e.code);
        assertTrue("no http status", !e.hasHttpStatus());
    }

    public void testSameOriginRedirectKeepsEveryHeader() throws Exception {
        assertEquals("same origin", identity(), redirected("https://provider.example/start", "https://provider.example/next"));
        assertEquals("relative location", identity(), redirected("https://provider.example/start", "/next"));
        assertEquals("default port is the same origin", identity(), redirected("http://a.example/x", "http://a.example:80/y"));
        assertEquals("host case", identity(), redirected("https://Provider.example/x", "https://provider.EXAMPLE/y"));
    }

    public void testCrossOriginRedirectDropsIdentityHeaders() throws Exception {
        Map<String, String> onlyAgent = new LinkedHashMap<String, String>();
        onlyAgent.put("User-Agent", "Mozilla/5.0");
        assertEquals("other host", onlyAgent, redirected("https://provider.example/start", "https://other.example/next"));
        assertEquals("other port", onlyAgent, redirected("https://provider.example/start", "https://provider.example:8443/next"));
        assertEquals("https to http", onlyAgent, redirected("https://provider.example/start", "http://provider.example/next"));
        Map<String, String> input = identity();
        HttpFetcher.headersForRedirect(input, new URL("https://a.example/"), new URL("https://b.example/"));
        assertTrue("input map is not mutated", input.containsKey("Authorization"));
        assertTrue("null headers stay null", HttpFetcher.headersForRedirect(null, new URL("https://a.example/"), new URL("https://b.example/")) == null);
    }
    /** A real HTTP 200 whose body cannot be consumed; no external host or provider data. */
    private static final class BrokenBodyServer implements AutoCloseable {
        private static final byte[] PLAYLIST = "#EXTM3U\n#EXTINF:-1,Synthetic Channel\nhttps://stream.example/live.m3u8\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final ServerSocket listener;
        final Thread thread;
        volatile IOException failure;

        BrokenBodyServer(final String mode) throws IOException {
            final boolean timeout = mode.equals("timeout");
            final boolean truncated = mode.equals("truncated");
            final boolean missingGzipMember = mode.equals("gzip-member");
            listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader request = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                    String line;
                    while ((line = request.readLine()) != null && !line.isEmpty()) { /* consume headers */ }
                    byte[] body = timeout ? new byte[0] : truncated ? PLAYLIST
                        : missingGzipMember ? gzip(PLAYLIST) : new byte[] { 0x1f, (byte) 0x8b, 0x08 };
                    int declared = timeout ? 1 : truncated || missingGzipMember ? 1024 : body.length;
                    String headers = "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: " + declared
                        + (timeout || truncated ? "" : "\r\nContent-Encoding: gzip") + "\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes("US-ASCII"));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                    // A stalled body ends when the client times out and disconnects.
                    if (timeout) socket.getInputStream().read();
                } catch (IOException e) {
                    failure = e;
                }
            }, "synthetic-broken-body");
            thread.start();
        }

        private static byte[] gzip(byte[] body) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(body);
            }
            return out.toByteArray();
        }

        String url() { return "http://127.0.0.1:" + listener.getLocalPort() + "/synthetic.m3u"; }

        @Override
        public void close() throws Exception {
            listener.close();
            thread.join(3000);
            assertTrue("server thread finished", !thread.isAlive());
            if (failure != null) throw failure;
        }
    }

    public void testBodyReadFailuresLoseTheSuccessfulHttpStatus() throws Exception {
        for (String mode : new String[] { "gzip", "timeout", "truncated" }) {
            try (BrokenBodyServer server = new BrokenBodyServer(mode)) {
                try {
                    new HttpFetcher().get(server.url(), null, 200);
                    throw new TestMain.AssertionFailed("broken body must fail");
                } catch (ProviderException error) {
                    assertTrue("body failure is a transport error", !error.hasHttpStatus());
                    assertTrue("network error code retained", error.code != null);
                    if (mode.equals("timeout")) assertEquals("body timeout code", "ETIMEDOUT", error.code);
                }
            }
        }
    }

    public void testBrokenPlaylistBodyCannotLookLikeASuccessfulImport() throws Exception {
        for (String mode : new String[] { "gzip", "truncated", "gzip-member" }) {
            assertBrokenPlaylistImport(mode);
        }
    }

    private static String sanitized(byte[] input, int chunk) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonSpoolSink sink = new JsonSpoolSink(out);
        for (int i = 0; i < input.length; i += chunk) sink.write(input, i, Math.min(chunk, input.length - i));
        sink.finish();
        assertEquals("written counts the output", Long.valueOf(out.size()), Long.valueOf(sink.written()));
        return new String(out.toByteArray(), "UTF-8");
    }

    public void testSpoolSinkMakesJsonParseableByABrowser() throws Exception {
        byte[] raw = "﻿ [{\"plot\":\"a\nb\tc \\\" \\\\\",\"t\":\"é\u0001\"}]".getBytes("UTF-8");
        for (int chunk : new int[] { 1, 2, 3, 7, 4096 }) {
            String text = sanitized(raw, chunk);
            assertEquals("BOM dropped, controls escaped in strings (chunk " + chunk + ")",
                " [{\"plot\":\"a\\u000ab\\u0009c \\\" \\\\\",\"t\":\"é\\u0001\"}]", text);
            assertTrue("valid for JSON.parse", Json.validate(text));
        }
        assertEquals("structure whitespace kept", "{\n\t\"a\" : 1\n}", sanitized("{\n\t\"a\" : 1\n}".getBytes("UTF-8"), 3));
        assertEquals("not JSON: unchanged", "Access\tdenied\n", sanitized("Access\tdenied\n".getBytes("UTF-8"), 2));
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        JsonSpoolSink sink = new JsonSpoolSink(partial);
        sink.write(new byte[] { (byte) 0xEF, (byte) 0xBB }, 0, 2);
        sink.finish();
        assertEquals("a partial BOM at the end is kept", 2, Integer.valueOf(partial.size()));
    }

    /** Serves one body (optionally gzipped) to every request. */
    private static final class FixedBodyServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        private final Thread thread;

        FixedBodyServer(final byte[] body, final boolean gzip) throws IOException {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!listener.isClosed()) {
                        try (Socket socket = listener.accept()) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                // request headers
                            }
                            byte[] payload = body;
                            if (gzip) {
                                ByteArrayOutputStream zipped = new ByteArrayOutputStream();
                                try (GZIPOutputStream out = new GZIPOutputStream(zipped)) {
                                    out.write(body);
                                }
                                payload = zipped.toByteArray();
                            }
                            String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + payload.length
                                + (gzip ? "\r\nContent-Encoding: gzip" : "") + "\r\nConnection: close\r\n\r\n";
                            socket.getOutputStream().write(head.getBytes("UTF-8"));
                            socket.getOutputStream().write(payload);
                            socket.getOutputStream().flush();
                        } catch (IOException e) {
                            // listener closed, or the client went away
                        }
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String url() {
            return "http://127.0.0.1:" + listener.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            thread.join(3000);
        }
    }

    private static java.io.File newSpoolDir() throws IOException {
        java.io.File dir = java.nio.file.Files.createTempDirectory("spool-test").toFile();
        dir.deleteOnExit();
        return dir;
    }

    @SuppressWarnings("unchecked")
    public void testLargeXtreamAnswerIsServedFromAFile() throws Exception {
        // Over SPOOL_IN_HEAP_BYTES, with a raw tab the browser would reject and a BOM.
        StringBuilder json = new StringBuilder("﻿[");
        int items = 0;
        while (json.length() < WebBackend.SPOOL_IN_HEAP_BYTES + 1024) {
            if (items > 0) json.append(',');
            json.append("{\"num\":").append(items).append(",\"name\":\"Série\t").append(items).append("\"}");
            items++;
        }
        json.append(']');
        byte[] body = json.toString().getBytes("UTF-8");
        for (boolean gzip : new boolean[] { false, true }) {
            java.io.File dir = newSpoolDir();
            try (FixedBodyServer server = new FixedBodyServer(body, gzip)) {
                WebBackend backend = new WebBackend(new HttpFetcher(), "/api", dir);
                String id = WebBackendTest.register(backend, server.url());
                WebBackend.Response response = backend.handle("GET", "/xtream",
                    WebBackendTest.query("targetId", id, "action", "get_series", "username", "u", "password", "p"), null);
                assertEquals("status", 200, Integer.valueOf(response.status));
                String[] files = dir.list();
                assertEquals("one spooled file while unserved", 1, Integer.valueOf(files == null ? 0 : files.length));
                long length = response.bodyLength();
                String text = response.bodyText();
                assertEquals("Content-Length matches the body", Long.valueOf(length), Long.valueOf(text.getBytes("UTF-8").length));
                assertTrue("valid for JSON.parse (gzip " + gzip + ")", Json.validate(text));
                Map<String, Object> out = (Map<String, Object>) Json.parse(text);
                assertEquals("action", "get_series", out.get("action"));
                java.util.List<Object> payload = (java.util.List<Object>) out.get("payload");
                assertEquals("every item", Integer.valueOf(items), Integer.valueOf(payload.size()));
                assertEquals("tab kept as an escape", "Série\t0", ((Map<String, Object>) payload.get(0)).get("name"));
                files = dir.list();
                assertEquals("file deleted once served", 0, Integer.valueOf(files == null ? 0 : files.length));
            }
        }
    }

    public void testSmallSpooledXtreamAnswerKeepsTheHeapPath() throws Exception {
        java.io.File dir = newSpoolDir();
        try (FixedBodyServer server = new FixedBodyServer("{\"user_info\":{\"auth\":1}}".getBytes("UTF-8"), false)) {
            WebBackend backend = new WebBackend(new HttpFetcher(), "/api", dir);
            String id = WebBackendTest.register(backend, server.url());
            WebBackend.Response response = backend.handle("GET", "/xtream",
                WebBackendTest.query("targetId", id, "action", "get_account_info"), null);
            assertEquals("same envelope", "{\"action\":\"get_account_info\",\"payload\":{\"user_info\":{\"auth\":1}}}", response.bodyText());
            String[] files = dir.list();
            assertEquals("nothing left behind", 0, Integer.valueOf(files == null ? 0 : files.length));
        }
    }

    private void assertBrokenPlaylistImport(String mode) throws Exception {
        try (BrokenBodyServer server = new BrokenBodyServer(mode)) {
            WebBackend backend = new WebBackend(new HttpFetcher(), "/api");
            String id = WebBackendTest.register(backend, server.url());
            WebBackend.Response response = backend.handle("GET", "/parse", WebBackendTest.query("targetId", id), null);
            assertEquals("failed import is non-2xx", 500, Integer.valueOf(response.status));
            assertEquals("error payload status", 500, ((Number) WebBackendTest.body(response).get("status")).intValue());
            assertTrue("never a playlist", !WebBackendTest.body(response).containsKey("playlist"));
        }
    }

}
