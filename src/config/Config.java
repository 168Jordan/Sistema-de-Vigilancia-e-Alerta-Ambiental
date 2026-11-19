package config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ficheiro de configuração do enunciado (secção 5.2): "chave = valor", '#' inicia
 * comentário até ao fim da linha. Chaves únicas (Tabela 4) aparecem uma vez;
 * chaves de lista (Tabela 5) aparecem zero ou mais vezes, por ordem. Sem valores por omissão.
 */
public final class Config {

    public record Endereco(String host, int porta) { }
    public record Publish(String topico, int qos, int count, int rate) { }
    public record Subscribe(String filtro, int qos) { }
    /** Ação temporizada: subscribe (com qos) ou unsubscribe (qos = 0). */
    public record At(int segundos, boolean subscribe, String filtro, int qos) { }

    private static final Set<String> UNICAS = Set.of(
            "bind", "control_port", "data_port", "broker_control", "log",
            "duration", "start_after", "drain", "evict_after");
    private static final Set<String> LISTAS = Set.of("peer", "publish", "subscribe", "at");

    private static final Map<String, List<String>> OBRIGATORIAS = Map.of(
            "broker", List.of("bind", "control_port", "data_port", "duration", "evict_after"),
            "publisher", List.of("broker_control", "log", "start_after", "drain"),
            "subscriber", List.of("broker_control", "log", "duration"));

    private final Map<String, List<String>> valores;

    private Config(Map<String, List<String>> valores) {
        this.valores = valores;
    }

    public static Config load(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("ficheiro de configuração não encontrado: " + path);
        }
        Map<String, List<String>> valores = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String linha;
            int n = 0;
            while ((linha = reader.readLine()) != null) {
                n++;
                int comentario = linha.indexOf('#');
                if (comentario >= 0) linha = linha.substring(0, comentario);
                linha = linha.strip();
                if (linha.isEmpty()) continue;

                int sep = linha.indexOf('=');
                if (sep < 0) throw new IOException("linha " + n + " inválida: " + linha);
                String chave = linha.substring(0, sep).strip();
                String valor = linha.substring(sep + 1).strip();
                if (valor.isEmpty()) throw new IOException("linha " + n + ": valor vazio para '" + chave + "'");

                boolean lista = LISTAS.contains(chave);
                if (!lista && !UNICAS.contains(chave)) {
                    throw new IOException("linha " + n + ": chave desconhecida '" + chave + "'");
                }
                List<String> existentes = valores.computeIfAbsent(chave, k -> new ArrayList<>());
                if (!lista && !existentes.isEmpty()) {
                    throw new IOException("linha " + n + ": chave repetida '" + chave + "'");
                }
                existentes.add(valor);
            }
        }
        return new Config(valores);
    }

    /** Verifica que as chaves obrigatórias do papel estão presentes. */
    public void validar(String papel) {
        List<String> chaves = OBRIGATORIAS.get(papel);
        if (chaves == null) throw new IllegalArgumentException("papel desconhecido: " + papel);
        for (String chave : chaves) {
            if (!valores.containsKey(chave)) {
                throw new IllegalArgumentException("chave em falta para " + papel + ": " + chave);
            }
        }
    }

    // ------------------------------------------------------------ chaves únicas

    public String getString(String chave) {
        List<String> l = valores.get(chave);
        if (l == null || l.isEmpty()) throw new IllegalArgumentException("chave em falta: " + chave);
        return l.get(0);
    }

    public int getInt(String chave) {
        return inteiro(getString(chave), chave);
    }

    public Endereco getEndereco(String chave) {
        return endereco(getString(chave), chave);
    }

    // ------------------------------------------------------------ chaves de lista

    public List<Endereco> peers() {
        List<Endereco> r = new ArrayList<>();
        for (String v : lista("peer")) r.add(endereco(v, "peer"));
        return r;
    }

    public List<Publish> publishes() {
        List<Publish> r = new ArrayList<>();
        for (String v : lista("publish")) {
            String[] t = v.split("\\s+");
            Map<String, String> o = opcoes(t, v);
            r.add(new Publish(t[0], qos(o, v),
                    inteiro(obrigatoria(o, "count", v), v),
                    inteiro(obrigatoria(o, "rate", v), v)));
        }
        return r;
    }

    public List<Subscribe> subscribes() {
        List<Subscribe> r = new ArrayList<>();
        for (String v : lista("subscribe")) {
            String[] t = v.split("\\s+");
            Map<String, String> o = opcoes(t, v);
            r.add(new Subscribe(t[0], qos(o, v)));
        }
        return r;
    }

    public List<At> ats() {
        List<At> r = new ArrayList<>();
        for (String v : lista("at")) {
            String[] t = v.split("\\s+");
            int segundos = inteiro(t[0], v);
            Map<String, String> o = opcoes(t, v);
            if (o.containsKey("subscribe") == o.containsKey("unsubscribe")) {
                throw new IllegalArgumentException("at: esperado subscribe= ou unsubscribe=: " + v);
            }
            if (o.containsKey("subscribe")) {
                r.add(new At(segundos, true, o.get("subscribe"), qos(o, v)));
            } else {
                r.add(new At(segundos, false, o.get("unsubscribe"), 0));
            }
        }
        return r;
    }

    // ------------------------------------------------------------ auxiliares

    private List<String> lista(String chave) {
        List<String> l = valores.get(chave);
        return l == null ? List.of() : l;
    }

    private static Map<String, String> opcoes(String[] tokens, String contexto) {
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i < tokens.length; i++) {
            int eq = tokens[i].indexOf('=');
            if (eq <= 0 || eq == tokens[i].length() - 1) {
                throw new IllegalArgumentException("opção inválida '" + tokens[i] + "' em: " + contexto);
            }
            o.put(tokens[i].substring(0, eq), tokens[i].substring(eq + 1));
        }
        return o;
    }

    private static String obrigatoria(Map<String, String> o, String nome, String contexto) {
        String v = o.get(nome);
        if (v == null) throw new IllegalArgumentException("opção '" + nome + "' em falta em: " + contexto);
        return v;
    }

    private static int qos(Map<String, String> o, String contexto) {
        int q = inteiro(obrigatoria(o, "qos", contexto), contexto);
        if (q != 1 && q != 2) throw new IllegalArgumentException("qos inválido em: " + contexto);
        return q;
    }

    private static int inteiro(String texto, String contexto) {
        try {
            return Integer.parseInt(texto);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("inteiro inválido '" + texto + "' em: " + contexto);
        }
    }

    private static Endereco endereco(String valor, String contexto) {
        int sep = valor.lastIndexOf(':');
        if (sep <= 0 || sep == valor.length() - 1) {
            throw new IllegalArgumentException("esperado endereço:porta em " + contexto + ": " + valor);
        }
        int porta = inteiro(valor.substring(sep + 1), contexto);
        if (porta < 1 || porta > 65535) {
            throw new IllegalArgumentException("porta fora de 1..65535 em " + contexto + ": " + valor);
        }
        return new Endereco(valor.substring(0, sep), porta);
    }
}
