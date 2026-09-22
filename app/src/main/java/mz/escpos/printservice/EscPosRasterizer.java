package mz.escpos.printservice;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Converte páginas PDF em imagem raster 1-bit e envia com o comando ESC/POS "GS v 0".
 * Como tudo é enviado como imagem, acentos (ç, ã, é…) e qualquer tipo de letra saem correctos,
 * sem depender das code pages da impressora.
 */
final class EscPosRasterizer {
    private static final byte[] INIT = {0x1B, 0x40};           // ESC @
    private static final int BAND_ROWS = 128;                  // linhas por bloco GS v 0
    private static final int THRESHOLD = 150;                  // 0..255, abaixo = preto

    private EscPosRasterizer() {}

    static void printPdf(File pdf, PrinterConfig.Entry printer, PrinterConfig.Options opt,
                         int copies, Transport out) throws IOException {
        int width = printer.printWidthDots();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            for (int c = 0; c < copies; c++) {
                out.write(INIT);
                for (int i = 0; i < renderer.getPageCount(); i++) {
                    checkInterrupted();
                    Bitmap bmp;
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        // Escala a largura da página para a largura útil de impressão
                        float scale = width / (float) page.getWidth();
                        int h = Math.max(1, Math.round(page.getHeight() * scale));
                        bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        Matrix m = new Matrix();
                        m.setScale(scale, scale);
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    }
                    try {
                        writeBitmap(bmp, opt, out);
                    } finally {
                        bmp.recycle();
                    }
                }
                finish(opt, out);
            }
        }
    }

    static void printTestPage(PrinterConfig.Entry printer, PrinterConfig.Options opt, Transport out)
            throws IOException {
        int w = printer.printWidthDots();
        String[] lines = {
                printer.name,
                "Largura: " + printer.paperWidthMm + " mm (" + w + " pontos)",
                "Acentos: ÁÀÂÃ ÉÊ Í ÓÔÕ Ú Ç",
                "Total: 1.234,56 MT",
                new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date())
        };
        int h = 90 + lines.length * 38 + 70;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(w >= 576 ? 44 : 34);
        c.drawText("TESTE ESC/POS", w / 2f, 56, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(w >= 576 ? 28 : 22);
        float y = 110;
        for (String line : lines) {
            c.drawText(line, w / 2f, y, p);
            y += 38;
        }
        // Barra de cinzentos (útil para comparar modo texto vs. modo imagem)
        int steps = 8, bw = w / steps;
        for (int i = 0; i < steps; i++) {
            int g = 255 * i / (steps - 1);
            p.setColor(Color.rgb(g, g, g));
            c.drawRect(i * bw, y, (i + 1) * bw, y + 40, p);
        }
        p.setColor(Color.BLACK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        c.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, p);

        out.write(INIT);
        try {
            writeBitmap(b, opt, out);
        } finally {
            b.recycle();
        }
        finish(opt, out);
    }

    private static void finish(PrinterConfig.Options opt, Transport out) throws IOException {
        if (opt.feedLines > 0) out.write(new byte[]{0x1B, 0x64, (byte) opt.feedLines});      // ESC d n
        if (opt.cut) out.write(new byte[]{0x1D, 0x56, 0x42, 0x00});                          // GS V 66 0: avança e corta
        if (opt.openDrawer) out.write(new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFA});       // ESC p: gaveta
    }

    static void writeBitmap(Bitmap bmp, PrinterConfig.Options opt, Transport out) throws IOException {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        byte[] mono = opt.dither ? ditherFloydSteinberg(px, w, h) : threshold(px, w, h);
        px = null;

        int rows = h;
        if (opt.trimBottom) {
            while (rows > 0 && rowBlank(mono, w, rows - 1)) rows--;
            if (rows == 0) return; // página em branco (ex.: página extra do Chrome)
            rows = Math.min(h, rows + 8); // pequena margem
        }

        int widthBytes = (w + 7) / 8;
        for (int y0 = 0; y0 < rows; y0 += BAND_ROWS) {
            checkInterrupted();
            int bh = Math.min(BAND_ROWS, rows - y0);
            byte[] cmd = new byte[8 + widthBytes * bh];
            cmd[0] = 0x1D; cmd[1] = 0x76; cmd[2] = 0x30; cmd[3] = 0x00;   // GS v 0 m=0
            cmd[4] = (byte) (widthBytes & 0xFF);
            cmd[5] = (byte) ((widthBytes >> 8) & 0xFF);
            cmd[6] = (byte) (bh & 0xFF);
            cmd[7] = (byte) ((bh >> 8) & 0xFF);
            int p = 8;
            for (int y = y0; y < y0 + bh; y++) {
                int rowOff = y * w;
                for (int bx = 0; bx < widthBytes; bx++) {
                    int b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = bx * 8 + bit;
                        if (x < w && mono[rowOff + x] != 0) b |= 0x80 >> bit;
                    }
                    cmd[p++] = (byte) b;
                }
            }
            out.write(cmd);
        }
    }

    private static int luminance(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static byte[] threshold(int[] px, int w, int h) {
        byte[] out = new byte[w * h];
        for (int i = 0; i < px.length; i++) out[i] = (byte) (luminance(px[i]) < THRESHOLD ? 1 : 0);
        return out;
    }

    private static byte[] ditherFloydSteinberg(int[] px, int w, int h) {
        int[] lum = new int[w * h];
        for (int i = 0; i < px.length; i++) lum[i] = luminance(px[i]);
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int old = lum[i];
                int nw = old < 128 ? 0 : 255;
                out[i] = (byte) (nw == 0 ? 1 : 0);
                int err = old - nw;
                if (x + 1 < w) lum[i + 1] += err * 7 / 16;
                if (y + 1 < h) {
                    if (x > 0) lum[i + w - 1] += err * 3 / 16;
                    lum[i + w] += err * 5 / 16;
                    if (x + 1 < w) lum[i + w + 1] += err / 16;
                }
            }
        }
        return out;
    }

    private static boolean rowBlank(byte[] mono, int w, int y) {
        int off = y * w;
        for (int x = 0; x < w; x++) if (mono[off + x] != 0) return false;
        return true;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelado");
    }
}
