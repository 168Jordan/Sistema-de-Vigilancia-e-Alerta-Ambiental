package protocolos.tcp.mensagens;

import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

import protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlType;

public class SubscribeMessage extends SubControlMessage {

    private final int qos;
    private final String filtro;

    public SubscribeMessage(int qos, String filtro) {
        int len = filtro.getBytes().length;
        if (qos != 1 && qos != 2)
            throw new IllegalArgumentException("qos inválido: " + qos);
        if (len < 1 || len > 255)
            throw new IllegalArgumentException("filtro fora de 1..255 bytes");
        this.qos = qos;
        this.filtro = filtro;
    }

    public int getQos() {
        return qos;
    }

    public String getFiltro() {
        return filtro;
    }

    @Override 
    public SubControlType getSubControlType() {
        return SubControlType.SUBSCRIBE;
    }

    @Override 
    protected byte[] corpoToBytes() {
        byte[] filtroBytes = filtro.getBytes();
        byte[] corpo = new byte[1 + 1 + filtroBytes.length];
        corpo[0] = (byte) qos;
        corpo[1] = (byte) filtroBytes.length;
        System.arraycopy(filtroBytes, 0, corpo, 2, filtroBytes.length);
        return corpo;
    }

    public static SubscribeMessage parse(byte[] body) throws IllegalArgumentException {
        if (body.length < 2)
            throw new IllegalArgumentException("SUBSCRIBE truncado");
        int qos = body[0] & 0xFF;
        int len = body[1] & 0xFF;
        if (qos != 1 && qos != 2)
            throw new IllegalArgumentException("qos inválido: " + qos);
        if (len == 0 || body.length != 2 + len)
            throw new IllegalArgumentException("SUBSCRIBE com tamanho inconsistente");
        try {
            return new SubscribeMessage(qos, new String(body, 2, len, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
    
}
