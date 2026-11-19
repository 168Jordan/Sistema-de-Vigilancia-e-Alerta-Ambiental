package protocolos;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

import config.Config;
import protocolos.tcp.Roles;
import protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlStream;
import protocolos.tcp.mensagens.HelloMessage;
import protocolos.tcp.mensagens.HelloOkMessage;
import protocolos.tcp.mensagens.ReplyMessage;
import protocolos.tcp.mensagens.SubscribeMessage;
import protocolos.udp.EventCastMessage;
import protocolos.udp.EventCastType;
import protocolos.udp.ReceptorJanela;
import protocolos.udp.mensagens.AckMessage;
import protocolos.udp.mensagens.DataMessage;

public final class Subscriber {

    private Subscriber() { }

    public static void run(String id, Config config) throws Exception {
        long inicio = System.currentTimeMillis();
        Config.Endereco broker = config.getEndereco("broker_control");
        String logPath = config.getString("log");
        long duracaoMs = config.getInt("duration") * 1000L;
        List<Config.Subscribe> subscricoes = config.subscribes();

        Socket tcpSocket = new Socket(broker.host(), broker.porta());
        SubControlStream stream = new SubControlStream(tcpSocket, 5000, 5000);
        DatagramSocket udpSocket = new DatagramSocket();

        try (BufferedWriter logWriter = new BufferedWriter(new FileWriter(logPath, false))) {
            stream.enviar(new HelloMessage(Roles.SUBSCRIBER, udpSocket.getLocalPort(), id));

            SubControlMessage resp = stream.receber();
            if (!(resp instanceof HelloOkMessage)) {
                throw new IOException("handshake falhou: esperado HELLO_OK");
            }

            for (Config.Subscribe s : subscricoes) {
                stream.enviar(new SubscribeMessage(s.qos(), s.filtro()));
                SubControlMessage reply = stream.receber();
                if (!(reply instanceof ReplyMessage) || !((ReplyMessage) reply).isOk()) {
                    System.err.println("Aviso: subscrição rejeitada para o filtro " + s.filtro());
                }
            }

            udpSocket.setSoTimeout(1000);
            byte[] buf = new byte[EventCastMessage.TAMANHO_BUFFER];
            ReceptorJanela receptor = new ReceptorJanela();

            while (System.currentTimeMillis() - inicio < duracaoMs) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    udpSocket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                EventCastMessage msg;
                try {
                    msg = EventCastMessage.deserialize(packet.getData(), packet.getLength());
                } catch (IllegalArgumentException e) {
                    continue;   // datagrama inválido: descartado, sem ACK
                }

                if (msg instanceof DataMessage
                        && msg.getEventCastType() == EventCastType.DELIVER) {
                    DataMessage entrega = (DataMessage) msg;
                    boolean nova = receptor.receber(entrega.getHopSeq());

                    // G1 (qos 1): regista todas as chegadas, mesmo repetidas.
                    // G2 (qos 2): regista só a primeira vez que a entrega chega.
                    if (nova || entrega.getQos() == 1) {
                        logWriter.write(System.currentTimeMillis() + " " + id + " "
                                + entrega.getTopico() + " " + entrega.getMsgId());
                        logWriter.newLine();
                        logWriter.flush();
                    }

                    // ACK (base, sack) do estado atual, também para repetidas:
                    // se o ACK anterior se perdeu, o broker retransmite e precisa deste.
                    byte[] ack = receptor.criarAck().serialize();
                    udpSocket.send(new DatagramPacket(ack, ack.length,
                            packet.getAddress(), packet.getPort()));
                }
            }
        } finally {
            udpSocket.close();
            stream.fechar();
        }
    }
}