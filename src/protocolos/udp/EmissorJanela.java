package protocolos.udp;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import protocolos.udp.mensagens.AckMessage;
import protocolos.udp.mensagens.DataMessage;

/**
 * Emissor de um salto com janela deslizante e selective repeat.
 *
 * - Cada transmissão leva o hopSeq seguinte (módulo 65536).
 * - Um temporizador por mensagem: só se retransmite o que expirou.
 * - O ACK confirma o que é anterior a ackBase e os hopSeq marcados no sack.
 * - A janela é de JANELA mensagens (a largura do sack). Com JANELA = 1 seria stop-and-wait.
 *
 * Não tem relógio nem socket próprios: quem o usa passa o instante atual
 * e um Transmissor. Um só fio de execução.
 */
public final class EmissorJanela {

    public static final int JANELA = AckMessage.JANELA_SACK;

    @FunctionalInterface
    public interface Transmissor {
        void enviar(DataMessage mensagem) throws IOException;
    }

    private static final class Pendente {
        final DataMessage mensagem;
        long ultimoEnvio;
        boolean confirmada;

        Pendente(DataMessage mensagem, long ultimoEnvio) {
            this.mensagem = mensagem;
            this.ultimoEnvio = ultimoEnvio;
        }
    }

    private final Transmissor transmissor;
    private final long rtoMs;
    private final Map<Integer, Pendente> pendentes = new LinkedHashMap<>();
    private int base = 0;       // hopSeq mais antigo ainda por confirmar
    private int proximo = 0;    // hopSeq da próxima mensagem nova
    private long retransmissoes = 0;

    public EmissorJanela(Transmissor transmissor, long rtoMs) {
        if (rtoMs < 1) throw new IllegalArgumentException("rto inválido: " + rtoMs);
        this.transmissor = transmissor;
        this.rtoMs = rtoMs;
    }

    public boolean temEspaco() {
        return SeqNum.distancia(proximo, base) < JANELA;
    }

    public boolean tudoConfirmado() {
        return base == proximo;
    }

    public int emVoo() {
        return SeqNum.distancia(proximo, base);
    }

    public long getRetransmissoes() {
        return retransmissoes;
    }

    /** Envia uma mensagem nova. Exige temEspaco(). */
    public void enviar(EventCastType tipo, int qos, long msgId, String topico,
                       byte[] conteudo, long agora) throws IOException {
        if (!temEspaco()) throw new IllegalStateException("janela cheia");
        DataMessage m = new DataMessage(tipo, EventCastFlags.criar(qos, false),
                                        proximo, msgId, topico, conteudo);
        pendentes.put(proximo, new Pendente(m, agora));
        proximo = SeqNum.proximo(proximo);
        transmissor.enviar(m);
    }

    /** Retransmite, marcadas como tal, as mensagens cujo temporizador expirou. */
    public void retransmitirExpirados(long agora) throws IOException {
        for (Pendente p : pendentes.values()) {
            if (!p.confirmada && agora - p.ultimoEnvio >= rtoMs) {
                p.ultimoEnvio = agora;
                retransmissoes++;
                transmissor.enviar(p.mensagem.comoRetransmissao());
            }
        }
    }

    /** Instante da próxima expiração, ou Long.MAX_VALUE se não há nada por confirmar. */
    public long proximoPrazo() {
        long prazo = Long.MAX_VALUE;
        for (Pendente p : pendentes.values()) {
            if (!p.confirmada) prazo = Math.min(prazo, p.ultimoEnvio + rtoMs);
        }
        return prazo;
    }

    public void aoReceberAck(AckMessage ack) {
        int ackBase = ack.getAckBase();
        int naJanela = SeqNum.distancia(proximo, base);

        // cumulativo: tudo o que é anterior a ackBase
        for (int h = base; h != proximo && SeqNum.depoisDe(ackBase, h); h = SeqNum.proximo(h)) {
            pendentes.get(h).confirmada = true;
        }
        // seletivo: ackBase+1+i
        for (int i = 0; i < AckMessage.JANELA_SACK; i++) {
            if (!ack.isSack(i)) continue;
            int h = (ackBase + 1 + i) & 0xFFFF;
            if (SeqNum.distancia(h, base) < naJanela) {
                pendentes.get(h).confirmada = true;
            }
        }
        // desliza a janela sobre o prefixo confirmado
        while (base != proximo && pendentes.get(base).confirmada) {
            pendentes.remove(base);
            base = SeqNum.proximo(base);
        }
    }
}