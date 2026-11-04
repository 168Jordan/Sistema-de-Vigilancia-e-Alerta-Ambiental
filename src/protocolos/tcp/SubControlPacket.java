package protocolos.tcp;

public abstract class SubControlPacket {
    public abstract SubControlType getSubControlType();
    protected abstract byte[] toByteArray();
  

    
}
