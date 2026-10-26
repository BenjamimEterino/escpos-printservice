package mz.escpos.printservice;

import android.content.Context;

import java.io.IOException;

final class Transports {
    private Transports() {}

    /**
     * Abre a ligação. Para USB pode bloquear até 30 s à espera da permissão e para Bluetooth
     * alguns segundos a ligar: nunca chamar na main thread.
     */
    static Transport open(Context ctx, PrinterConfig.Entry e) throws IOException {
        if (PrinterConfig.TYPE_NET.equals(e.type)) {
            return new NetworkTransport(e.host, e.port);
        }
        if (PrinterConfig.TYPE_BT.equals(e.type)) {
            return BluetoothTransport.open(ctx, e.btAddress);
        }
        return UsbTransport.open(ctx, e.vendorId, e.productId);
    }
}
