package config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Carregamento simples de ficheiros de configuração por chave=valor ou secções INI. */
public final class Config {
    private final Map<String, String> values;

    private Config(Map<String, String> values) {
        this.values = values;
    }

    public static Config load(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("ficheiro de configuração não indicado");
        if (!Files.isRegularFile(path)) {
            throw new IOException("ficheiro de configuração não encontrado: " + path);
        }

        Map<String, String> result = new LinkedHashMap<>();
        String section = "";
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }

                int separator = line.indexOf('=');
                if (separator < 0) separator = line.indexOf(':');
                if (separator < 0) {
                    throw new IOException("linha inválida em " + path + ": " + line);
                }

                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (value.isEmpty()) throw new IOException("valor vazio para '" + key + "' em " + path);

                put(result, key, value);
                if (!section.isEmpty()) {
                    put(result, section + "." + key, value);
                }
            }
        }

        if (result.isEmpty()) throw new IOException("ficheiro de configuração vazio: " + path);
        return new Config(result);
    }

    private static void put(Map<String, String> values, String key, String value) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        values.put(normalized, value);
    }

    public boolean containsKey(String key) {
        return values.containsKey(normalize(key));
    }

    public String get(String key) {
        return values.get(normalize(key));
    }

    public String get(String key, String defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : value;
    }

    public String getFirst(String defaultValue, String... keys) {
        for (String key : keys) {
            String value = get(key);
            if (value != null) return value;
        }
        return defaultValue;
    }

    public String getRequired(String role, String id, String... names) {
        for (String name : names) {
            String[] candidates = {
                role + "." + id + "." + name,
                id + "." + name,
                role + "_" + id + "_" + name,
                role + "." + name
            };
            for (String candidate : candidates) {
                if (containsKey(candidate)) return get(candidate);
            }
        }
        throw new IllegalArgumentException("configuração em falta para " + role + " '" + id
                + "' (esperado um valor como " + role + "." + id + "." + names[0] + ")");
    }

    public int getRequiredInt(String role, String id, String... names) {
        String value = getRequired(role, id, names);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("valor inteiro inválido para "
                    + role + '.' + id + '.' + names[0] + ": " + value);
        }
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("valor inteiro inválido para " + key + ": " + value);
        }
    }

    public Map<String, String> asMap() {
        return new LinkedHashMap<>(values);
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
