package protocolos.udp;

/**
 * Byte de flags do EventCast:
 *   bits 0-1 : qos (1 ou 2)
 *   bit 2    : retransmissão
 *   bits 3-7 : reservados (enviar a 0, ignorar na receção)
 */
public final class EventCastFlags {

    private static final int MASCARA_QOS = 0x03;
    private static final int BIT_RETRANSMISSAO = 0x04;

    private EventCastFlags() {}

    public static int criar(int qos, boolean retransmissao) {
        if (qos != 1 && qos != 2) {
            throw new IllegalArgumentException("qos inválido: " + qos);
        }
        return qos | (retransmissao ? BIT_RETRANSMISSAO : 0);
    }

    public static int qos(int flags) {
        return flags & MASCARA_QOS;
    }

    public static boolean isRetransmissao(int flags) {
        return (flags & BIT_RETRANSMISSAO) != 0;
    }
}