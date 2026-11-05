package protocolos.tcp.pacotes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import  protocolos.tcp.SubControlPacket;
import protocolos.tcp.SubControlType;


public class RegisterPacket extends SubControlPacket {

    public final class Fluxo {
    
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

    
}
