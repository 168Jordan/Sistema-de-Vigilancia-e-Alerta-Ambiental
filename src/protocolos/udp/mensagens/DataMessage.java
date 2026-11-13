package protocolos.udp.mensagens;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import protocolos.udp.EventCastFlags;
import protocolos.udp.EventCastMessage;
import protocolos.udp.EventCastType;

/**
 * Formato do cabeçalho de uma mensagem de dados (PUBLISH ou DELIVER):
 *  0     type          1 B   PUBLISH=1, DELIVER=2
 *  1     flags         1 B   qos (bits 0-1), retransmissão (bit 2)
 *  2     hopSeq        2 B   nº de sequência por salto (o ACK responde a este)
 *  4     msgId         4 B   identificador ponta a ponta, sem sinal
 *  8     topicLen      1 B
 *  9     conteudoLen   2 B
 * 11     topico        topicLen B   UTF-8
 * 11+tl  conteudo      conteudoLen B
 */
public final class DataMessage extends EventCastMessage {

    private final EventCastType tipo;
    private final int hopSeq;
    private final long msgId;
    private final String topico;
    private final byte[] topicoBytes;
    private final byte[] conteudo;

    public DataMessage(EventCastType tipo, int flags, int hopSeq, long msgId,
                       String topico, byte[] conteudo) {
        super(flags);
        if (tipo != EventCastType.PUBLISH && tipo != EventCastType.DELIVER) {
            throw new IllegalArgumentException("tipo não é de dados: " + tipo);
        }
        int qos = EventCastFlags.qos(flags);
        if (qos != 1 && qos != 2) {
            throw new IllegalArgumentException("qos inválido: " + qos);
        }
        if (hopSeq < 0 || hopSeq > 0xFFFF) {
            throw new IllegalArgumentException("hopSeq fora de 0..65535: " + hopSeq);
        }
        if (msgId < 0 || msgId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("msgId fora de 0..2^32-1: " + msgId);
        }
        byte[] t = topico.getBytes(StandardCharsets.UTF_8);
        if (t.length < 1 || t.length > MAX_TOPICO) {
            throw new IllegalArgumentException("tópico fora de 1.." + MAX_TOPICO + " bytes");
        }
        if (conteudo.length > MAX_CONTEUDO) {
            throw new IllegalArgumentException("conteúdo excede " + MAX_CONTEUDO + " bytes");
        }
        this.tipo = tipo;
        this.hopSeq = hopSeq;
        this.msgId = msgId;
        this.topico = topico;
        this.topicoBytes = t;
        this.conteudo = conteudo.clone();
    }

    @Override
    public EventCastType getEventCastType() {
        return tipo;
    }

    @Override
    public byte[] serialize() {
        byte[] b = new byte[CABECALHO_DADOS + topicoBytes.length + conteudo.length];
        b[0] = (byte) tipo.getCodigo();
        b[1] = (byte) flags;
        b[2] = (byte) (hopSeq >> 8);
        b[3] = (byte) hopSeq;
        b[4] = (byte) (msgId >> 24);
        b[5] = (byte) (msgId >> 16);
        b[6] = (byte) (msgId >> 8);
        b[7] = (byte) msgId;
        b[8] = (byte) topicoBytes.length;
        b[9] = (byte) (conteudo.length >> 8);
        b[10] = (byte) conteudo.length;
        System.arraycopy(topicoBytes, 0, b, CABECALHO_DADOS, topicoBytes.length);
        System.arraycopy(conteudo, 0, b, CABECALHO_DADOS + topicoBytes.length, conteudo.length);
        return b;
    }

    public static DataMessage parse(EventCastType tipo, int flags, byte[] buf, int len) {
        int hopSeq = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        long msgId = ((long) (buf[4] & 0xFF) << 24) | ((long) (buf[5] & 0xFF) << 16)
                   | ((long) (buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        int tl = buf[8] & 0xFF;
        int cl = ((buf[9] & 0xFF) << 8) | (buf[10] & 0xFF);
        if (tl == 0) {
            throw new IllegalArgumentException("tópico vazio");
        }
        if (cl > MAX_CONTEUDO) {
            throw new IllegalArgumentException("conteúdo excede o máximo: " + cl);
        }
        if (CABECALHO_DADOS + tl + cl != len) {          // truncado ou bytes a mais
            throw new IllegalArgumentException("tamanho inconsistente com o cabeçalho");
        }
        String topico = new String(buf, CABECALHO_DADOS, tl, StandardCharsets.UTF_8);
        byte[] conteudo = Arrays.copyOfRange(buf, CABECALHO_DADOS + tl, len);
        return new DataMessage(tipo, flags, hopSeq, msgId, topico, conteudo);
    }

    /** O DELIVER que o broker envia a um subscritor, com o hopSeq desse subscritor. */
    public DataMessage paraEntrega(int hopSeqDoSubscritor, int qosEfetivo) {
        return new DataMessage(EventCastType.DELIVER, EventCastFlags.criar(qosEfetivo, false),
                               hopSeqDoSubscritor, msgId, topico, conteudo);
    }

    /** A mesma mensagem, marcada como retransmissão (hopSeq e msgId não mudam). */
    public DataMessage comoRetransmissao() {
        return new DataMessage(tipo, EventCastFlags.criar(getQos(), true),
                               hopSeq, msgId, topico, conteudo);
    }

    public int getQos() { 
        return EventCastFlags.qos(flags); 
    }

    public boolean isRetransmissao() { 
        return EventCastFlags.isRetransmissao(flags); 
    
    }

    public int getHopSeq() { 
        return hopSeq; 
    }

    public long getMsgId() { 
        return msgId; 
    }

    public String getMsgIdTexto() { 
        return Long.toString(msgId); 
    }

    public String getTopico() { 
        return topico; 
    }

    public byte[] getConteudo() { 
        return conteudo.clone(); 
    }
}