package mz.escpos.printservice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Envio por Bluetooth clássico (perfil série SPP/RFCOMM). Serve também para as impressoras
 * internas que o terminal expõe como impressora Bluetooth virtual (ex.: ZCS Z92).
 * As permissões são verificadas em {@link #hasPermission} antes de qualquer chamada.
 */
@SuppressLint("MissingPermission")
final class BluetoothTransport implements Transport {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int CHUNK = 4096;
    // Fechar logo após a última escrita pode descartar o que ainda está no buffer do Bluetooth
    // e o recibo sai cortado.
    private static final long LINGER_MS = 500;

    private final BluetoothSocket socket;
    private final OutputStream out;

    private BluetoothTransport(BluetoothSocket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
    }

    static BluetoothAdapter adapter(Context ctx) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    /** Android 12+ exige a permissão "Dispositivos próximos" (pedida no ecrã da app). */
    static boolean hasPermission(Context ctx) {
        return Build.VERSION.SDK_INT < 31
                || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean looksLikePrinter(BluetoothDevice dev) {
        BluetoothClass c = dev.getBluetoothClass();
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING;
    }

    static BluetoothTransport open(Context ctx, String address) throws IOException {
        BluetoothAdapter ad = adapter(ctx);
        if (ad == null) throw new IOException("Este dispositivo não tem Bluetooth");
        if (!hasPermission(ctx)) {
            throw new IOException("Permissão Bluetooth em falta. Abra a app e toque em \"Procurar impressoras Bluetooth\".");
        }
        if (!ad.isEnabled()) throw new IOException("O Bluetooth está desligado");
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IOException("Endereço Bluetooth inválido: " + address);
        }
        // Uma pesquisa de dispositivos em curso torna a ligação lenta ou fá-la falhar.
        // No Android 12+ cancelar exige outra permissão; a app nunca inicia pesquisas.
        if (Build.VERSION.SDK_INT < 31) ad.cancelDiscovery();

        BluetoothDevice dev = ad.getRemoteDevice(address);
        BluetoothSocket s = null;
        try {
            s = dev.createRfcommSocketToServiceRecord(SPP);
            s.connect();
        } catch (IOException secure) {
            closeQuietly(s);
            // Algumas impressoras só aceitam ligações sem encriptação
            try {
                s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                s.connect();
            } catch (IOException e) {
                closeQuietly(s);
                throw new IOException("Não foi possível ligar à impressora Bluetooth " + address
                        + " (está ligada e emparelhada?)");
            }
        }
        try {
            return new BluetoothTransport(s);
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelado");
            int len = Math.min(CHUNK, data.length - off);
            out.write(data, off, len);
            off += len;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            out.flush();
            Thread.sleep(LINGER_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            socket.close();
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
