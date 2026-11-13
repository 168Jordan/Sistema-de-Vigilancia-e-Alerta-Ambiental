package protocolos.udp;

public enum EventCastType {
    PUBLISH(1,11),
    DELIVER(2,11),
    ACK(3,8);

    private final int codigo;
    private final int tamnahoCabecalho;

    EventCastType(int codigo, int tamanhoCabecalho) {
        this.codigo = codigo;
        this.tamnahoCabecalho = tamanhoCabecalho;
    }
    
    public int getCodigo() {
        return codigo;
    }

    public int getTamanhoCabecalho() {
        return tamnahoCabecalho;
    }

    public static EventCastType fromCodigo(int codigo) {
        for (EventCastType t : values()) {
            if (t.codigo == codigo) {
                return t;
            }
        }
        return null;
    }
}