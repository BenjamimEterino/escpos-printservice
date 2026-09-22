package mz.escpos.printservice;

import java.io.Closeable;
import java.io.IOException;

/** Canal de bytes para a impressora (USB ou TCP 9100). */
interface Transport extends Closeable {
    void write(byte[] data) throws IOException;
}
