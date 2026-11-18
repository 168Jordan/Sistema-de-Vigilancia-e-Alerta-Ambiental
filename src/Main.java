import broker.Broker;
import config.Config;

import java.net.InetAddress;
import java.nio.file.Paths;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        try {
            if (args.length < 1) throw new IllegalArgumentException(usage());

            String role = args[0].toLowerCase(java.util.Locale.ROOT);
            if (!role.equals("broker") && !role.equals("publisher") && !role.equals("subscriber")) {
                throw new IllegalArgumentException("papel inválido: " + args[0]);
            }

            String id = null;
            String configPath = null;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--id":
                        id = valorOpcao(args, ++i, "--id");
                        break;
                    case "--config":
                        configPath = valorOpcao(args, ++i, "--config");
                        break;
                    default:
                        throw new IllegalArgumentException("opção desconhecida: " + args[i]);
                }
            }

            if (id == null || id.isBlank()) throw new IllegalArgumentException("--id é obrigatório");
            if (configPath == null || configPath.isBlank()) {
                throw new IllegalArgumentException("--config é obrigatório");
            }

            Config config = Config.load(Paths.get(configPath));

            if (role.equals("broker")) {
                iniciarBroker(id, config);
            } else {
                throw new IllegalArgumentException("papel '" + role
                        + "' ainda não está implementado neste projeto");
            }
        } catch (IllegalArgumentException | java.io.IOException e) {
            System.err.println("Erro: " + e.getMessage());
            System.err.println("Uso: " + usage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Erro ao iniciar: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String valorOpcao(String[] args, int index, String opcao) {
        if (index >= args.length) throw new IllegalArgumentException(opcao + " requer um valor");
        return args[index];
    }

    private static void iniciarBroker(String id, Config config) throws Exception {
        String ip = firstValue(config,
                "broker." + id + ".ip",
                "broker." + id + ".host",
                "broker." + id + ".address",
                id + ".ip", id + ".host", id + ".address",
                "broker.ip", "broker.host", "broker.address",
                "ip", "host");

        int udpPort = firstInt(config, 5000,
                "broker." + id + ".udpPort", "broker." + id + ".udp.port",
                "broker." + id + ".udp", id + ".udpPort", id + ".udp.port", id + ".udp",
                "broker.udpPort", "broker.udp.port", "broker.udp", "udpPort", "udp.port", "udp");

        int tcpPort = firstInt(config, 6000,
                "broker." + id + ".tcpPort", "broker." + id + ".tcp.port",
                "broker." + id + ".tcp", id + ".tcpPort", id + ".tcp.port", id + ".tcp",
                "broker.tcpPort", "broker.tcp.port", "broker.tcp", "tcpPort", "tcp.port", "tcp");

        if (ip == null || ip.isBlank()) ip = "127.0.0.1";
        Broker broker = new Broker(InetAddress.getByName(ip), id, udpPort, tcpPort);
        broker.run();
    }

    private static String firstValue(Config config, String... keys) {
        for (String key : keys) {
            String value = config.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static int firstInt(Config config, int defaultValue, String... keys) {
        for (String key : keys) {
            if (config.containsKey(key)) return config.getInt(key, defaultValue);
        }
        return defaultValue;
    }

    private static String usage() {
        return "java Main <broker|publisher|subscriber> --id <id> --config <ficheiro>";
    }
}
