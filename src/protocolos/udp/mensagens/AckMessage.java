package protocolos.udp.mensagens;

import protocolos.udp.EventCastMessage;
import protocolos.udp.EventCastType;

/**
 * ACK (8 bytes, tamanho fixo):
 *
 *  0  type      1 B   ACK=3
 *  1  flags     1 B   reservado (0)
 *  2  ackBase   2 B   próximo hopSeq esperado: tudo o que é anterior foi recebido
 *  4  sack      4 B   bit i a 1 => hopSeq ackBase+1+i também foi recebido
 *
 * Stop-and-wait e go-back-N usam só o ackBase; selective repeat usa também o sack.
 */
public final class AckMessage extends EventCastMessage {

    public static final int TAMANHO = 8;
    public static final int JANELA_SACK = 32;

    private final int ackBase;
    private final int sack;

    public AckMessage(int ackBase, int sack) {
        super(0);
        if (ackBase < 0 || ackBase > 0xFFFF) {
            throw new IllegalArgumentException("ackBase fora de 0..65535: " + ackBase);
        }
        this.ackBase = ackBase;
        this.sack = sack;
    }

    @Override
    public EventCastType getEventCastType() {
        return EventCastType.ACK;
    }

    @Override
    public byte[] serialize() {
        return new byte[] {
            (byte) EventCastType.ACK.getCodigo(),
            (byte) flags,
            (byte) (ackBase >> 8), (byte) ackBase,
            (byte) (sack >> 24), (byte) (sack >> 16), (byte) (sack >> 8), (byte) sack
        };
    }

    public static AckMessage parse(byte[] buf, int len) {
        if (len != TAMANHO) {
            throw new IllegalArgumentException("ACK com tamanho inválido: " + len);
        }
        int ackBase = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        int sack = ((buf[4] & 0xFF) << 24) | ((buf[5] & 0xFF) << 16)
                 | ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        return new AckMessage(ackBase, sack);
    }

    public int getAckBase() { return ackBase; }
    public int getSack()    { return sack; }

    /** O hopSeq ackBase+1+i foi recebido (i de 0 a 31)? */
    public boolean isSack(int i) {
        return i >= 0 && i < JANELA_SACK && ((sack >>> i) & 1) != 0;
    }

    @Override
    public String toString() {
        return "ACK{base=" + ackBase + ", sack=0x" + Integer.toHexString(sack) + "}";
    }
}
