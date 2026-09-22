package mz.escpos.printservice;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Publica no sistema as impressoras configuradas na app. */
final class EscPosDiscoverySession extends PrinterDiscoverySession {
    private final PrintService service;

    EscPosDiscoverySession(PrintService service) {
        this.service = service;
    }

    @Override public void onStartPrinterDiscovery(List<PrinterId> priorityList) { publish(); }
    @Override public void onStopPrinterDiscovery() {}
    @Override public void onValidatePrinters(List<PrinterId> printerIds) { publish(); }
    @Override public void onStartPrinterStateTracking(PrinterId printerId) { publish(); }
    @Override public void onStopPrinterStateTracking(PrinterId printerId) {}
    @Override public void onDestroy() {}

    private void publish() {
        UsbManager um = (UsbManager) service.getSystemService(Context.USB_SERVICE);
        List<PrinterInfo> infos = new ArrayList<>();
        Set<PrinterId> keep = new HashSet<>();

        for (PrinterConfig.Entry e : PrinterConfig.load(service)) {
            PrinterId id = service.generatePrinterId(e.id);
            keep.add(id);
            int status = PrinterInfo.STATUS_IDLE;
            if (PrinterConfig.TYPE_USB.equals(e.type)
                    && (um == null || UsbTransport.findDevice(um, e.vendorId, e.productId) == null)) {
                status = PrinterInfo.STATUS_UNAVAILABLE;
            }
            infos.add(new PrinterInfo.Builder(id, e.name, status)
                    .setDescription(e.describe())
                    .setCapabilities(capabilities(id, e))
                    .build());
        }

        List<PrinterId> stale = new ArrayList<>();
        for (PrinterInfo p : getPrinters()) if (!keep.contains(p.getId())) stale.add(p.getId());
        if (!stale.isEmpty()) removePrinters(stale);
        addPrinters(infos);
    }

    private static PrinterCapabilitiesInfo capabilities(PrinterId id, PrinterConfig.Entry e) {
        int w = e.paperWidthMm;
        int wMils = mils(w);
        PrinterCapabilitiesInfo.Builder b = new PrinterCapabilitiesInfo.Builder(id);
        b.addMediaSize(new PrintAttributes.MediaSize("ESCPOS_" + w + "x200",
                "Talão " + w + " × 200 mm", wMils, mils(200)), true);
        b.addMediaSize(new PrintAttributes.MediaSize("ESCPOS_" + w + "x297",
                "Talão " + w + " × 297 mm", wMils, mils(297)), false);
        b.addMediaSize(new PrintAttributes.MediaSize("ESCPOS_" + w + "x600",
                "Talão " + w + " × 600 mm (longo)", wMils, mils(600)), false);
        // A4 é reduzido à largura do rolo (útil para facturas do Odoo, mas a letra fica pequena)
        b.addMediaSize(PrintAttributes.MediaSize.ISO_A4, false);
        b.addResolution(new PrintAttributes.Resolution("R203", "203 dpi", 203, 203), true);
        b.setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME);
        b.setMinMargins(new PrintAttributes.Margins(0, 0, 0, 0));
        return b.build();
    }

    private static int mils(int mm) {
        return Math.round(mm * 1000f / 25.4f);
    }
}
