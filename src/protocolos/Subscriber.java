package protocolos;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import protocolos.tcp.Roles;
import protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlStream;
import protocolos.tcp.mensagens.HelloMessage;
import protocolos.tcp.mensagens.HelloOkMessage;
import protocolos.tcp.mensagens.SubscribeMessage;
import protocolos.tcp.mensagens.UnsubscribeMessage;
import protocolos.tcp.mensagens.ReplyMessage;
import protocolos.udp.EventCastMessage;
import protocolos.udp.EventCastType;
import protocolos.udp.mensagens.AckMessage;
import protocolos.udp.mensagens.DataMessage;

public class Subscriber {

    static class SubInfo {
        String filtro;
        int qos;

        public SubInfo(String filtro, int qos) {
            this.filtro = filtro;
            this.qos = qos;
        }
    }

    public static void main(String[] args) {
        String id = null;
        String configFile = null;

        // 1. Ler argumentos da linha de comandos (--id e --config)
        for (int i = 0; i < args.length; i++) {
            if ("--id".equals(args[i]) && i + 1 < args.length) {
                id = args[++i];
            } else if ("--config".equals(args[i]) && i + 1 < args.length) {
                configFile = args[++i];
            }
        }

        if (id == null || configFile == null) {
            System.err.println("Uso: ./run.sh subscriber-id <id> --config <ficheiro>");
            System.exit(1);
        }

        // 2. Ler o ficheiro de configuração
        Map<String, String> config = new HashMap<>();
        List<SubInfo> subscricoesIniciais = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int commentIdx = line.indexOf('#');
                if (commentIdx != -1) {
                    line = line.substring(0, commentIdx).trim();
                }

                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();

                    if ("subscribe".equals(key)) {
                        // Exemplo: north/station7/humidity qos=2
                        String[] sParts = value.split("\\s+");
                        String filtro = sParts[0];
                        int qos = 1;
                        if (sParts.length > 1 && sParts[1].startsWith("qos=")) {
                            qos = Integer.parseInt(sParts[1].substring(4));
                        }
                        subscricoesIniciais.add(new SubInfo(filtro, qos));
                    } else {
                        config.put(key, value);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler configuração: " + e.getMessage());
            System.exit(1);
        }

        String brokerControl = config.get("broker_control");
        String logPath = config.get("log");
        int duration = Integer.parseInt(config.getOrDefault("duration", "60"));

        if (brokerControl == null || logPath == null) {
            System.err.println("Falta broker_control ou log na configuração.");
            System.exit(1);
        }

        String[] bcParts = brokerControl.split(":");
        String brokerHost = bcParts[0];
        int brokerControlPort = Integer.parseInt(bcParts[1]);

        try {
            // 3. Ligação TCP e Handshake com o SubControlStream
            Socket tcpSocket = new Socket(brokerHost, brokerControlPort);
            SubControlStream stream = new SubControlStream(tcpSocket, 5000, 5000);

            // Socket UDP local para escutar as entregas do broker
            DatagramSocket udpSocket = new DatagramSocket();
            int myUdpPort = udpSocket.getLocalPort();

            HelloMessage hello = new HelloMessage(Roles.SUBSCRIBER, myUdpPort, id);
            stream.enviar(hello);

            SubControlMessage resp = stream.receber();
            if (!(resp instanceof HelloOkMessage)) {
                System.err.println("Handshake falhou: esperado HELLO_OK");
                System.exit(1);
            }

            // 4. Enviar subscrições iniciais via TCP
            for (SubInfo sub : subscricoesIniciais) {
                SubscribeMessage subMsg = new SubscribeMessage(sub.qos, sub.filtro);
                stream.enviar(subMsg);
                // Opcional: receber e validar o REPLY do broker
                SubControlMessage reply = stream.receber();
                if (reply instanceof ReplyMessage && !((ReplyMessage) reply).isOk()) {
                    System.err.println("Aviso: Subscrição rejeitada para o filtro " + sub.filtro);
                }
            }

            // 5. Abrir ficheiro de log (flush linha a linha)
            BufferedWriter logWriter = new BufferedWriter(new FileWriter(logPath, false));

            // Thread de temporizador para terminar após 'duration' segundos
            long startTime = System.currentTimeMillis();
            long durationMs = duration * 1000L;

            udpSocket.setSoTimeout(1000); // timeout de 1s para verificar a duração periodicamente

            // 6. Ciclo de receção de dados UDP (EventCast)
            while (System.currentTimeMillis() - startTime < durationMs) {
                try {
                    byte[] buf = new byte[1500]; // Tamanho do buffer do EventCastMessage
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);

                    // Deserializar a mensagem recebida via UDP
                    EventCastMessage msg = EventCastMessage.deserialize(packet.getData(), packet.getLength());

                    if (msg instanceof DataMessage) {
                        DataMessage entrega = (DataMessage) msg;
                        if (entrega.getEventCastType() == EventCastType.DELIVER) {
                            
                            // Escrever imediatamente no log: <timestamp_ms> <id> <topico> <message_id>
                            long timestampMs = System.currentTimeMillis();
                            logWriter.write(timestampMs + " " + id + " " + entrega.getTopico() + " " + entrega.getMsgId());
                            logWriter.newLine();
                            logWriter.flush(); // Flush imediato por linha (exigido pelo enunciado)

                            // Enviar ACK de volta ao broker (se necessário pelo QoS)
                            if (entrega.getQos() > 0) {
                                AckMessage ack = new AckMessage(entrega.getHopSeq(), 0);
                                byte[] ackBytes = ack.serialize();
                                DatagramPacket ackPacket = new DatagramPacket(
                                    ackBytes, ackBytes.length, packet.getAddress(), packet.getPort()
                                );
                                udpSocket.send(ackPacket);
                            }
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // Timeout normal para reavaliar a condição do ciclo (duration)
                }
            }

            logWriter.close();
            udpSocket.close();
            stream.fechar();
            System.out.println("Subscriber terminou com sucesso.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}