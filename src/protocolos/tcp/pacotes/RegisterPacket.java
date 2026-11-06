package protocolos.tcp.pacotes;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import  protocolos.tcp.SubControlPacket;
import protocolos.tcp.SubControlType;


public class RegisterPacket extends SubControlPacket {

    public static final class Fluxo {
    
        private final int qos;
        private final String topico;

        public Fluxo (int qos,String topico){
            int len = topico.getBytes().length;
                if (qos != 1 && qos != 2)
                    throw new IllegalArgumentException("qos inválido: " + qos);
                if (len < 1 || len > 255)
                    throw new IllegalArgumentException("tópico fora de 1..255 bytes");
                this.qos = qos;
                this.topico = topico;
        }

        public int getQos() {
            return qos;
        }

        public String getTopico() {
            return topico;
        }
    }

    private final List<Fluxo> fluxos;


    public RegisterPacket(List<Fluxo> fluxos) {
        if (fluxos == null) {
            throw new IllegalArgumentException("lista de fluxos não pode ser nula");
        }
        if (fluxos.size() > 255) {
            throw new IllegalArgumentException("demasiados fluxos: " + fluxos.size());
        }
        this.fluxos = Collections.unmodifiableList(new ArrayList<>(fluxos));
    
    }

    @Override 
    public SubControlType getSubControlType() {
        return SubControlType.REGISTER;
    }

    @Override
    protected byte[] corpoToBytes() {
        byte[][] topicos = new byte[fluxos.size()][];
        int size = 1;
        for (int i = 0; i < topicos.length; i++) {
            topicos[i] = fluxos.get(i).getTopico().getBytes(StandardCharsets.UTF_8);
            size += 2 + topicos[i].length;
        }
        byte[] corpo = new byte[size];
        int pos = 0;
        corpo[pos++] = (byte) fluxos.size();
        for (int i = 0; i < topicos.length; i++) {
            corpo[pos++] = (byte) fluxos.get(i).getQos();
            corpo[pos++] = (byte) topicos[i].length;
            System.arraycopy(topicos[i], 0, corpo, pos, topicos[i].length);
            pos += topicos[i].length;
        }
        return corpo;
    }

    public static RegisterPacket parse(byte[] body) throws ProtocolException {
        if (body.length < 1)
            throw new ProtocolException("REGISTER truncado");
        int n = body[0] & 0xFF;
        int pos = 1;
        List<Fluxo> fluxos = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                if (body.length - pos < 2)
                    throw new ProtocolException("REGISTER truncado no fluxo " + i);
                int qos = body[pos++] & 0xFF;
                int len = body[pos++] & 0xFF;
                if (qos != 1 && qos != 2)
                    throw new ProtocolException("qos inválido: " + qos);
                if (len == 0 || body.length - pos < len)
                    throw new ProtocolException("tópico truncado no fluxo " + i);
                fluxos.add(new Fluxo(qos, new String(body, pos, len, StandardCharsets.UTF_8)));
                pos += len;
            }
            if (pos != body.length)
                throw new ProtocolException("REGISTER com bytes a mais");
            return new RegisterPacket(fluxos);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage());
        }
    }

    public List<Fluxo> getFluxos() {
        return fluxos;
    }


}
