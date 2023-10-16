package peergos.server.util;

import peergos.shared.io.ipfs.api.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class JavaPoster implements HttpPoster {

    private final URL dht;
    private final boolean useGet;
    private final Optional<String> basicAuth;

    public JavaPoster(URL dht, boolean isPublicServer, Optional<String> basicAuth) {
        this.dht = dht;
        this.useGet = isPublicServer;
        this.basicAuth = basicAuth;
    }

    public JavaPoster(URL dht, boolean isPublicServer) {
        this(dht, isPublicServer, Optional.empty());
    }

    public URL buildURL(String method) throws IOException {
        try {
            return new URL(dht, method);
        } catch (MalformedURLException mexican) {
            throw new IOException(mexican);
        }
    }

    @Override
    public CompletableFuture<byte[]> postUnzip(String url, byte[] payload, int timeoutMillis) {
        return post(url, payload, true, timeoutMillis);
    }

    @Override
    public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, int timeoutMillis) {
        return post(url, payload, unzip, Collections.emptyMap(), timeoutMillis);
    }

    private CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, Map<String, String> headers, int timeoutMillis) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        HttpHeaders responseHeaders = null;
        try
        {
            String urlStr = buildURL(url).toString();
            URI uri = URI.create(urlStr);
            HttpClient.Builder builder  = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofMillis(1_000));
            HttpClient client = builder.build();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            if (timeoutMillis >= 0)
                requestBuilder.timeout(Duration.ofMillis(timeoutMillis));
            for (Map.Entry<String, String> e : headers.entrySet()) {
                requestBuilder.setHeader(e.getKey(), e.getValue());
            }
            if (basicAuth.isPresent())
                requestBuilder.setHeader("Authorization", basicAuth.get());

            HttpRequest request  = requestBuilder.build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            responseHeaders = response.headers();
            Optional<String> contentEncodingOpt = responseHeaders.firstValue("content-encoding");
            boolean isGzipped = contentEncodingOpt.isPresent() && "gzip".equals(contentEncodingOpt.get());
            DataInputStream din = new DataInputStream(isGzipped && unzip ? new GZIPInputStream(response.body()) : response.body());
            byte[] resp = Serialize.readFully(din);
            din.close();
            res.complete(resp);
        } catch (SocketTimeoutException e) {
            res.completeExceptionally(new SocketTimeoutException("Socket timeout on: " + url));
        } catch (InterruptedException ex) {
            res.completeExceptionally(new RuntimeException(ex));
        } catch (IOException e) {
            if (responseHeaders != null){
                Optional<String> trailer = responseHeaders.firstValue("Trailer");
                if (trailer.isPresent())
                    System.err.println("Trailer:" + trailer);
                else
                    System.err.println(e.getMessage() + " retrieving " + url);
                res.completeExceptionally(trailer.isEmpty() ? e : new RuntimeException(trailer.get()));
            } else
                res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files, int timeoutMillis) {
        try {
            Map<String, String> headers = new HashMap<>();
            if (basicAuth.isPresent())
                headers.put("Authorization", basicAuth.get());
            Multipart mPost = new Multipart(buildURL(url).toString(), "UTF-8", headers);
            int i = 0;
            for (byte[] file : files) {
                String fieldName = "file" + i++;
                mPost.addFilePart(fieldName, new NamedStreamable.ByteArrayWrapper(Optional.of(fieldName), file));
            }
            return CompletableFuture.completedFuture(mPost.finish().getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files) {
        return postMultipart(url, files, -1);
    }

    @Override
    public CompletableFuture<byte[]> put(String url, byte[] body, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) buildURL(url).openConnection();
            conn.setRequestMethod("PUT");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (basicAuth.isPresent())
                conn.setRequestProperty("Authorization", basicAuth.get());
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            out.write(body);
            out.flush();
            out.close();

            InputStream in = conn.getInputStream();
            return Futures.of(Serialize.readFully(in));
        } catch (IOException e) {
            CompletableFuture<byte[]> res = new CompletableFuture<>();
            if (conn != null) {
                try {
                    InputStream err = conn.getErrorStream();
                    res.completeExceptionally(new IOException("HTTP " + conn.getResponseCode() + ": " + conn.getResponseMessage()));
                } catch (IOException f) {
                    res.completeExceptionally(f);
                }
            } else
                res.completeExceptionally(e);
            return res;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override
    public CompletableFuture<byte[]> get(String url) {
        return get(url, Collections.emptyMap());
    }

    @Override
    public CompletableFuture<byte[]> get(String url, Map<String, String> headers) {
        if (useGet) {
            return publicGet(url, headers);
        } else {
            // This changes to a POST with an empty body
            // The reason for this is browsers allow any website to do a get request to localhost
            // but they block POST requests. So this prevents random websites from calling APIs on localhost
            return postUnzip(url, new byte[0]);
        }
    }

    private CompletableFuture<byte[]> publicGet(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) buildURL(url).openConnection();
                conn.setReadTimeout(15000);
                conn.setDoInput(true);
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
                if (basicAuth.isPresent())
                    conn.setRequestProperty("Authorization", basicAuth.get());

                String contentEncoding = conn.getContentEncoding();
                boolean isGzipped = "gzip".equals(contentEncoding);
                DataInputStream din = new DataInputStream(isGzipped ? new GZIPInputStream(conn.getInputStream()) : conn.getInputStream());
                return Serialize.readFully(din);
            } catch (SocketTimeoutException e) {
                throw new RuntimeException("Timeout retrieving: " + url, e);
            } catch (IOException e) {
                String trailer = conn.getHeaderField("Trailer");
                if (trailer != null)
                    try {
                        throw new IllegalStateException(URLDecoder.decode(trailer, "UTF-8"));
                    } catch (UnsupportedEncodingException f) {}
                throw new RuntimeException(e);
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }, ForkJoinPool.commonPool());
    }

    @Override
    public String toString() {
        return dht.toString();
    }
}
