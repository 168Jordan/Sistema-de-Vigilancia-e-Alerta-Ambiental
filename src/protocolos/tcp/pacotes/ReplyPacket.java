package protocolos.tcp.pacotes;

import protocolos.tcp.SubControlPacket;
import protocolos.tcp.SubControlType;
import protocolos.tcp.ReplyStatus;

public final class ReplyPacket extends SubControlPacket {
    
    private final SubControlType replyTo;
    private final ReplyStatus status;
    
    public ReplyPacket(SubControlType replyTo, ReplyStatus status) {
        if (!isRequest(replyTo))
            throw new IllegalArgumentException("não é um pedido: " + replyTo);
        this.replyTo = replyTo;
        this.status = status;
    }
    
    private static boolean isRequest(SubControlType t) {
        return t == SubControlType.HELLO || t == SubControlType.REGISTER
            || t == SubControlType.SUBSCRIBE || t == SubControlType.UNSUBSCRIBE;
    }
    
    @Override
    public SubControlType getSubControlType() {
        return SubControlType.REPLY;
    }
    
       
    @Override
    protected byte[] corpoToBytes() {
        return new byte[] { (byte) replyTo.getCodigo(), (byte) status.getCodigo() };
    }
    
    public static ReplyPacket parse(byte[] body) throws IllegalArgumentException {
        if (body.length != 2)
            throw new IllegalArgumentException("REPLY com tamanho inválido: " + body.length);
        SubControlType replyTo = SubControlType.fromCodigo(body[0] & 0xFF);
        ReplyStatus status = ReplyStatus.fromCodigo(body[1] & 0xFF);
        if (replyTo == null || !isRequest(replyTo))
            throw new IllegalArgumentException("REPLY a um tipo inválido: " + (body[0] & 0xFF));
        if (status == null)
            throw new IllegalArgumentException("estado desconhecido: " + (body[1] & 0xFF));
        return new ReplyPacket(replyTo, status);
    }
    
    public SubControlType getReplyTo() { 
        return replyTo; 
    }

    public ReplyStatus getStatus() { 
        return status; 
    }

    public boolean isOk() { 
        return status == ReplyStatus.OK; 
    }
}

