package mz.escpos.printservice;

import android.content.Context;
import android.content.SharedPreferences;

/** Definições do servidor HTTP local de impressão silenciosa (sem diálogo). */
public final class BridgeConfig {

    public static final int DEFAULT_PORT = 9200;

    private static final String PREFS = "escpos_bridge";

    private BridgeConfig() {}

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean("enabled", v).apply();
    }

    public static int getPort(Context c) {
        return prefs(c).getInt("port", DEFAULT_PORT);
    }

    public static void setPort(Context c, int port) {
        prefs(c).edit().putInt("port", port).apply();
    }

    /** Vazio = sem autenticação (cabeçalho X-Api-Key deixa de ser exigido). */
    public static String getApiKey(Context c) {
        return prefs(c).getString("api_key", "");
    }

    public static void setApiKey(Context c, String key) {
        prefs(c).edit().putString("api_key", key == null ? "" : key.trim()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
