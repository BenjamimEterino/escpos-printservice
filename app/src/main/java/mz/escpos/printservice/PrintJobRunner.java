package mz.escpos.printservice;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Execução efectiva de um trabalho de impressão (partilhada pelo PrintService e pelo bridge HTTP). */
final class PrintJobRunner {
    private PrintJobRunner() {}

    static void printPdfBytes(Context ctx, PrinterConfig.Entry printer, PrinterConfig.Options opt,
                               byte[] pdfBytes, int copies) throws IOException {
        File tmp = File.createTempFile("bridge", ".pdf", ctx.getCacheDir());
        try {
            try (OutputStream fos = new FileOutputStream(tmp)) {
                fos.write(pdfBytes);
            }
            try (Transport t = Transports.open(ctx, printer)) {
                EscPosRasterizer.printPdf(tmp, printer, opt, copies, t);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Bytes ESC/POS já prontos (o chamador controla tudo, incluindo corte/gaveta). */
    static void printRawBytes(Context ctx, PrinterConfig.Entry printer, byte[] raw) throws IOException {
        try (Transport t = Transports.open(ctx, printer)) {
            t.write(raw);
        }
    }
}
