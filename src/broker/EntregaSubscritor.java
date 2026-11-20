package broker;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import protocolos.udp.EmissorJanela;
import protocolos.udp.EventCastType;
import protocolos.udp.mensagens.AckMessage;
import protocolos.udp.mensagens.DataMessage;

/**
 * Estado de entrega do broker para um subscritor (salto broker -> subscritor).
 *
 * Cada subscritor tem o seu EmissorJanela (hopSeq, janela e temporizadores próprios)
 * e a sua fila: quando a janela enche, as mensagens novas esperam aqui, sem
 * atrasar a entrega aos restantes subscritores.
 *
 * Chamado por vários fios (receção UDP, sessões TCP, temporizador): tudo sincronizado.
 */
final class EntregaSubscritor {

    private static final class Item {
        final long msgId;
        final String topico;
        final byte[] conteudo;
        final int qos;

        Item(long msgId, String topico, byte[] conteudo, int qos) {
            this.msgId = msgId;
            this.topico = topico;
            this.conteudo = conteudo;
            this.qos = qos;
        }
    }

    private final EmissorJanela emissor;
    private final Deque<Item> fila = new ArrayDeque<>();
    /** Início do prazo de evict_after: o último ACK, ou o instante em que passou a haver algo por entregar. */
    private long referencia;

    EntregaSubscritor(EmissorJanela.Transmissor transmissor, long rtoMs) {
        this.emissor = new EmissorJanela(transmissor, rtoMs);
    }

    /** Põe a publicação na fila deste subscritor e envia o que a janela deixar. */
    synchronized void entregar(DataMessage original, int qosEfetivo, long agora) {
        if (pendentes() == 0) referencia = agora;     // estava em repouso: o prazo conta a partir daqui
        fila.add(new Item(original.getMsgId(), original.getTopico(),
                original.getConteudo(), qosEfetivo));
        escoar(agora);
    }

    synchronized void aoReceberAck(AckMessage ack, long agora) {
        referencia = agora;                            // o subscritor está vivo
        emissor.aoReceberAck(ack);
        escoar(agora);
    }

    /** Chamado periodicamente: retransmite o que expirou e envia o que couber. */
    synchronized void tick(long agora) {
        try {
            emissor.retransmitirExpirados(agora);
        } catch (IOException e) {
            // o transmissor trata os seus próprios erros de envio
        }
        escoar(agora);
    }

    /** Há algo por entregar e passou evictMs sem nenhum ACK? */
    synchronized boolean expirou(long agora, long evictMs) {
        return pendentes() > 0 && agora - referencia >= evictMs;
    }

    synchronized int pendentes() {
        return fila.size() + emissor.emVoo();
    }

    synchronized long getRetransmissoes() {
        return emissor.getRetransmissoes();
    }

    private void escoar(long agora) {
        while (!fila.isEmpty() && emissor.temEspaco()) {
            Item i = fila.poll();
            try {
                emissor.enviar(EventCastType.DELIVER, i.qos, i.msgId, i.topico, i.conteudo, agora);
            } catch (IOException e) {
                // o transmissor trata os seus próprios erros de envio
            }
        }
    }
}