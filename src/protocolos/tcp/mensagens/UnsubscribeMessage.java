package protocolos.tcp.mensagens;

import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

import protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlType;

public class UnsubscribeMessage extends SubControlMessage {

    private final String filtro;

    public UnsubscribeMessage(String filtro) {
        int len = filtro.getBytes().length;
        if (len < 1 || len > 255)
            throw new IllegalArgumentException("filtro fora de 1..255 bytes");
        this.filtro = filtro;
    }

    public String getFiltro() {
        return filtro;
    }

    @Override 
    public SubControlType getSubControlType() {
        return SubControlType.UNSUBSCRIBE;
    }

    @Override 
     protected byte[] corpoToBytes() {
        byte[] f = filtro.getBytes(StandardCharsets.UTF_8);
        byte[] corpo = new byte[1 + f.length];
        corpo[0] = (byte) f.length;
        System.arraycopy(f, 0, corpo, 1, f.length);
        return corpo;
     }

     public static UnsubscribeMessage parse(byte[] corpo) throws IllegalArgumentException{
        if (corpo.length < 1)
            throw new IllegalArgumentException("UNSUBSCRIBE truncado");
        int len = corpo[0] & 0xFF;
        if (len == 0 || corpo.length != 1 + len)
            throw new IllegalArgumentException("UNSUBSCRIBE com tamanho inconsistente");
        try {
            return new UnsubscribeMessage(new String(corpo, 1, len, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
