package protocolos;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import config.Config;
import protocolos.tcp.Roles;
import protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlStream;
import protocolos.tcp.mensagens.HelloMessage;
import protocolos.tcp.mensagens.HelloOkMessage;
import protocolos.udp.EventCastFlags;
import protocolos.udp.EventCastType;
import protocolos.udp.SeqNum;
import protocolos.udp.mensagens.DataMessage;

public final class Publisher {

    private Publisher() { }

    public static void run(String id, Config config) throws Exception {
        Config.Endereco broker = config.getEndereco("broker_control");
        String logPath = config.getString("log");
        int startAfter = config.getInt("start_after");
        List<Config.Publish> fluxos = config.publishes();

        Socket tcpSocket = new Socket(broker.host(), broker.porta());
        SubControlStream stream = new SubControlStream(tcpSocket, 5000, 5000);
        DatagramSocket udpSocket = new DatagramSocket();

        try (BufferedWriter logWriter = new BufferedWriter(new FileWriter(logPath, false))) {
            stream.enviar(new HelloMessage(Roles.PUBLISHER, udpSocket.getLocalPort(), id));

            SubControlMessage resp = stream.receber();
            if (!(resp instanceof HelloOkMessage)) {
                throw new IOException("handshake falhou: esperado HELLO_OK");
            }
            int brokerDataPort = ((HelloOkMessage) resp).getUdpPort();
            InetAddress brokerAddr = InetAddress.getByName(broker.host());

            Thread.sleep(startAfter * 1000L);

            int hopSeq = 0;
            long msgId = 1;

            for (Config.Publish fluxo : fluxos) {
                long intervaloMs = 1000 / Math.max(1, fluxo.rate());

                for (int i = 0; i < fluxo.count(); i++) {
                    byte[] conteudo = ("Sensor data " + (i + 1)).getBytes(StandardCharsets.UTF_8);
                    int flags = EventCastFlags.criar(fluxo.qos(), false);
                    DataMessage msg = new DataMessage(EventCastType.PUBLISH, flags, hopSeq,
                            msgId, fluxo.topico(), conteudo);

                    byte[] bytes = msg.serialize();
                    udpSocket.send(new DatagramPacket(bytes, bytes.length, brokerAddr, brokerDataPort));

                    logWriter.write(System.currentTimeMillis() + " " + id + " "
                            + fluxo.topico() + " " + msgId);
                    logWriter.newLine();
                    logWriter.flush();

                    msgId++;
                    hopSeq = SeqNum.proximo(hopSeq);
                    Thread.sleep(intervaloMs);
                }
            }
        } finally {
            udpSocket.close();
            stream.fechar();
        }
    }
}