package protocolos.tcp.mensagens;

import protocolos.tcp.SubControlMessage;
import protocolos.tcp.SubControlType;

public class ByeMessage extends SubControlMessage {

    
    @Override
    public SubControlType getSubControlType() {
        return SubControlType.BYE;
    }
    
    @Override
    protected byte[] corpoToBytes() {
        return new byte[0];
    }
    
    public static ByeMessage parse(byte[] body) throws IllegalArgumentException {
        if (body.length != 0)
            throw new IllegalArgumentException("BYE com corpo de " + body.length + " bytes");
        return new ByeMessage();
    }
    
}

