package dev.bridgedns.vpn;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class ResolverStore {
    private static final String PREFS = "resolver_store";
    private static final String ITEMS = "items";

    static synchronized List<String> load(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEMS, "");
        if (raw.isEmpty()) return defaults();
        try {
            JSONArray array = new JSONArray(raw);
            ArrayList<String> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
            return result.isEmpty() ? defaults() : result;
        } catch (Exception ignored) { return defaults(); }
    }

    private static ArrayList<String> defaults() {
        ArrayList<String> values = new ArrayList<>();
        values.add("1.1.1.1:53");
        values.add("8.8.8.8:53");
        return values;
    }

    static synchronized void save(Context context, List<String> values) throws Exception {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            String normalized = ConfigRules.normalizeResolvers(value.trim()).trim();
            for (String line : normalized.split("\\r?\\n")) if (!line.isEmpty()) unique.add(line);
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("Add at least one resolver");
        JSONArray array = new JSONArray();
        for (String value : unique) array.put(value);
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ITEMS, array.toString()).putBoolean("initialized", true).commit())
            throw new java.io.IOException("Could not save resolver list");
    }

    static synchronized void migrateIfNeeded(Context context, JSONArray profiles) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("initialized", false)) return;
        LinkedHashSet<String> migrated = new LinkedHashSet<>();
        for (int i = 0; i < profiles.length(); i++) {
            try {
                String old = profiles.getJSONObject(i).optString("resolvers", "");
                for (String value : parse(old)) migrated.add(value);
            } catch (Exception ignored) { }
        }
        if (migrated.isEmpty()) {
            migrated.add("1.1.1.1:53");
            migrated.add("8.8.8.8:53");
        }
        try { save(context, new ArrayList<>(migrated)); }
        catch (Exception ignored) { }
    }

    static List<String> parse(String text) throws Exception {
        String source = text == null ? "" : text.trim();
        ArrayList<String> rawValues = new ArrayList<>();
        if (source.startsWith("{")) {
            JSONObject object = new JSONObject(source);
            JSONArray values = object.optJSONArray("resolvers");
            if (values != null) for (int i = 0; i < values.length(); i++) {
                Object item = values.get(i);
                if (item instanceof JSONObject) rawValues.add(((JSONObject) item).optString("address", ((JSONObject) item).optString("resolver", "")));
                else rawValues.add(String.valueOf(item));
            }
            else source = object.optString("resolvers", "");
        } else if (source.startsWith("[")) {
            JSONArray values = new JSONArray(source);
            for (int i = 0; i < values.length(); i++) {
                Object item = values.get(i);
                if (item instanceof JSONObject) rawValues.add(((JSONObject) item).optString("address", ((JSONObject) item).optString("resolver", "")));
                else rawValues.add(String.valueOf(item));
            }
        }
        if (rawValues.isEmpty()) {
            for (String line : source.split("[\\r\\n,;]+")) {
                String value = line.replaceFirst("#.*$", "").trim();
                if (!value.isEmpty()) rawValues.add(value);
            }
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : rawValues) {
            String normalized = ConfigRules.normalizeResolvers(value.trim()).trim();
            for (String line : normalized.split("\\r?\\n")) if (!line.isEmpty()) result.add(line);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No resolvers were found");
        return new ArrayList<>(result);
    }

    static String exportText(Context context) {
        return String.join("\n", load(context)) + "\n";
    }
}
