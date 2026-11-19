package protocolos;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import config.Config;
import protocolos.tcp.Roles;
import protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlStream;
import protocolos.tcp.mensagens.HelloMessage;
import protocolos.tcp.mensagens.HelloOkMessage;
import protocolos.udp.EmissorJanela;
import protocolos.udp.EventCastMessage;
import protocolos.udp.EventCastType;
import protocolos.udp.mensagens.AckMessage;

public final class Publisher {

    /** Temporizador de retransmissão por mensagem (ms). */
    static final long RTO_MS = 200;

    private Publisher() { }

    public static void run(String id, Config config) throws Exception {
        Config.Endereco broker = config.getEndereco("broker_control");
        String logPath = config.getString("log");
        int startAfter = config.getInt("start_after");
        long drainMs = config.getInt("drain") * 1000L;
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
            final int brokerDataPort = ((HelloOkMessage) resp).getUdpPort();
            final InetAddress brokerAddr = InetAddress.getByName(broker.host());

            EmissorJanela emissor = new EmissorJanela(m -> {
                byte[] bytes = m.serialize();
                udpSocket.send(new DatagramPacket(bytes, bytes.length, brokerAddr, brokerDataPort));
            }, RTO_MS);

            Thread.sleep(startAfter * 1000L);

            int fluxo = 0;          // fluxo corrente
            int noFluxo = 0;        // publicações já feitas nesse fluxo
            long msgId = 1;
            long proximoEnvio = System.currentTimeMillis();
            long fimDrain = Long.MAX_VALUE;
            byte[] buf = new byte[EventCastMessage.TAMANHO_BUFFER];

            fluxo = saltarVazios(fluxos, fluxo);

            while (true) {
                long agora = System.currentTimeMillis();
                boolean haPorEnviar = fluxo < fluxos.size();

                // 1. envia a próxima publicação se for a hora e a janela tiver espaço
                if (haPorEnviar && agora >= proximoEnvio && emissor.temEspaco()) {
                    Config.Publish f = fluxos.get(fluxo);
                    byte[] conteudo = ("Sensor data " + (noFluxo + 1)).getBytes(StandardCharsets.UTF_8);
                    emissor.enviar(EventCastType.PUBLISH, f.qos(), msgId, f.topico(), conteudo, agora);

                    logWriter.write(agora + " " + id + " " + f.topico() + " " + msgId);
                    logWriter.newLine();
                    logWriter.flush();

                    msgId++;
                    noFluxo++;
                    proximoEnvio = agora + 1000 / Math.max(1, f.rate());
                    if (noFluxo >= f.count()) {
                        fluxo = saltarVazios(fluxos, fluxo + 1);
                        noFluxo = 0;
                        if (fluxo >= fluxos.size()) fimDrain = agora + drainMs;
                    }
                }

                // 2. retransmite o que expirou
                emissor.retransmitirExpirados(agora);

                // 3. terminação: tudo confirmado, ou esgotou o drain
                haPorEnviar = fluxo < fluxos.size();
                if (!haPorEnviar && emissor.tudoConfirmado()) break;
                if (agora >= fimDrain) {
                    System.err.println("Aviso: " + emissor.emVoo()
                            + " publicações sem confirmação ao fim de drain");
                    break;
                }

                // 4. espera por um ACK até ao próximo evento (envio, expiração ou fim do drain)
                long prazo = Math.min(emissor.proximoPrazo(), fimDrain);
                if (haPorEnviar && emissor.temEspaco()) prazo = Math.min(prazo, proximoEnvio);
                long espera = Math.max(1, Math.min(1000, prazo - System.currentTimeMillis()));
                udpSocket.setSoTimeout((int) espera);

                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    udpSocket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try {
                    EventCastMessage msg = EventCastMessage.deserialize(
                            packet.getData(), packet.getLength());
                    if (msg instanceof AckMessage) emissor.aoReceberAck((AckMessage) msg);
                } catch (IllegalArgumentException ignored) {
                    // datagrama inválido: descartado
                }
            }
            System.err.println(id + ": publicadas=" + (msgId - 1)
                    + " retransmissões=" + emissor.getRetransmissoes());
        } finally {
            udpSocket.close();
            stream.fechar();
        }
    }

    private static int saltarVazios(List<Config.Publish> fluxos, int i) {
        while (i < fluxos.size() && fluxos.get(i).count() == 0) i++;
        return i;
    }
}