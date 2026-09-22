package mz.escpos.printservice;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Impressoras configuradas e opções de impressão, guardadas em SharedPreferences. */
public final class PrinterConfig {

    public static final String TYPE_NET = "net";
    public static final String TYPE_USB = "usb";

    private static final String PREFS = "escpos_printers";
    private static final String KEY_LIST = "printers";

    private PrinterConfig() {}

    public static final class Entry {
        public String id;
        public String name;
        public String type;
        public String host;
        public int port = 9100;
        public int vendorId;
        public int productId;
        public int paperWidthMm = 80;

        /** 8 pontos/mm: 72 mm úteis = 576 pontos (papel 80 mm); 48 mm = 384 pontos (papel 58 mm). */
        public int printWidthDots() {
            return paperWidthMm == 58 ? 384 : 576;
        }

        public String describe() {
            if (TYPE_NET.equals(type)) {
                return "Rede " + host + ":" + port + " · papel " + paperWidthMm + " mm";
            }
            return String.format(Locale.US, "USB VID %04X PID %04X (%d/%d) · papel %d mm",
                    vendorId, productId, vendorId, productId, paperWidthMm);
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("type", type);
            o.put("host", host == null ? "" : host);
            o.put("port", port);
            o.put("vid", vendorId);
            o.put("pid", productId);
            o.put("width", paperWidthMm);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id");
            e.name = o.optString("name");
            e.type = o.optString("type");
            e.host = o.optString("host");
            e.port = o.optInt("port", 9100);
            e.vendorId = o.optInt("vid");
            e.productId = o.optInt("pid");
            e.paperWidthMm = o.optInt("width", 80);
            return e;
        }
    }

    public static final class Options {
        public boolean cut = true;
        public boolean trimBottom = true;
        public boolean dither = false;
        public boolean openDrawer = false;
        public int feedLines = 1;
    }

    public static Entry network(String name, String host, int port, int widthMm) {
        Entry e = new Entry();
        e.type = TYPE_NET;
        e.name = name;
        e.host = host;
        e.port = port;
        e.paperWidthMm = widthMm;
        e.id = "net:" + host + ":" + port;
        return e;
    }

    public static Entry usb(String name, int vid, int pid, int widthMm) {
        Entry e = new Entry();
        e.type = TYPE_USB;
        e.name = name;
        e.vendorId = vid;
        e.productId = pid;
        e.paperWidthMm = widthMm;
        e.id = usbId(vid, pid);
        return e;
    }

    public static String usbId(int vid, int pid) {
        return String.format(Locale.US, "usb:%04x:%04x", vid, pid);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Entry> load(Context c) {
        List<Entry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(Entry.fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    public static synchronized void save(Context c, List<Entry> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Entry e : list) arr.put(e.toJson());
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static Entry find(Context c, String id) {
        for (Entry e : load(c)) if (e.id.equals(id)) return e;
        return null;
    }

    public static synchronized void addOrReplace(Context c, Entry entry) {
        List<Entry> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(entry.id)) {
                list.set(i, entry);
                save(c, list);
                return;
            }
        }
        list.add(entry);
        save(c, list);
    }

    public static synchronized void remove(Context c, String id) {
        List<Entry> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        save(c, list);
        if (id.equals(getDefaultPrinterId(c))) setDefaultPrinterId(c, null);
    }

    /** Impressora usada pelo servidor HTTP (bridge) quando o pedido não indica ?printer=. */
    public static String getDefaultPrinterId(Context c) {
        return prefs(c).getString("default_printer", null);
    }

    public static void setDefaultPrinterId(Context c, String id) {
        prefs(c).edit().putString("default_printer", id).apply();
    }

    public static Entry findDefault(Context c) {
        List<Entry> list = load(c);
        String id = getDefaultPrinterId(c);
        if (id != null) {
            for (Entry e : list) if (e.id.equals(id)) return e;
        }
        return list.size() == 1 ? list.get(0) : null;
    }

    public static Options loadOptions(Context c) {
        SharedPreferences p = prefs(c);
        Options o = new Options();
        o.cut = p.getBoolean("opt_cut", true);
        o.trimBottom = p.getBoolean("opt_trim", true);
        o.dither = p.getBoolean("opt_dither", false);
        o.openDrawer = p.getBoolean("opt_drawer", false);
        o.feedLines = p.getInt("opt_feed", 1);
        return o;
    }

    public static void saveOptions(Context c, Options o) {
        prefs(c).edit()
                .putBoolean("opt_cut", o.cut)
                .putBoolean("opt_trim", o.trimBottom)
                .putBoolean("opt_dither", o.dither)
                .putBoolean("opt_drawer", o.openDrawer)
                .putInt("opt_feed", Math.max(0, Math.min(20, o.feedLines)))
                .apply();
    }
}
