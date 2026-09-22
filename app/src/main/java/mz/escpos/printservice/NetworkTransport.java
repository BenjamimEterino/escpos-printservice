package mz.escpos.printservice;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Impressão "raw" pela porta TCP 9100 (interface Ethernet da impressora). */
final class NetworkTransport implements Transport {
    private final Socket socket;
    private final OutputStream out;

    NetworkTransport(String host, int port) throws IOException {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(20000);
            socket.setTcpNoDelay(true);
            out = new BufferedOutputStream(socket.getOutputStream(), 16384);
        } catch (IOException e) {
            socket.close();
            throw new IOException("Não foi possível ligar a " + host + ":" + port + " (" + e.getMessage() + ")");
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        out.write(data);
    }

    @Override
    public void close() throws IOException {
        try {
            out.flush();
            socket.shutdownOutput();
        } finally {
            socket.close();
        }
    }
}
