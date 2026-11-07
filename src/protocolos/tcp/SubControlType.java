package protocolos.tcp;

public enum SubControlType {
    HELLO(1),
    HELLO_OK(2),
    REGISTER(3),
    SUBSCRIBE(4),
    UNSUBSCRIBE(5),
    BYE(6),
    REPLY(7);

    private final int codigo;

    SubControlType(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static SubControlType fromCodigo(int codigo) {
        for (SubControlType type : SubControlType.values()) {
            if (type.getCodigo() == codigo) {
                return type;
            }
        }
        return null; // Retorna null se o código não corresponder a nenhum tipo conhecido
    }
}