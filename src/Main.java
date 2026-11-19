import broker.Broker;
import config.Config;
import protocolos.Publisher;
import protocolos.Subscriber;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Paths;
import java.util.Locale;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        try {
            if (args.length < 1) throw new IllegalArgumentException("papel em falta");

            String role = args[0].toLowerCase(Locale.ROOT);
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
            config.validar(role);

            switch (role) {
                case "broker":
                    iniciarBroker(id, config);
                    break;
                case "publisher":
                    Publisher.run(id, config);
                    break;
                default:
                    Subscriber.run(id, config);
                    break;
            }
            System.exit(0);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Erro: " + e.getMessage());
            System.err.println("Uso: " + usage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String valorOpcao(String[] args, int index, String opcao) {
        if (index >= args.length) throw new IllegalArgumentException(opcao + " requer um valor");
        return args[index];
    }

    private static void iniciarBroker(String id, Config config) throws Exception {
        InetAddress bind = InetAddress.getByName(config.getString("bind"));
        int controlPort = config.getInt("control_port");
        int dataPort = config.getInt("data_port");
        long duracaoMs = config.getInt("duration") * 1000L;

        Broker broker = new Broker(bind, id, dataPort, controlPort);
        broker.start();
        Thread.sleep(duracaoMs);
        broker.stop();
    }

    private static String usage() {
        return "run.sh <broker|publisher|subscriber> --id <id> --config <ficheiro>";
    }
}