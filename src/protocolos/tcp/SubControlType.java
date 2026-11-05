package protocolos.tcp;

public enum SubControlType {
    HELLO((byte)1),
    HELLO_OK((byte)2),
    REGISTER((byte)3),
    SUBSCRIBE((byte)4),
    UNSUBSCRIBE((byte)5),
    BYE((byte)6),
    REPLY((byte)7);

    private final byte codigo;

    SubControlType(byte codigo) {
        this.codigo = codigo;
    }

    public byte getCodigo() {
        return codigo;
    }

    public static SubControlType fromCodigo(byte codigo) {
        for (SubControlType type : SubControlType.values()) {
            if (type.getCodigo() == codigo) {
                return type;
            }
        }
        return null; // Retorna null se o código não corresponder a nenhum tipo conhecido
    }
}