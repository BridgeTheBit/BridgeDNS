package dev.bridgedns.vpn;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class PublicIpProbe {
    static String fetch(boolean throughTunnel) throws Exception {
        return throughTunnel ? tunnel() : direct();
    }

    private static String direct() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://api.ipify.org").openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "text/plain");
        connection.setRequestProperty("User-Agent", "BridgeDNS-Android");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Public IP service returned HTTP " + connection.getResponseCode());
            byte[] bytes = readLimited(connection.getInputStream(), 128);
            return validate(new String(bytes, StandardCharsets.US_ASCII));
        } finally { connection.disconnect(); }
    }

    private static String tunnel() throws Exception {
        try (Socket raw = DataProbe.openSocks("api.ipify.org", 443, 18000, ignored -> {})) {
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, "api.ipify.org", 443, true);
            try (SSLSocket socket = tls) {
                socket.setSoTimeout(8000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: api.ipify.org\r\nUser-Agent: BridgeDNS-Android\r\nAccept: text/plain\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                String status = line(input);
                if (!status.matches("HTTP/\\d(?:\\.\\d)? 200(?: .*)?")) throw new IOException("Public IP service returned " + status);
                int contentLength = -1;
                boolean chunked = false;
                for (int i = 0; i < 64; i++) {
                    String header = line(input);
                    if (header.isEmpty()) break;
                    int colon = header.indexOf(':');
                    if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                        try { contentLength = Integer.parseInt(header.substring(colon + 1).trim()); } catch (NumberFormatException ignored) { }
                    }
                    if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Transfer-Encoding"))
                        chunked = header.substring(colon + 1).toLowerCase(java.util.Locale.ROOT).contains("chunked");
                }
                byte[] body = chunked ? readChunked(input, 128) : contentLength >= 0 ? readExact(input, Math.min(contentLength, 128)) : readLimited(input, 128);
                return validate(new String(body, StandardCharsets.US_ASCII));
            }
        }
    }

    private static String line(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        while (out.size() < 4096) {
            int value = input.read();
            if (value < 0) throw new IOException("Public IP response ended early");
            out.write(value);
            if (previous == '\r' && value == '\n') {
                byte[] data = out.toByteArray();
                return new String(data, 0, data.length - 2, StandardCharsets.ISO_8859_1);
            }
            previous = value;
        }
        throw new IOException("Public IP response header is too long");
    }

    private static byte[] readExact(java.io.InputStream input, int count) throws IOException {
        byte[] data = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = input.read(data, offset, count - offset);
            if (n < 0) throw new IOException("Public IP response body ended early");
            offset += n;
        }
        return data;
    }

    private static byte[] readChunked(BufferedInputStream input, int max) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = line(input).trim();
            int extension = sizeLine.indexOf(';');
            if (extension >= 0) sizeLine = sizeLine.substring(0, extension);
            final int size;
            try { size = Integer.parseInt(sizeLine, 16); }
            catch (NumberFormatException e) { throw new IOException("Invalid chunked public IP response"); }
            if (size == 0) {
                while (!line(input).isEmpty()) { }
                break;
            }
            if (size < 0 || body.size() + size > max) throw new IOException("Public IP response is too long");
            body.write(readExact(input, size));
            if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid chunked public IP response");
        }
        return body.toByteArray();
    }

    private static byte[] readLimited(java.io.InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        while (out.size() < max) {
            int n = input.read(buffer, 0, Math.min(buffer.length, max - out.size()));
            if (n < 0) break;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String validate(String value) throws IOException {
        String ip = value.trim();
        if (ip.isEmpty() || ip.length() > 64 || !ip.matches("[0-9a-fA-F:.]+") || !(ip.contains(":") || ip.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")))
            throw new IOException("Invalid public IP response");
        return ip;
    }
}
