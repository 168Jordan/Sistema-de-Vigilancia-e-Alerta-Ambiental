package protocolos.tcp;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import protocolos.tcp.mensagens.ReplyMessage;

/**
 * Stream TCP do SubControl: envia e recebe pacotes, delimita os frames
 * (type | length | corpo), trata EOF e erros de protocolo e aplica os
 * temporizadores de sessão.
 *
 * receber() devolve null quando a sessão terminou, seja por que razão for.
 * A razão está em getMotivoFecho().
 */
public final class SubControlStream {

    public enum CloseReason {
        PEER_CLOSED,     // EOF entre frames
        PEER_BYE,        // recebeu BYE
        PEER_ERROR,      // recebeu REPLY{BYE, erro}: o par encerrou a sessão
        TIMEOUT,         // temporizador expirou
        PROTOCOL_ERROR,  // frame inválido: avisou o par e fechou
        IO_ERROR,        // EOF a meio de um frame, reset, falha de escrita
        LOCAL            // fechar() chamado por quem usa a stream
    }

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subcontrol-timer");
            t.setDaemon(true);
            return t;
        });

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final long handshakeTimeoutMs;
    private final long replyTimeoutMs;

    private final Object writeLock = new Object();

    // Tudo o que está abaixo é protegido por stateLock.
    // Os métodos marcarFechada, armarTemporizador e cancelarTemporizador
    // só se chamam com o stateLock já obtido.
    private final Object stateLock = new Object();
    private ScheduledFuture<?> timer;
    private long generation;            // invalida temporizadores já cancelados
    private boolean handshakeStarted;
    private int pending;                // pedidos à espera de REPLY
    private boolean closed;
    private CloseReason closeReason;
    private String closeDetail;

    public SubControlStream(Socket socket, long handshakeTimeoutMs, long replyTimeoutMs)
            throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
        this.handshakeTimeoutMs = handshakeTimeoutMs;
        this.replyTimeoutMs = replyTimeoutMs;
        socket.setTcpNoDelay(true);     // frames pequenos: não esperar pelo Nagle
    }

    // ------------------------------------------------------------------ envio

    public void enviar(SubControlMessage message) throws IOException {
        if (estaFechada()) {
            throw new IOException("sessão fechada: " + getMotivoFecho());
        }
        byte[] frame = message.serialize();
        antesDeEnviar(message.getSubControlType());   // arma ANTES de escrever
        try {
            synchronized (writeLock) {
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            fechar(CloseReason.IO_ERROR, String.valueOf(e.getMessage()));
            throw e;
        }
    }

    private void antesDeEnviar(SubControlType type) {
        synchronized (stateLock) {
            if (closed) return;
            switch (type) {
                case HELLO:                       // cliente: primeiro HELLO enviado
                    if (!handshakeStarted) {
                        handshakeStarted = true;
                        armarTemporizador(handshakeTimeoutMs, "HELLO sem resposta");
                    }
                    break;
                case HELLO_OK:                    // broker: sessão estabelecida
                    cancelarTemporizador();
                    break;
                case REGISTER:
                case SUBSCRIBE:
                case UNSUBSCRIBE:                 // pedidos que esperam REPLY
                    if (pending++ == 0) {
                        armarTemporizador(replyTimeoutMs, "pedido sem REPLY");
                    }
                    break;
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------- receção

    /** Devolve o próximo pacote, ou null se a sessão terminou. Nunca lança exceção. */
    public SubControlMessage receber() throws IllegalArgumentException {
        if (estaFechada()) return null;
        try {
            int code = in.read();
            if (code < 0) {
                fechar(CloseReason.PEER_CLOSED, "EOF entre frames");
                return null;
            }
            SubControlType type = SubControlType.fromCodigo(code);
            if (type == null) {
                throw new IllegalArgumentException("tipo desconhecido: " + code);
            }
            byte[] lenBytes = new byte[2];
            lerExatamente(lenBytes, 2);
            int len = ((lenBytes[0] & 0xFF) << 8) | (lenBytes[1] & 0xFF);
            if (len > SubControlMessage.MAX_PACKET_SIZE - SubControlMessage.HEADER_SIZE) { // antes de alocar
                throw new IllegalArgumentException("comprimento inválido: " + len);
            }
            byte[] body = new byte[len];
            lerExatamente(body, len);

            byte[] frame = new byte[SubControlMessage.HEADER_SIZE + len];
        frame[0] = (byte) code;
        frame[1] = lenBytes[0];
        frame[2] = lenBytes[1];
        System.arraycopy(body, 0, frame, SubControlMessage.HEADER_SIZE, len);
        return acompanhar(SubControlMessage.deserialize(frame));
            
        } catch (IllegalArgumentException e) {
            terminarComErro(CloseReason.PROTOCOL_ERROR, e.getMessage());
            return null;
        } catch (EOFException e) {
            fechar(CloseReason.IO_ERROR, "ligação fechada a meio de um frame");
            return null;
        } catch (IOException e) {
            // inclui o caso de o temporizador ter fechado o socket: a razão
            // já está registada e este fechar() não faz nada
            fechar(CloseReason.IO_ERROR, String.valueOf(e.getMessage()));
            return null;
        }
    }

    /** Atualiza o estado da sessão e os temporizadores com o pacote recebido. */
    private SubControlMessage acompanhar(SubControlMessage m) {
        switch (m.getSubControlType()) {
            case HELLO:                           // broker: primeiro HELLO recebido
                synchronized (stateLock) {
                    if (!handshakeStarted) {
                        handshakeStarted = true;
                        armarTemporizador(handshakeTimeoutMs, "handshake não concluído");
                    }
                }
                return m;

            case HELLO_OK:                        // cliente: sessão estabelecida
                synchronized (stateLock) {
                    cancelarTemporizador();
                }
                return m;

            case REPLY: {
                ReplyMessage r = (ReplyMessage) m;
                if (r.getReplyTo() == SubControlType.BYE) {
                    fechar(CloseReason.PEER_ERROR, "o par encerrou a sessão: " + r.getStatus());
                    return null;
                }
                synchronized (stateLock) {
                    if (r.getReplyTo() == SubControlType.HELLO) {
                        cancelarTemporizador();   // HELLO recusado: o chamador fecha
                    } else if (pending > 0) {
                        pending--;
                        if (pending == 0) cancelarTemporizador();
                        else armarTemporizador(replyTimeoutMs, "pedido sem REPLY");
                    }
                }
                return r;
            }

            case BYE:
                fechar(CloseReason.PEER_BYE, "BYE recebido");
                return m;                         // devolve-o para o chamador o registar

            default:
                return m;
        }
    }

    private void lerExatamente(byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

    // --------------------------------------------------------- erro e fecho

    /** Erro de protocolo: avisa o par (melhor esforço), depois fecha. */
    private void terminarComErro(CloseReason reason, String detail) {
        if (estaFechada()) return;
        try {
            byte[] aviso = new ReplyMessage(SubControlType.BYE, ReplyStatus.PROTOCOL_ERROR)
                               .serialize();
            synchronized (writeLock) {
                out.write(aviso);
                out.flush();
            }
            esvaziarEntrada();
        } catch (IOException ignored) {
            // o par pode já ter saído: não há ninguém a avisar
        }
        fechar(reason, detail);
    }

    /**
     * Fechar com dados por ler faz o TCP enviar RST, e o par pode perder o
     * aviso que acabámos de escrever. Por isso fecha-se a escrita (FIN) e
     * consome-se o que ainda vier, por pouco tempo, antes de fechar.
     */
    private void esvaziarEntrada() {
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(300);
            byte[] lixo = new byte[256];
            for (int i = 0; i < 64 && in.read(lixo) >= 0; i++) { }
        } catch (IOException ignored) {
        }
    }

    public void fechar() {
        fechar(CloseReason.LOCAL, "fechada localmente");
    }

    private void fechar(CloseReason reason, String detail) {
        synchronized (stateLock) {
            if (closed) return;
            marcarFechada(reason, detail);
        }
        fecharSocket();
    }

    private void marcarFechada(CloseReason reason, String detail) {
        closed = true;
        closeReason = reason;
        closeDetail = detail;
        cancelarTemporizador();
    }

    private void fecharSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // -------------------------------------------------------- temporizador

    private void armarTemporizador(long ms, String motivo) {
        cancelarTemporizador();
        final long minha = generation;
        timer = SCHEDULER.schedule(() -> aoExpirar(minha, motivo), ms, TimeUnit.MILLISECONDS);
    }

    private void cancelarTemporizador() {
        generation++;                     // um temporizador já a disparar fica sem efeito
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private void aoExpirar(long minha, String motivo) {
        synchronized (stateLock) {
            if (minha != generation || closed) return;   // foi cancelado entretanto
            marcarFechada(CloseReason.TIMEOUT, motivo);
        }
        fecharSocket();                   // desbloqueia o receber() em curso
    }

    // ------------------------------------------------------------- estado

    public boolean estaFechada() {
        synchronized (stateLock) { return closed; }
    }

    public CloseReason getMotivoFecho() {
        synchronized (stateLock) { return closeReason; }
    }

    public String getDetalheFecho() {
        synchronized (stateLock) { return closeDetail; }
    }

    public InetAddress getEnderecoRemoto() {
        return socket.getInetAddress();
    }
}
