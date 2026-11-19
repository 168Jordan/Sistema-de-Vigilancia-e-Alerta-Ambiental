package protocolos.udp;

import protocolos.udp.mensagens.AckMessage;

/**
 * Estado de receção de um salto (um emissor -> este recetor), para selective repeat.
 *
 *   base : próximo hopSeq esperado; tudo o que é anterior foi recebido
 *   sack : bit i a 1 => hopSeq base+1+i também foi recebido
 *
 * Um só fio de execução por instância (o ciclo de receção UDP).
 */
public final class ReceptorJanela {

    private int base = 0;
    private int sack = 0;

    /** Regista um hopSeq. Devolve true só na primeira vez que chega e cabe na janela. */
    public boolean receber(int hopSeq) {
        int d = SeqNum.distancia(hopSeq, base);
        if (d == 0) {
            base = SeqNum.proximo(base);
            while ((sack & 1) != 0) {          // o que estava à espera passa a contíguo
                sack >>>= 1;
                base = SeqNum.proximo(base);
            }
            sack >>>= 1;
            return true;
        }
        if (d <= AckMessage.JANELA_SACK) {     // à frente de base, dentro do SACK
            int bit = 1 << (d - 1);
            if ((sack & bit) != 0) return false;
            sack |= bit;
            return true;
        }
        return false;                          // duplicado antigo, ou além da janela
    }

    public AckMessage criarAck() {
        return new AckMessage(base, sack);
    }

    public int getBase() { return base; }
    public int getSack() { return sack; }
}