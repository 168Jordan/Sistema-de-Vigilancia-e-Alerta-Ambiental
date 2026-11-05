package protocolos.tcp.pacotes;
import protocolos.tcp.SubControlPacket;
import protocolos.tcp.SubControlType;

public class HelloOkPacket extends SubControlPacket {

    private static final int TAMANHO_CORPO = 2;
    private final int udpPort;

    public HelloOkPacket(int udpPort) {
        if (udpPort < 1 || udpPort > 65535) {
            throw new IllegalArgumentException("O valor da porta UDP deve estar entre 1 e 65535.");
        }
        this.udpPort = udpPort;
    }

    @Override 
    public SubControlType getSubControlType() {
        return SubControlType.HELLO_OK;
    }

    @Override 
    public byte[] corpoToBytes() {
        return new byte[] {
            (byte) ((udpPort >> 8) & 0xFF),
            (byte) (udpPort & 0xFF)
        };
    }

    public static HelloOkPacket parse(byte[] body) {
        if (body.length != TAMANHO_CORPO) {
            throw new IllegalArgumentException("O corpo do pacote HelloOk deve ter exatamente " + TAMANHO_CORPO + " bytes.");
        }
        int udpPort = ((body[0] & 0xFF) << 8 | (body[1] & 0xFF));
        if (udpPort < 1 || udpPort > 65535) {
            throw new IllegalArgumentException("O valor da porta UDP deve estar entre 1 e 65535.");
        }
        return new HelloOkPacket(udpPort);
    }
}
