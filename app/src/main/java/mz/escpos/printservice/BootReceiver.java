package mz.escpos.printservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reinicia o servidor de impressão silenciosa depois de o telemóvel/terminal reiniciar. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && BridgeConfig.isEnabled(context)) {
            PrintBridgeService.start(context);
        }
    }
}
