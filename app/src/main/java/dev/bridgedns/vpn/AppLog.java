package dev.bridgedns.vpn;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

final class AppLog {
    private static final int MAX_LINES = 500;
    private static final long MAX_BYTES = 256 * 1024;

    private static File file(Context context) { return new File(context.getNoBackupFilesDir(), "session-events.log"); }

    static synchronized void append(Context context, String message, String secret) {
        if (message == null || message.trim().isEmpty()) return;
        String safe = secret == null || secret.isEmpty() ? message : message.replace(secret, "[key redacted]");
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        File target = file(context);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(target, true);
            for (String line : safe.split("\\r?\\n")) {
                if (!line.isEmpty()) out.write((stamp + "  " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            out.close();
            trim(target);
        } catch (Exception ignored) { }
    }

    private static void trim(File target) throws Exception {
        if (target.length() <= MAX_BYTES) return;
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(target), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == MAX_LINES) lines.removeFirst();
                lines.addLast(line);
            }
        }
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(target, false), StandardCharsets.UTF_8))) {
            for (String line : lines) { writer.write(line); writer.newLine(); }
        }
    }

    static synchronized String read(Context context) {
        File target = file(context);
        if (!target.isFile()) return "";
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(target.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - MAX_LINES);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception ignored) { return ""; }
    }

    static synchronized void clear(Context context) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file(context), false)) { out.write(new byte[0]); }
        catch (Exception ignored) { }
    }
}
