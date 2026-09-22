package mz.escpos.printservice;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** Ecrã de configuração (UI feita em código, sem layouts XML nem bibliotecas). */
public class SettingsActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout printersBox;
    private LinearLayout usbBox;
    private RadioGroup widthGroup;
    private CheckBox bridgeEnabled;
    private EditText bridgePort;
    private EditText bridgeApiKey;
    private TextView bridgeStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = vbox();
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("Passo 1: active o serviço \"Impressora ESC/POS\" nas definições de impressão do Android."));
        root.addView(button("Abrir definições de impressão", v -> openPrintSettings()));

        root.addView(header("Impressoras configuradas"));
        printersBox = vbox();
        root.addView(printersBox);

        root.addView(header("Largura do papel (para novas impressoras)"));
        widthGroup = new RadioGroup(this);
        widthGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton r80 = radio("80 mm", 80);
        RadioButton r58 = radio("58 mm", 58);
        widthGroup.addView(r80);
        widthGroup.addView(r58);
        r80.setChecked(true);
        root.addView(widthGroup);

        // ---- Rede ----
        root.addView(header("Adicionar impressora de rede (Ethernet)"));
        EditText netName = edit("Nome (ex.: Balcão)", InputType.TYPE_CLASS_TEXT);
        EditText netHost = edit("Endereço IP (ex.: 192.168.1.100)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText netPort = edit("Porta", InputType.TYPE_CLASS_NUMBER);
        netPort.setText("9100");
        root.addView(netName);
        root.addView(netHost);
        root.addView(netPort);
        root.addView(button("Adicionar impressora de rede", v -> {
            String host = netHost.getText().toString().trim();
            if (host.isEmpty()) {
                toast("Indique o endereço IP");
                return;
            }
            int port = parseInt(netPort.getText().toString(), 9100);
            String name = netName.getText().toString().trim();
            if (name.isEmpty()) name = "ESC/POS " + host;
            PrinterConfig.addOrReplace(this, PrinterConfig.network(name, host, port, selectedWidth()));
            toast("Impressora adicionada");
            refreshPrinters();
        }));

        // ---- USB ----
        root.addView(header("Impressoras USB ligadas (cabo/adaptador OTG)"));
        root.addView(button("Procurar dispositivos USB", v -> refreshUsb()));
        usbBox = vbox();
        root.addView(usbBox);

        // ---- Opções ----
        root.addView(header("Opções de impressão"));
        PrinterConfig.Options o = PrinterConfig.loadOptions(this);
        CheckBox cut = check("Cortar o papel no fim", o.cut);
        CheckBox trim = check("Remover espaço em branco no fim de cada página", o.trimBottom);
        CheckBox dither = check("Modo imagem (dithering) – melhor para logótipos e fotos", o.dither);
        CheckBox drawer = check("Abrir gaveta de dinheiro após imprimir", o.openDrawer);
        EditText feed = edit("Linhas de avanço antes do corte", InputType.TYPE_CLASS_NUMBER);
        feed.setText(String.valueOf(o.feedLines));
        root.addView(cut);
        root.addView(trim);
        root.addView(dither);
        root.addView(drawer);
        root.addView(text("Linhas de avanço antes do corte:"));
        root.addView(feed);
        root.addView(button("Guardar opções", v -> {
            PrinterConfig.Options n = new PrinterConfig.Options();
            n.cut = cut.isChecked();
            n.trimBottom = trim.isChecked();
            n.dither = dither.isChecked();
            n.openDrawer = drawer.isChecked();
            n.feedLines = parseInt(feed.getText().toString(), 1);
            PrinterConfig.saveOptions(this, n);
            toast("Opções guardadas");
        }));

        // ---- Impressão silenciosa (HTTP local) ----
        root.addView(header("Impressão directa (HTTP local, sem diálogo)"));
        root.addView(text("Para o Odoo POS (Chrome) e para apps próprias (ex.: React Native) imprimirem "
                + "sem mostrar o diálogo \"Imprimir\" do Android: activa este servidor e chama-o por HTTP. "
                + "Ver README para o contrato da API (GET /health, GET /printers, POST /print)."));
        bridgeEnabled = check("Activar servidor local", BridgeConfig.isEnabled(this));
        root.addView(bridgeEnabled);
        bridgePort = edit("Porta", InputType.TYPE_CLASS_NUMBER);
        bridgePort.setText(String.valueOf(BridgeConfig.getPort(this)));
        root.addView(text("Porta:"));
        root.addView(bridgePort);
        bridgeApiKey = edit("Chave de API (opcional; cabeçalho X-Api-Key)", InputType.TYPE_CLASS_TEXT);
        bridgeApiKey.setText(BridgeConfig.getApiKey(this));
        root.addView(text("Chave de API (deixe vazio para não exigir autenticação):"));
        root.addView(bridgeApiKey);
        root.addView(button("Guardar e aplicar", v -> applyBridgeSettings()));
        bridgeStatus = text("");
        root.addView(bridgeStatus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPrinters();
        refreshUsb();
        refreshBridgeStatus();
    }

    private void refreshPrinters() {
        printersBox.removeAllViews();
        List<PrinterConfig.Entry> list = PrinterConfig.load(this);
        if (list.isEmpty()) {
            printersBox.addView(text("Nenhuma impressora configurada."));
            return;
        }
        String defaultId = PrinterConfig.getDefaultPrinterId(this);
        boolean singleIsDefault = defaultId == null && list.size() == 1;
        for (PrinterConfig.Entry e : list) {
            boolean isDefault = e.id.equals(defaultId) || singleIsDefault;
            TextView t = text((isDefault ? "★ " : "") + e.name + "\n" + e.describe()
                    + (isDefault ? "\n(predefinida para impressão silenciosa)" : ""));
            t.setTypeface(Typeface.DEFAULT_BOLD);
            printersBox.addView(t);
            LinearLayout row = hbox();
            row.addView(button("Imprimir teste", v -> testPrint(e)));
            if (!isDefault) {
                row.addView(button("Tornar predefinida", v -> {
                    PrinterConfig.setDefaultPrinterId(this, e.id);
                    refreshPrinters();
                }));
            }
            row.addView(button("Remover", v -> {
                PrinterConfig.remove(this, e.id);
                refreshPrinters();
            }));
            printersBox.addView(row);
        }
    }

    private void applyBridgeSettings() {
        boolean enabled = bridgeEnabled.isChecked();
        int port = parseInt(bridgePort.getText().toString(), BridgeConfig.DEFAULT_PORT);
        BridgeConfig.setEnabled(this, enabled);
        BridgeConfig.setPort(this, port);
        BridgeConfig.setApiKey(this, bridgeApiKey.getText().toString());

        PrintBridgeService.stop(this);
        if (enabled) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            PrintBridgeService.start(this);
            toast("Servidor activado");
        } else {
            toast("Servidor desactivado");
        }
        ui.postDelayed(this::refreshBridgeStatus, 500);
    }

    private void refreshBridgeStatus() {
        if (bridgeStatus == null) return;
        boolean enabled = BridgeConfig.isEnabled(this);
        int port = BridgeConfig.getPort(this);
        StringBuilder sb = new StringBuilder();
        if (!enabled) {
            sb.append("Servidor desactivado.");
        } else {
            List<String> ips = localIpAddresses();
            if (ips.isEmpty()) {
                sb.append("Servidor activo na porta ").append(port)
                        .append(", mas sem IP de rede detectado (verifique o Wi-Fi/Ethernet).");
            } else {
                sb.append("Servidor activo. Endpoint de impressão:\n");
                for (String ip : ips) sb.append("  http://").append(ip).append(':').append(port).append("/print\n");
                sb.append("No mesmo aparelho (Chrome/RN), use http://127.0.0.1:").append(port).append("/print.");
            }
        }
        bridgeStatus.setText(sb.toString());
    }

    private static List<String> localIpAddresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr.getHostAddress() == null) continue;
                    if (addr.getHostAddress().contains(":")) continue; // ignora IPv6
                    out.add(addr.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out);
        return out;
    }

    private void refreshUsb() {
        usbBox.removeAllViews();
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        Collection<UsbDevice> devices = um == null ? null : um.getDeviceList().values();
        if (devices == null || devices.isEmpty()) {
            usbBox.addView(text("Nenhum dispositivo USB detectado."));
            return;
        }
        for (UsbDevice dev : devices) {
            String label = usbLabel(dev);
            String info = String.format(Locale.US, "%s\nVID %d / PID %d%s%s", label,
                    dev.getVendorId(), dev.getProductId(),
                    UsbTransport.hasPrinterInterface(dev) ? " · classe impressora" : "",
                    um.hasPermission(dev) ? " · permissão OK" : "");
            usbBox.addView(text(info));
            usbBox.addView(button("Usar esta impressora", v -> {
                PrinterConfig.addOrReplace(this, PrinterConfig.usb(label,
                        dev.getVendorId(), dev.getProductId(), selectedWidth()));
                UsbPermission.request(this, um, dev);
                toast("Impressora USB adicionada");
                refreshPrinters();
            }));
        }
    }

    static String usbLabel(UsbDevice dev) {
        String name = null;
        try {
            String m = dev.getManufacturerName(), p = dev.getProductName();
            if (m != null || p != null) name = ((m == null ? "" : m + " ") + (p == null ? "" : p)).trim();
        } catch (SecurityException ignored) {
        }
        return name == null || name.isEmpty() ? "Impressora USB" : name;
    }

    private void testPrint(PrinterConfig.Entry e) {
        toast("A imprimir teste…");
        PrinterConfig.Options o = PrinterConfig.loadOptions(this);
        Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            try (Transport t = Transports.open(app, e)) {
                EscPosRasterizer.printTestPage(e, o, t);
                msg = "Teste enviado para " + e.name;
            } catch (Exception ex) {
                msg = "Erro: " + ex.getMessage();
            }
            final String m = msg;
            ui.post(() -> toast(m));
        }).start();
    }

    private void openPrintSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_PRINT_SETTINGS));
        } catch (ActivityNotFoundException ex) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ---------- helpers de UI ----------

    private int selectedWidth() {
        View v = widthGroup.findViewById(widthGroup.getCheckedRadioButtonId());
        return v != null && v.getTag() instanceof Integer ? (Integer) v.getTag() : 80;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(checked);
        return c;
    }

    private RadioButton radio(String label, int tag) {
        RadioButton r = new RadioButton(this);
        r.setId(View.generateViewId());
        r.setText(label);
        r.setTag(tag);
        return r;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
