package protocolos.tcp;

public enum ReplyStatus {
     OK(0), 
     BAD_FILTER(1), 
     NO_SESSION(2), 
     BAD_REQUEST(3);

    private final int codigo;

    ReplyStatus(int codigo) { 
        this.codigo = codigo; 
    }
    
    public int getCodigo() { 
        return codigo; 
    }

    public static ReplyStatus fromCodigo(int codigo) {
        for (ReplyStatus s : values()) if (s.codigo == codigo) return s;
        return null;
    }
}

