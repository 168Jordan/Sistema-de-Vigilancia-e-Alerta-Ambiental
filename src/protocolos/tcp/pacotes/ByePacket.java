package protocolos.tcp.pacotes;

import protocolos.tcp.SubControlPacket;
import protocolos.tcp.SubControlType;

public class ByePacket extends SubControlPacket {

    
    @Override
    public SubControlType getSubControlType() {
        return SubControlType.BYE;
    }
    
    @Override
    protected byte[] corpoToBytes() {
        return new byte[0];
    }
    
    public static ByePacket parse(byte[] body) throws IllegalArgumentException {
        if (body.length != 0)
            throw new IllegalArgumentException("BYE com corpo de " + body.length + " bytes");
        return new ByePacket();
    }
    
}

