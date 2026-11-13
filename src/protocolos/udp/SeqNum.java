package protocolos.udp;

/**
 * Aritmética de números de série de 16 bits (módulo 65536).
 * Nunca se comparam números de sequência com < ou >: depois do 65535 vem o 0.
 */
public final class SeqNum {

    private static final int MASCARA = 0xFFFF;
    private static final int METADE = 0x8000;

    private SeqNum() {}

    public static int proximo(int s) {
        return (s + 1) & MASCARA;
    }

    /** Quantos passos de b até a, andando para a frente. */
    public static int distancia(int a, int b) {
        return (a - b) & MASCARA;
    }

    /** a vem depois de b? Só é fiável se estiverem a menos de metade do espaço. */
    public static boolean depoisDe(int a, int b) {
        int d = distancia(a, b);
        return d != 0 && d < METADE;
    }

    /** s está em [base, base + tamanho)? */
    public static boolean naJanela(int s, int base, int tamanho) {
        return distancia(s, base) < tamanho;
    }
}
