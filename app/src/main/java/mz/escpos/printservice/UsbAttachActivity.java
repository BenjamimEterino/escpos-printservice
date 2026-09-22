package mz.escpos.printservice;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Aberta pelo sistema quando se liga uma impressora USB. Se o utilizador marcar
 * "Usar sempre", o Android concede permissão USB permanente a esta app.
 */
public class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UsbDevice dev = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (dev != null) {
            String id = PrinterConfig.usbId(dev.getVendorId(), dev.getProductId());
            if (PrinterConfig.find(this, id) == null) {
                PrinterConfig.addOrReplace(this, PrinterConfig.usb(SettingsActivity.usbLabel(dev),
                        dev.getVendorId(), dev.getProductId(), 80));
                Toast.makeText(this, "Impressora USB adicionada (papel 80 mm)", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Impressora USB pronta", Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }
}
