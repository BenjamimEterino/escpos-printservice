package mz.escpos.printservice;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintJobId;
import android.print.PrinterId;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Serviço de impressão do Android: faz aparecer a impressora ESC/POS no diálogo "Imprimir"
 * de qualquer app (Chrome/Odoo, visualizadores de PDF, apps React Native, etc.).
 */
public class EscPosPrintService extends PrintService {
    private static final String TAG = "EscPosPrintService";

    private ExecutorService executor;
    private Handler main;
    private final Map<PrintJobId, Future<?>> running = new HashMap<>(); // só na main thread

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        main = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new EscPosDiscoverySession(this);
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob job) {
        Future<?> f = running.remove(job.getId());
        if (f != null) f.cancel(true);
        if (!job.isCancelled() && !job.isCompleted() && !job.isFailed()) job.cancel();
    }

    @Override
    protected void onPrintJobQueued(final PrintJob job) {
        if (!job.isQueued()) return;

        PrinterId printerId = job.getInfo().getPrinterId();
        final PrinterConfig.Entry printer =
                printerId == null ? null : PrinterConfig.find(this, printerId.getLocalId());
        if (printer == null) {
            job.fail("Impressora não configurada. Abra a app \"Impressora ESC/POS\".");
            return;
        }
        final ParcelFileDescriptor data = job.getDocument().getData();
        if (data == null) {
            job.fail("Documento vazio");
            return;
        }
        final int copies = Math.max(1, job.getInfo().getCopies());
        final PrinterConfig.Options opt = PrinterConfig.loadOptions(this);
        final PrintJobId id = job.getId();

        job.start();
        Future<?> f = executor.submit(() -> {
            String error = null;
            File tmp = null;
            try {
                // PdfRenderer precisa de um ficheiro "seekable": copiamos o PDF para a cache
                tmp = File.createTempFile("printjob", ".pdf", getCacheDir());
                copy(data, tmp);
                try (Transport t = Transports.open(this, printer)) {
                    EscPosRasterizer.printPdf(tmp, printer, opt, copies, t);
                }
            } catch (InterruptedIOException ie) {
                error = "Cancelado";
            } catch (Throwable ex) {
                Log.e(TAG, "Falha ao imprimir", ex);
                error = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            } finally {
                try {
                    data.close();
                } catch (IOException ignored) {
                }
                if (tmp != null) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
            }
            final String err = error;
            main.post(() -> {
                running.remove(id);
                if (job.isCancelled() || job.isCompleted() || job.isFailed()) return;
                if (err == null) job.complete();
                else job.fail(err);
            });
        });
        running.put(id, f);
    }

    private static void copy(ParcelFileDescriptor src, File dst) throws IOException {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
