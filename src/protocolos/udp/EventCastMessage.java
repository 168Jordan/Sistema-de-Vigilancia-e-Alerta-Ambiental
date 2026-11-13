package protocolos.udp;

import protocolos.udp.mensagens.AckMessage;
import protocolos.udp.mensagens.DataMessage;

public abstract class EventCastMessage {

    public static final int MAX_TOPICO = 255;
    public static final int MAX_CONTEUDO = 1024;
    public static final int CABECALHO_DADOS = 11;
    public static final int MAX_DATAGRAMA = CABECALHO_DADOS + MAX_TOPICO + MAX_CONTEUDO; // 1290 < MTU
    /** Buffer de receção maior que o máximo, para detetar datagramas grandes demais. */
    public static final int TAMANHO_BUFFER = 1500;

    protected final int flags;

    protected EventCastMessage(int flags) {
        this.flags = flags & 0xFF;
    }

    public abstract EventCastType getEventCastType();

    public abstract byte[] serialize();

    public int getFlags() {
        return flags;
    }

    /**
     * Interpreta um datagrama recebido. Usar len = packet.getLength(), nunca buf.length.
     * Qualquer inconsistência lança IllegalArgumentException: quem recebe descarta
     * o datagrama em silêncio (e não envia ACK).
     */
    public static EventCastMessage deserialize(byte[] buf, int len) {
        if (len < 2 || len > MAX_DATAGRAMA) {
            throw new IllegalArgumentException("tamanho de datagrama inválido: " + len);
        }
        EventCastType tipo = EventCastType.fromCodigo(buf[0] & 0xFF);
        if (tipo == null) {
            throw new IllegalArgumentException("tipo desconhecido: " + (buf[0] & 0xFF));
        }
        if (len < tipo.getTamanhoCabecalho()) {
            throw new IllegalArgumentException("cabeçalho truncado: " + len + " bytes");
        }
        int flags = buf[1] & 0xFF;
        switch (tipo) {
            case PUBLISH:
            case DELIVER:
                return DataMessage.parse(tipo, flags, buf, len);
            case ACK:
                return AckMessage.parse(buf, len);
            default:
                throw new IllegalArgumentException("sem parser para " + tipo);
        }
    }
}
