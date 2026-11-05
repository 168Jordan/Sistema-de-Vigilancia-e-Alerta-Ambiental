package protocolos.tcp;

public enum Roles {
    PUBLISHER((byte)1),
    SUBSCRIBER((byte)2),
    BROKER((byte)3);

    private final byte codigo;

    Roles(byte codigo) {
        this.codigo = codigo;
    }

    public byte getCodigo() {
        return codigo;
    }

    public static Roles fromCodigo(byte codigo) {
        for (Roles type : Roles.values()) {
            if (type.getCodigo() == codigo) {
                return type;
            }
        }
        return null; // Retorna null se o código não corresponder a nenhum tipo conhecido
    }
}
