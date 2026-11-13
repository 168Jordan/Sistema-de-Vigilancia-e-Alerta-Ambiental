package protocolos.tcp.mensagens;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import  protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlType;


public class RegisterMessage extends SubControlMessage {

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


    public RegisterMessage(List<Fluxo> fluxos) {
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

    public static RegisterMessage parse(byte[] body) throws IllegalArgumentException{
        if (body.length < 1)
            throw new IllegalArgumentException("REGISTER truncado");
        int n = body[0] & 0xFF;
        int pos = 1;
        List<Fluxo> fluxos = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                if (body.length - pos < 2)
                    throw new IllegalArgumentException("REGISTER truncado no fluxo " + i);
                int qos = body[pos++] & 0xFF;
                int len = body[pos++] & 0xFF;
                if (qos != 1 && qos != 2)
                    throw new IllegalArgumentException("qos inválido: " + qos);
                if (len == 0 || body.length - pos < len)
                    throw new IllegalArgumentException("tópico truncado no fluxo " + i);
                fluxos.add(new Fluxo(qos, new String(body, pos, len, StandardCharsets.UTF_8)));
                pos += len;
            }
            if (pos != body.length)
                throw new IllegalArgumentException("REGISTER com bytes a mais");
            return new RegisterMessage(fluxos);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    public List<Fluxo> getFluxos() {
        return fluxos;
    }


}
