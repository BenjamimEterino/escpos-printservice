package mz.escpos.printservice;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;

/** Envio por USB (USB Host / OTG) para o endpoint BULK OUT da impressora. */
final class UsbTransport implements Transport {
    private static final int CHUNK = 4096;
    private static final int TIMEOUT_MS = 10000;

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint endpoint;

    private UsbTransport(UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint endpoint) {
        this.conn = conn;
        this.intf = intf;
        this.endpoint = endpoint;
    }

    static UsbDevice findDevice(UsbManager um, int vid, int pid) {
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() == vid && d.getProductId() == pid) return d;
        }
        return null;
    }

    static boolean hasPrinterInterface(UsbDevice dev) {
        if (dev.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            if (dev.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        }
        return false;
    }

    static UsbTransport open(Context ctx, int vid, int pid) throws IOException {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) throw new IOException("Este dispositivo não suporta USB Host (OTG)");
        UsbDevice dev = findDevice(um, vid, pid);
        if (dev == null) {
            throw new IOException(String.format(Locale.US,
                    "Impressora USB %04X:%04X não está ligada", vid, pid));
        }
        boolean ok;
        try {
            ok = UsbPermission.ensure(ctx, um, dev, 30000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Cancelado");
        }
        if (!ok) throw new IOException("Permissão USB negada. Abra a app e toque em \"Usar esta impressora\".");

        UsbInterface chosen = null;
        UsbEndpoint out = null;
        // 1.ª passagem: interfaces da classe impressora; 2.ª: qualquer interface com BULK OUT
        for (int pass = 0; pass < 2 && chosen == null; pass++) {
            for (int i = 0; i < dev.getInterfaceCount() && chosen == null; i++) {
                UsbInterface itf = dev.getInterface(i);
                if (pass == 0 && itf.getInterfaceClass() != UsbConstants.USB_CLASS_PRINTER) continue;
                for (int e = 0; e < itf.getEndpointCount(); e++) {
                    UsbEndpoint ep = itf.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        chosen = itf;
                        out = ep;
                        break;
                    }
                }
            }
        }
        if (chosen == null) throw new IOException("Não encontrei um endpoint BULK OUT na impressora USB");

        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) throw new IOException("Não foi possível abrir a impressora USB");
        if (!conn.claimInterface(chosen, true)) {
            conn.close();
            throw new IOException("A interface USB está ocupada por outra app");
        }
        return new UsbTransport(conn, chosen, out);
    }

    @Override
    public void write(byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelado");
            int len = Math.min(CHUNK, data.length - off);
            int n = conn.bulkTransfer(endpoint, data, off, len, TIMEOUT_MS);
            if (n < 0) throw new IOException("Falha na escrita USB (impressora sem papel ou desligada?)");
            off += n;
        }
    }

    @Override
    public void close() {
        try {
            conn.releaseInterface(intf);
        } finally {
            conn.close();
        }
    }
}
