package broker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import protocolos.tcp.ReplyStatus;
import protocolos.tcp.Roles;
import protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlStream;
import protocolos.tcp.SubControlType;
import protocolos.tcp.mensagens.HelloMessage;
import protocolos.tcp.mensagens.HelloOkMessage;
import protocolos.tcp.mensagens.RegisterMessage;
import protocolos.tcp.mensagens.ReplyMessage;
import protocolos.tcp.mensagens.SubscribeMessage;
import protocolos.tcp.mensagens.UnsubscribeMessage;
import protocolos.udp.EventCastMessage;
import protocolos.udp.mensagens.AckMessage;
import protocolos.udp.mensagens.DataMessage;

public class Broker {

    private final Map<String, InetAddress> subscribersIP = new ConcurrentHashMap<>();
    private final Map<String, Integer> subscribersPorts = new ConcurrentHashMap<>();
    private final Map<String, InetAddress> publishersIP = new ConcurrentHashMap<>();
    private final Map<String, Integer> publishersPorts = new ConcurrentHashMap<>();

    /* Mantidas para compatibilidade com os campos da versão inicial. */
    private final Map<String, Map<String, Integer>> subscriberSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> publisherRegistrations = new ConcurrentHashMap<>();
    private final Map<String, DataMessage> publishedTopics = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> subscriberSequences = new ConcurrentHashMap<>();
    private final Set<String> receivedPublisherMessages = ConcurrentHashMap.newKeySet();

    private final String brokerId;
    private final int brokerUDPPort;
    private final int brokerTCPPort;
    private final InetAddress brokerIP;

    private volatile boolean running;
    private volatile ServerSocket tcpServer;
    private volatile DatagramSocket udpSocket;
    private Thread tcpThread;
    private Thread udpThread;

    private static final long HANDSHAKE_TIMEOUT_MS = 5000;
    private static final long REPLY_TIMEOUT_MS = 5000;

    public Broker(InetAddress brokerIP, String brokerId, int brokerUDPPort, int brokerTCPPort) throws IOException {
        if (brokerId == null || brokerId.isBlank()) {
            throw new IllegalArgumentException("id do broker não pode ser vazio");
        }
        validarPorta(brokerUDPPort, "porta UDP");
        validarPorta(brokerTCPPort, "porta TCP");
        this.brokerId = brokerId;
        this.brokerUDPPort = brokerUDPPort;
        this.brokerTCPPort = brokerTCPPort;
        this.brokerIP = brokerIP == null ? InetAddress.getLoopbackAddress() : brokerIP;
    }

    

    public synchronized void start() throws IOException {
        if (running) return;

        tcpServer = new ServerSocket(brokerTCPPort, 50, brokerIP);
        udpSocket = new DatagramSocket(brokerUDPPort, brokerIP);
        running = true;

        tcpThread = new Thread(this::aceitarLigacoesTCP, "broker-tcp-" + brokerId);
        tcpThread.setDaemon(true);
        tcpThread.start();

        udpThread = new Thread(this::receberDatagramasUDP, "broker-udp-" + brokerId);
        udpThread.setDaemon(true);
        udpThread.start();
    }

    public void run() throws IOException {
        start();
        try {
            while (running) Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        }
    }

    public synchronized void stop() {
        running = false;
        if (tcpServer != null && !tcpServer.isClosed()) {
            try { tcpServer.close(); } catch (IOException ignored) { }
        }
        if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
    }


    // Tratar TCP 
    private void aceitarLigacoesTCP() {
        while (running) {
            try {
                Socket socket = tcpServer.accept();
                Thread session = new Thread(() -> tratarSessaoTCP(socket),
                        "broker-tcp-session-" + socket.getInetAddress().getHostAddress());
                session.setDaemon(true);
                session.start();
            } catch (IOException e) {
                if (running) System.err.println("Erro a aceitar ligação TCP: " + e.getMessage());
            }
        }
    }

    private void tratarSessaoTCP(Socket socket) {
        String id = null;
        Roles role = null;
        try {
            SubControlStream stream = new SubControlStream(socket,
                    HANDSHAKE_TIMEOUT_MS, REPLY_TIMEOUT_MS);

            SubControlMessage first = stream.receber();
            if (first == null || first.getSubControlType() != SubControlType.HELLO) {
                stream.fechar();
                return;
            }

            HelloMessage hello = (HelloMessage) first;
            id = hello.getId();
            role = hello.getRole();

            if (role == Roles.PUBLISHER) {
                publishersIP.put(id, socket.getInetAddress());
                publishersPorts.put(id, hello.getUdpPort());
            } else if (role == Roles.SUBSCRIBER) {
                subscribersIP.put(id, socket.getInetAddress());
                subscribersPorts.put(id, hello.getUdpPort());
                subscriberSubscriptions.putIfAbsent(id, new ConcurrentHashMap<>());
                subscriberSequences.putIfAbsent(id, new AtomicInteger(0));
            }

            stream.enviar(new HelloOkMessage(brokerUDPPort));

            SubControlMessage message;
            while ((message = stream.receber()) != null) {
                SubControlType type = message.getSubControlType();
                if (type == SubControlType.REGISTER && role == Roles.PUBLISHER) {
                    for (RegisterMessage.Fluxo fluxo : ((RegisterMessage) message).getFluxos()) {
                        registerPublisherTopic(id, fluxo.getTopico(), fluxo.getQos());
                    }
                    stream.enviar(new ReplyMessage(SubControlType.REGISTER, ReplyStatus.OK));

                } else if (type == SubControlType.SUBSCRIBE && role == Roles.SUBSCRIBER) {
                    SubscribeMessage subscribe = (SubscribeMessage) message;
                    subscribe(id, subscribe.getFiltro(), subscribe.getQos(),
                            socket.getInetAddress(), subscribersPorts.get(id));
                    stream.enviar(new ReplyMessage(SubControlType.SUBSCRIBE, ReplyStatus.OK));

                } else if (type == SubControlType.UNSUBSCRIBE && role == Roles.SUBSCRIBER) {
                    String filtro = ((UnsubscribeMessage) message).getFiltro();
                    unsubscribe(id, filtro);
                    stream.enviar(new ReplyMessage(SubControlType.UNSUBSCRIBE, ReplyStatus.OK));

                } else if (type == SubControlType.BYE) {
                    stream.enviar(new ReplyMessage(SubControlType.BYE, ReplyStatus.OK));
                    stream.fechar();
                    break;

                } else {
                    stream.enviar(new ReplyMessage(type, ReplyStatus.BAD_REQUEST));
                }
            }
        } catch (IOException e) {
            // A stream já trata o fecho e os motivos da sessão.
        } finally {
            if (id != null) {
                if (role == Roles.PUBLISHER) {
                    publishersIP.remove(id);
                    publishersPorts.remove(id);
                    publisherRegistrations.remove(id);
                } else if (role == Roles.SUBSCRIBER) {
                    subscribersIP.remove(id);
                    subscribersPorts.remove(id);
                    subscriberSubscriptions.remove(id);
                    subscriberSequences.remove(id);
                }
            }
        }
    }

    // Tratar UDP
    private void receberDatagramasUDP() {
        byte[] buffer = new byte[EventCastMessage.TAMANHO_BUFFER];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(packet);
                try {
                    EventCastMessage message = EventCastMessage.deserialize(
                            packet.getData(), packet.getLength());

                    if (message instanceof DataMessage) {
                        tratarDataUDP((DataMessage) message, packet.getAddress(), packet.getPort());
                    } else if (message instanceof AckMessage) {
                        tratarAckUDP((AckMessage) message, packet.getAddress(), packet.getPort());
                    }
                } catch (IllegalArgumentException ignored) {
                    // Datagramas inválidos são descartados sem alterar o estado.
                }
            } catch (IOException e) {
                if (running) System.err.println("Erro a receber datagrama UDP: " + e.getMessage());
            }
        }
    }

    private void tratarDataUDP(DataMessage message, InetAddress senderIP, int senderPort) {
        if (message.getEventCastType().getCodigo()
                != protocolos.udp.EventCastType.PUBLISH.getCodigo()) {
            return;
        }

        String publisherKey = senderIP.getHostAddress() + ":" + senderPort;
        String dedupKey = publisherKey + ":" + message.getMsgId() + ":" + message.getHopSeq();
        boolean novo = receivedPublisherMessages.add(dedupKey);

        if (novo) publish(message);

        /* QoS 1 e 2 recebem confirmação no salto entre publisher e broker. */
        enviarUDP(new AckMessage(message.getHopSeq() + 1 & 0xFFFF, 0),
                senderIP, senderPort);
    }

    private void tratarAckUDP(AckMessage ack, InetAddress senderIP, int senderPort) {
        // Os ACK dos subscritores confirmam o hopSeq do broker. O estado necessário
        // para retransmissão pode ser consultado pelas tabelas por id, mas o ACK é
        // processado silenciosamente quando não há transporte fiável adicional.
    }

    private void enviarUDP(EventCastMessage message, InetAddress ip, int port) {
        if (!running || udpSocket == null) return;
        try {
            byte[] data = message.serialize();
            DatagramPacket packet = new DatagramPacket(data, data.length, ip, port);
            udpSocket.send(packet);
        } catch (IOException e) {
            System.err.println("Erro a enviar datagrama UDP: " + e.getMessage());
        }
    }

    // Registo público de um publisher.
    public void registerPublisher(String id, InetAddress ip, int port) {
        validarId(id);
        validarPorta(port, "porta UDP do publisher");
        publishersIP.put(id, ip);
        publishersPorts.put(id, port);
        publisherRegistrations.putIfAbsent(id, new ConcurrentHashMap<>());
    }

    // Registo público de um subscriber.
    public void registerSubscriber(String id, InetAddress ip, int port) {
        validarId(id);
        validarPorta(port, "porta UDP do subscriber");
        subscribersIP.put(id, ip);
        subscribersPorts.put(id, port);
        subscriberSubscriptions.putIfAbsent(id, new ConcurrentHashMap<>());
        subscriberSequences.putIfAbsent(id, new AtomicInteger(0));
    }

    // Registo público de um tópico de um publisher.
    public void registerPublisherTopic(String publisherId, String topic, int qos) {
        validarId(publisherId);
        validarTopicoOuFiltro(topic);
        if (qos != 1 && qos != 2) throw new IllegalArgumentException("qos inválido: " + qos);
        publisherRegistrations.computeIfAbsent(publisherId, k -> new ConcurrentHashMap<>())
                .put(topic, qos);
    }

    // Subscrição pública de um tópico/filtro de um subscriber.
    public void subscribe(String subscriberId, String filter, int qos,
                          InetAddress ip, int udpPort) {
        registerSubscriber(subscriberId, ip, udpPort);
        subscribe(subscriberId, filter, qos);
    }

    // Subscrição pública de um tópico/filtro de um subscriber.
    public void subscribe(String subscriberId, String filter, int qos) {
        validarId(subscriberId);
        validarTopicoOuFiltro(filter);
        if (qos != 1 && qos != 2) throw new IllegalArgumentException("qos inválido: " + qos);
        subscriberSubscriptions.computeIfAbsent(subscriberId, k -> new ConcurrentHashMap<>())
                .put(filter, qos);

        // Entrega aos subscritores novos os tópicos retidos que já correspondem.
        for (DataMessage retained : publishedTopics.values()) {
            if (corresponde(retained.getTopico(), filter)) {
                entregar(retained, subscriberId);
            }
        }
    }

    // Remoção pública de uma subscrição de um tópico/filtro de um subscriber.
    public void unsubscribe(String subscriberId, String filter) {
        validarId(subscriberId);
        Map<String, Integer> subscriptions = subscriberSubscriptions.get(subscriberId);
        if (subscriptions != null) subscriptions.remove(filter);
    }

    // Publica uma mensagem já construída e entrega-a aos subscritores correspondentes.
    public void publish(DataMessage message) {
        if (message == null) throw new IllegalArgumentException("mensagem não pode ser nula");
        if (message.getEventCastType().getCodigo()
                != protocolos.udp.EventCastType.PUBLISH.getCodigo()) {
            throw new IllegalArgumentException("a mensagem publicada deve ser PUBLISH");
        }

        publishedTopics.put(message.getTopico(), message);

        for (Map.Entry<String, Map<String, Integer>> entry : subscriberSubscriptions.entrySet()) {
            String subscriberId = entry.getKey();
            for (Map.Entry<String, Integer> subscription : entry.getValue().entrySet()) {
                if (corresponde(message.getTopico(), subscription.getKey())) {
                    entregar(message, subscriberId);
                    break;
                }
            }
        }
    }

    // Entrega uma mensagem a um subscritor específico, considerando o QoS efetivo.
    private void entregar(DataMessage message, String subscriberId) {
        InetAddress ip = subscribersIP.get(subscriberId);
        Integer port = subscribersPorts.get(subscriberId);
        if (ip == null || port == null) return;

        int hopSeq = subscriberSequences.computeIfAbsent(subscriberId, k -> new AtomicInteger(0))
                .getAndUpdate(v -> (v + 1) & 0xFFFF);

        int qosSubscriber = 1;
        Map<String, Integer> subscriptions = subscriberSubscriptions.get(subscriberId);
        if (subscriptions != null) {
            for (Map.Entry<String, Integer> s : subscriptions.entrySet()) {
                if (corresponde(message.getTopico(), s.getKey())) {
                    qosSubscriber = s.getValue();
                    break;
                }
            }
        }
        int qosEfetivo = Math.min(message.getQos(), qosSubscriber);
        DataMessage deliver = message.paraEntrega(hopSeq, qosEfetivo);
        enviarUDP(deliver, ip, port);
    }
    
    // Getters públicos para os campos do broker

    public String getBrokerId() { 
        return brokerId; 
    }

    public int getBrokerUDPPort() { 
        return brokerUDPPort; 
    }

    public int getBrokerTCPPort() { 
        return brokerTCPPort; 
    }

    public InetAddress getBrokerIP() { 
        return brokerIP; 
    }

    public boolean isRunning() { 
        return running; 
    }

    public Map<String, DataMessage> getPublishedTopics() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(publishedTopics));
    }

    public DataMessage getPublishedTopic(String topic) { 
        return publishedTopics.get(topic); 
    }

    public Map<String, InetAddress> getSubscribersIP() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(subscribersIP));
    }

    public Map<String, Integer> getSubscribersPorts() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(subscribersPorts));
    }

    public Map<String, InetAddress> getPublishersIP() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(publishersIP));
    }

    public Map<String, Integer> getPublishersPorts() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(publishersPorts));
    }

    public Map<String, Integer> getSubscriptions(String subscriberId) {
        Map<String, Integer> subscriptions = subscriberSubscriptions.get(subscriberId);
        return subscriptions == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(subscriptions));
    }


    //Métodos auxiliares de validação
    private static void validarId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id não pode ser vazio");
        if (id.getBytes(StandardCharsets.UTF_8).length > 255)
            throw new IllegalArgumentException("id excede 255 bytes");
    }

    private static void validarPorta(int porta, String nome) {
        if (porta < 1 || porta > 65535) {
            throw new IllegalArgumentException(nome + " fora do intervalo 1..65535");
        }
    }

    public static void validarTopicoOuFiltro(String value) {
        if (value == null) throw new IllegalArgumentException("tópico/filtro não pode ser nulo");
        int len = value.getBytes(StandardCharsets.UTF_8).length;
        if (len < 1 || len > 255)
            throw new IllegalArgumentException("tópico/filtro fora de 1..255 bytes");
    }

    public boolean corresponde(String topico, String filtro) {
        if (topico == null || filtro == null) return false;
        if (filtro.equals("*") || filtro.equals("#") || filtro.equals(topico)) return true;

        if (filtro.endsWith("/*") || filtro.endsWith("/#")) {
            String prefix = filtro.substring(0, filtro.length() - 1);
            return topico.startsWith(prefix);
        }
        if (filtro.endsWith("*")) return topico.startsWith(filtro.substring(0, filtro.length() - 1));
        if (filtro.startsWith("*")) return topico.endsWith(filtro.substring(1));

        String[] topicoPartes = topico.split("/", -1);
        String[] filtroPartes = filtro.split("/", -1);
        if (filtroPartes.length > topicoPartes.length) return false;
        for (int i = 0; i < filtroPartes.length; i++) {
            if (filtroPartes[i].equals("#")) return i == filtroPartes.length - 1;
            if (filtroPartes[i].equals("*") || filtroPartes[i].equals("+")) continue;
            if (!filtroPartes[i].equals(topicoPartes[i])) return false;
        }
        return filtroPartes.length == topicoPartes.length;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java Broker <brokerIP>");
            System.exit(1);
        }
        try {
            InetAddress brokerIP = InetAddress.getByName(args[0]);
            Broker broker = new Broker(brokerIP, "broker1", 5000, 6000); // Ver isto com configs
            broker.run();
        }
        catch (Exception e) {
            System.err.println("Erro ao iniciar o broker: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
