package protocolos.tcp.pacotes;

import protocolos.tcp.Roles;
import protocolos.tcp.SubControlPacket;
import protocolos.tcp.SubControlType;

public final class HelloPacket extends SubControlPacket {

    private final Roles role;
    private final int udpPort;
    private final String id;

    public HelloPacket(Roles role, int udpPort, String id) {
        int idLength = id.getBytes().length;
        if (udpPort < 1 || udpPort > 65535) {
            throw new IllegalArgumentException("O valor da porta UDP deve estar entre 0 e 65535.");
        }
        if (idLength > 255 || idLength < 1) {
            throw new IllegalArgumentException("O tamanho do ID não pode exceder 255 bytes.");
        }
        this.role = role;
        this.udpPort = udpPort;
        this.id = id;
    }

    @Override 
    public SubControlType getSubControlType() {
        return SubControlType.HELLO;
    }

    @Override
    protected byte[] corpoToBytes() {
        byte[] idBytes = id.getBytes();
        byte[] corpo = new byte[1 + 2 + 1 + idBytes.length];
        corpo[0] = (byte) role.ordinal();
        corpo[1] = (byte) ((udpPort >> 8) & 0xFF);
        corpo[2] = (byte) (udpPort & 0xFF);
        corpo[3] = (byte) idBytes.length;
        System.arraycopy(idBytes, 0, corpo, 4, idBytes.length);
        return corpo;
    }

    public static HelloPacket parse(byte[] body) {
        if (body.length < 4) {
            throw new IllegalArgumentException("O corpo do pacote é muito pequeno para conter os campos obrigatórios.");
        }
        Roles role = Roles.values()[body[0]];
        int udpPort = ((body[1] & 0xFF) << 8) | (body[2] & 0xFF);
        int idLength = body[3] & 0xFF;
        if (body.length != 4 + idLength) {
            throw new IllegalArgumentException("O tamanho do corpo do pacote não corresponde ao tamanho do ID especificado.");
        }
        String id = new String(body, 4, idLength);
        return new HelloPacket(role, udpPort, id);
    }

    public Roles getRole() {
        return role;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public String getId() {
        return id;
    }


}
