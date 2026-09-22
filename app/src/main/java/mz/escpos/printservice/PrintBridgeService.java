package mz.escpos.printservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/**
 * Serviço em primeiro plano que mantém o servidor HTTP de impressão silenciosa a correr,
 * mesmo com a app em segundo plano ou o ecrã apagado.
 */
public class PrintBridgeService extends Service {
    private static final String TAG = "EscPosBridge";
    private static final String CHANNEL_ID = "escpos_bridge";
    private static final int NOTIF_ID = 42;

    private PrintBridgeServer server;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, PrintBridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, PrintBridgeService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int port = BridgeConfig.getPort(this);
        showForeground("A ouvir na porta " + port + "…");

        if (server == null) {
            server = new PrintBridgeServer(getApplicationContext());
            try {
                server.start(port);
            } catch (IOException e) {
                Log.e(TAG, "Falha ao iniciar servidor na porta " + port, e);
                showForeground("Erro ao iniciar na porta " + port + ": " + e.getMessage());
                server = null;
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }

    private void showForeground(String text) {
        Intent open = new Intent(this, SettingsActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Impressão silenciosa activa")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_printer)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Impressão silenciosa",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Mantém o servidor de impressão (sem diálogo) activo em segundo plano.");
        nm.createNotificationChannel(ch);
    }
}
