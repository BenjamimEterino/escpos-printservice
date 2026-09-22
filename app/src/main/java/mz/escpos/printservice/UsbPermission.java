package mz.escpos.printservice;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class UsbPermission {
    private UsbPermission() {}

    static String action(Context ctx) {
        return ctx.getPackageName() + ".USB_PERMISSION";
    }

    static PendingIntent pendingIntent(Context ctx) {
        Intent intent = new Intent(action(ctx)).setPackage(ctx.getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getBroadcast(ctx, 0, intent, flags);
    }

    /** Pede a permissão sem esperar (usado no ecrã de definições). */
    static void request(Context ctx, UsbManager um, UsbDevice dev) {
        if (!um.hasPermission(dev)) um.requestPermission(dev, pendingIntent(ctx));
    }

    /** Pede a permissão e espera pela resposta do utilizador (thread de fundo). */
    static boolean ensure(Context ctx, UsbManager um, UsbDevice dev, long timeoutMs)
            throws InterruptedException {
        if (um.hasPermission(dev)) return true;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] granted = {false};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                granted[0] = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                latch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(action(ctx));
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
        try {
            um.requestPermission(dev, pendingIntent(ctx));
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            ctx.unregisterReceiver(receiver);
        }
        return granted[0] || um.hasPermission(dev);
    }
}
