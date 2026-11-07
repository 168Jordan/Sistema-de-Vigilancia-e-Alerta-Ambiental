package protocolos.tcp;

public enum Roles {
    PUBLISHER(1),
    SUBSCRIBER(2),
    BROKER(3);

    private final int codigo;

    Roles(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }


    public static Roles fromCodigo(int codigo) {
        for (Roles type : Roles.values()) {
            if (type.getCodigo() == codigo) {
                return type;
            }
        }
        return null; // Retorna null se o código não corresponder a nenhum tipo conhecido
    }
}
