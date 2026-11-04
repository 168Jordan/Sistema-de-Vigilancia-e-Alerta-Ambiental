package protocolos.tcp;
import protocolos.tcp.pacotes.*;

public abstract class SubControlPacket {

    public static final int MAX_PACKET_SIZE = 1024; // Tamanho máximo do pacote em bytes
    public static final int HEADER_SIZE = 3; // Tamanho do cabeçalho em bytes (1 byte para o tipo de subcontrole + 2 bytes para o tamanho do payload)
    public abstract SubControlType getSubControlType(); // Método abstrato para obter o tipo do pacote
    protected abstract byte[]  corpoToBytes(); // Método abstrato para obter o corpo do pacote em bytes

    public final byte[] serialize() {
        byte[] corpo = corpoToBytes();
        if (corpo.length > MAX_PACKET_SIZE - HEADER_SIZE) {
            throw new IllegalArgumentException("O tamanho do corpo do pacote excede o tamanho máximo permitido.");
        }
        byte[] packet = new byte[HEADER_SIZE + corpo.length];
        packet[0] = getSubControlType().getCodigo(); // Tipo de subcontrole
        packet[1] = (byte) ((corpo.length >> 8) & 0xFF); 
        packet[2] = (byte) (corpo.length & 0xFF);
        System.arraycopy(corpo, 0, packet, HEADER_SIZE, corpo.length);
        return packet;
    }
    
    public static SubControlPacket deserialize (byte[] packet) {
        if (packet.length < HEADER_SIZE) {
            throw new IllegalArgumentException("O pacote é muito pequeno para conter um cabeçalho válido.");
        }
        byte tipoCodigo = packet[0];
        SubControlType tipo = SubControlType.fromCodigo(tipoCodigo);
        int tamanhoCorpo = ((packet[1] & 0xFF) << 8) | (packet[2] & 0xFF);
        if (packet.length != HEADER_SIZE + tamanhoCorpo) {
            throw new IllegalArgumentException("O tamanho do pacote não corresponde ao tamanho do corpo especificado no cabeçalho.");
        }
        byte[] corpo = new byte[tamanhoCorpo];
        System.arraycopy(packet, HEADER_SIZE, corpo, 0, tamanhoCorpo);
        
        switch (tipo) {
            case HELLO:
                return HelloPacket.parse(corpo);
            case HELLO_OK:
                return HelloOkPacket.deserialize(corpo);
            case REGISTER:
                return RegisterPacket.deserialize(corpo);
            case SUBSCRIBE:
                return SubscribePacket.deserialize(corpo);
            case UNSUBSCRIBE:
                return UnsubscribePacket.deserialize(corpo);
            case BYE:
                return ByePacket.deserialize(corpo);
            default:
                throw new IllegalArgumentException("Tipo de subcontrole desconhecido: " + tipo);
        }
    }
}
    

