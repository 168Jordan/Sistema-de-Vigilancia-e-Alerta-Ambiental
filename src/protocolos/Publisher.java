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
import protocolos.udp.EventCastFlags;
import protocolos.udp.EventCastType;
import protocolos.udp.SeqNum;
import protocolos.udp.mensagens.DataMessage;

public class Publisher {

    static class FluxoPublish {
        String topico;
        int qos;
        int count;
        int rate;

        public FluxoPublish(String topico, int qos, int count, int rate) {
            this.topico = topico;
            this.qos = qos;
            this.count = count;
            this.rate = rate;
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
            System.err.println("Uso: ./run.sh publisher-id <id> --config <ficheiro>");
            System.exit(1);
        }

        // 2. Ler o ficheiro de configuração
        Map<String, String> config = new HashMap<>();
        List<FluxoPublish> fluxos = new ArrayList<>();

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

                    if ("publish".equals(key)) {
                        String[] pParts = value.split("\\s+");
                        String topico = pParts[0];
                        int qos = 1;
                        int count = 1;
                        int rate = 1;

                        for (int i = 1; i < pParts.length; i++) {
                            if (pParts[i].startsWith("qos=")) qos = Integer.parseInt(pParts[i].substring(4));
                            if (pParts[i].startsWith("count=")) count = Integer.parseInt(pParts[i].substring(6));
                            if (pParts[i].startsWith("rate=")) rate = Integer.parseInt(pParts[i].substring(5));
                        }
                        fluxos.add(new FluxoPublish(topico, qos, count, rate));
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
        int startAfter = Integer.parseInt(config.getOrDefault("start_after", "0"));

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

            DatagramSocket udpSocket = new DatagramSocket();
            int myUdpPort = udpSocket.getLocalPort();

            HelloMessage hello = new HelloMessage(Roles.PUBLISHER, myUdpPort, id);
            stream.enviar(hello);

            SubControlMessage resp = stream.receber();
            int brokerDataPort = -1;
            if (resp instanceof HelloOkMessage) {
                brokerDataPort = ((HelloOkMessage) resp).getUdpPort();
            } else {
                System.err.println("Handshake falhou: esperado HELLO_OK");
                System.exit(1);
            }

            InetAddress brokerAddr = InetAddress.getByName(brokerHost);
            Thread.sleep(startAfter * 1000L);

            // 4. Ficheiro de log (com flush imediato linha a linha)
            BufferedWriter logWriter = new BufferedWriter(new FileWriter(logPath, false));

            int hopSeq = 0;
            long globalMsgId = 1;

            // 5. Ciclo de envio de publicações UDP
            for (FluxoPublish fluxo : fluxos) {
                long intervalMs = 1000 / Math.max(1, fluxo.rate);

                for (int i = 0; i < fluxo.count; i++) {
                    String conteudoStr = "Sensor data " + (i + 1);
                    byte[] conteudoBytes = conteudoStr.getBytes(StandardCharsets.UTF_8);

                    int flags = EventCastFlags.criar(fluxo.qos, false);
                    DataMessage pubMsg = new DataMessage(
                        EventCastType.PUBLISH, flags, hopSeq, globalMsgId, fluxo.topico, conteudoBytes
                    );

                    byte[] datagramBytes = pubMsg.serialize();
                    DatagramPacket packet = new DatagramPacket(
                        datagramBytes, datagramBytes.length, brokerAddr, brokerDataPort
                    );

                    udpSocket.send(packet);

                    // Registo obrigatório: <timestamp_ms> <id> <topico> <message_id>
                    long timestampMs = System.currentTimeMillis();
                    logWriter.write(timestampMs + " " + id + " " + fluxo.topico + " " + globalMsgId);
                    logWriter.newLine();
                    logWriter.flush(); // Flush imediato por linha

                    globalMsgId++;
                    hopSeq = SeqNum.proximo(hopSeq); // Uso da tua aritmética modular de 16 bits

                    Thread.sleep(intervalMs);
                }
            }

            logWriter.close();
            udpSocket.close();
            stream.fechar();
            System.out.println("Publisher executado com sucesso.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}