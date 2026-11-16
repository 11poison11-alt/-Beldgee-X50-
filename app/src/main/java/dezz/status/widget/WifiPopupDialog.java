package dezz.status.widget;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Всплывающее оверлей-окно выбора и подключения к Wi-Fi сетям для ГУ Belgee X50 / Coolray.
 */
final class WifiPopupDialog {

    private final Context context;
    private final WindowManager windowManager;
    private final WifiManager wifiManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable private FrameLayout rootOverlay;
    @Nullable private LinearLayout listContainer;
    @Nullable private TextView statusText;
    @Nullable private Switch wifiSwitch;
    private boolean receiverRegistered = false;

    WifiPopupDialog(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    boolean isShowing() {
        return rootOverlay != null;
    }

    @SuppressLint("MissingPermission")
    void show() {
        if (rootOverlay != null || windowManager == null || wifiManager == null) return;

        rootOverlay = new FrameLayout(context);
        rootOverlay.setBackgroundColor(0x99000000);
        rootOverlay.setOnClickListener(v -> dismiss());

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        card.setClickable(true);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E2229);
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), 0xFF3A414E);
        card.setBackground(cardBg);

        // Заголовок и переключатель Wi-Fi
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("Сети Wi-Fi");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        wifiSwitch = new Switch(context);
        wifiSwitch.setChecked(wifiManager.isWifiEnabled());
        wifiSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            wifiManager.setWifiEnabled(isChecked);
            updateStatusHeader();
            if (isChecked) {
                triggerScan();
            } else if (listContainer != null) {
                listContainer.removeAllViews();
            }
        });
        header.addView(wifiSwitch);

        Button refreshBtn = makeActionButton("Обновить", 0xFF2D3542);
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        refLp.leftMargin = dp(12);
        refreshBtn.setOnClickListener(v -> triggerScan());
        header.addView(refreshBtn, refLp);

        Button closeBtn = makeActionButton("✕", 0xFF3A2A2E);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = dp(8);
        closeBtn.setOnClickListener(v -> dismiss());
        header.addView(closeBtn, closeLp);

        card.addView(header);

        // Текущий статус подключения
        statusText = new TextView(context);
        statusText.setTextColor(0xFFB0BEC5);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setPadding(0, dp(8), 0, dp(10));
        card.addView(statusText);

        // Прокручиваемый список сетей
        ScrollView scrollView = new ScrollView(context);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listContainer);
        card.addView(scrollView, scrollLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(dp(480), LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        rootOverlay.addView(card, cardLp);

        // FLAG_NOT_FOCUSABLE НЕ ставим, чтобы работала клавиатура при вводе пароля!
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.CENTER;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        try {
            windowManager.addView(rootOverlay, lp);
        } catch (Throwable t) {
            rootOverlay = null;
            return;
        }

        registerScanReceiver();
        updateStatusHeader();
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
            wifiSwitch.setChecked(true);
        }
        renderScanResults(wifiManager.getScanResults());
        triggerScan();
    }

    void dismiss() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(scanReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        if (rootOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(rootOverlay);
            } catch (Throwable ignored) {
            }
            rootOverlay = null;
        }
        listContainer = null;
        statusText = null;
        wifiSwitch = null;
    }

    private void registerScanReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        context.registerReceiver(scanReceiver, filter);
        receiverRegistered = true;
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (rootOverlay == null) return;
            updateStatusHeader();
            renderScanResults(wifiManager.getScanResults());
        }
    };

    @SuppressLint("MissingPermission")
    private void triggerScan() {
        if (statusText != null) {
            statusText.setText("Поиск доступных сетей Wi-Fi...");
        }
        try {
            wifiManager.startScan();
        } catch (Throwable ignored) {
        }
        handler.postDelayed(() -> {
            if (rootOverlay != null) {
                updateStatusHeader();
                renderScanResults(wifiManager.getScanResults());
            }
        }, 1500L);
    }

    @SuppressLint("MissingPermission")
    private void updateStatusHeader() {
        if (statusText == null || wifiManager == null) return;
        if (!wifiManager.isWifiEnabled()) {
            statusText.setText("Wi-Fi выключен");
            return;
        }
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info != null && info.getNetworkId() != -1 && info.getSSID() != null) {
            String ssid = info.getSSID().replace("\"", "");
            if (!"<unknown ssid>".equalsIgnoreCase(ssid)) {
                statusText.setText("Подключено: " + ssid + " (" + info.getLinkSpeed() + " Мбит/с)");
                return;
            }
        }
        statusText.setText("Выберите сеть для подключения:");
    }

    @SuppressLint("MissingPermission")
    private void renderScanResults(@Nullable List<ScanResult> rawResults) {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        if (!wifiManager.isWifiEnabled()) {
            return;
        }

        if (rawResults == null || rawResults.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Сети не найдены. Нажмите «Обновить» или проверьте разрешение Геолокации.");
            empty.setTextColor(0xFF90A4AE);
            empty.setPadding(dp(8), dp(16), dp(8), dp(16));
            listContainer.addView(empty);
            return;
        }
        // Сортируем по уровню сигнала и убираем дубликаты SSID (оставляем самый сильный)
        List<ScanResult> sorted = new ArrayList<>(rawResults);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.level, a.level));
        Map<String, ScanResult> unique = new LinkedHashMap<>();
        for (ScanResult sr : sorted) {
            if (TextUtils.isEmpty(sr.SSID)) continue;
            if (!unique.containsKey(sr.SSID)) {
                unique.put(sr.SSID, sr);
            }
        }

        String connectedSsid = "";
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info != null && info.getNetworkId() != -1 && info.getSSID() != null) {
            connectedSsid = info.getSSID().replace("\"", "");
        }

        for (ScanResult sr : unique.values()) {
            boolean isConnected = sr.SSID.equals(connectedSsid);
            boolean isSecured = isSecured(sr.capabilities);
            int bars = WifiManager.calculateSignalLevel(sr.level, 4);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(isConnected ? 0xFF1B3A2E : 0xFF282D37);
            rowBg.setCornerRadius(dp(10));
            row.setBackground(rowBg);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(8);

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

            TextView nameTv = new TextView(context);
            nameTv.setText(sr.SSID);
            nameTv.setTextColor(isConnected ? 0xFF69F0AE : Color.WHITE);
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            nameTv.setTypeface(isConnected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            textCol.addView(nameTv);

            TextView subTv = new TextView(context);
            String secLabel = isSecured ? "Защищена" : "Открытая";
            subTv.setText((isConnected ? "Подключено · " : "") + secLabel + " · Сигнал " + (bars + 1) + "/4");
            subTv.setTextColor(0xFF90A4AE);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            textCol.addView(subTv);

            row.addView(textCol, colLp);

            if (isSecured) {
                TextView lockTv = new TextView(context);
                lockTv.setText("🔒");
                lockTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                row.addView(lockTv);
            }

            row.setOnClickListener(v -> onNetworkClicked(sr, isConnected, isSecured));
            listContainer.addView(row, rowLp);
        }
    }

    @SuppressLint("MissingPermission")
    private void onNetworkClicked(@NonNull ScanResult sr, boolean isConnected, boolean isSecured) {
        if (isConnected) {
            wifiManager.disconnect();
            Toast.makeText(context, "Отключено от " + sr.SSID, Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::updateStatusHeader, 600L);
            return;
        }

        // Проверяем, сохранена ли уже эта сеть в системе
        List<WifiConfiguration> configured = wifiManager.getConfiguredNetworks();
        if (configured != null) {
            String quoted = "\"" + sr.SSID + "\"";
            for (WifiConfiguration cfg : configured) {
                if (quoted.equals(cfg.SSID)) {
                    wifiManager.disconnect();
                    wifiManager.enableNetwork(cfg.networkId, true);
                    wifiManager.reconnect();
                    Toast.makeText(context, "Подключение к " + sr.SSID + "...", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(this::updateStatusHeader, 1500L);
                    return;
                }
            }
        }

        if (!isSecured) {
            connectToNetwork(sr.SSID, null);
        } else {
            showPasswordPrompt(sr.SSID);
        }
    }

    private void showPasswordPrompt(@NonNull String ssid) {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        TextView promptTitle = new TextView(context);
        promptTitle.setText("Пароль для сети «" + ssid + "»:");
        promptTitle.setTextColor(Color.WHITE);
        promptTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        promptTitle.setPadding(0, dp(4), 0, dp(10));
        listContainer.addView(promptTitle);

        EditText passInput = new EditText(context);
        passInput.setHint("Введите пароль (минимум 8 символов)");
        passInput.setHintTextColor(0xFF78909C);
        passInput.setTextColor(Color.WHITE);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setPadding(dp(12), dp(12), dp(12), dp(12));

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFF282D37);
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), 0xFF4F5B6E);
        passInput.setBackground(inputBg);
        listContainer.addView(passInput);

        CheckBox showPass = new CheckBox(context);
        showPass.setText("Показать пароль");
        showPass.setTextColor(0xFFB0BEC5);
        showPass.setOnCheckedChangeListener((btn, checked) -> {
            int sel = passInput.getSelectionEnd();
            passInput.setInputType(checked
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            if (sel >= 0) passInput.setSelection(sel);
        });
        listContainer.addView(showPass);

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(14), 0, 0);

        Button cancelBtn = makeActionButton("Отмена", 0xFF37474F);
        cancelBtn.setOnClickListener(v -> triggerScan());
        btnRow.addView(cancelBtn);

        Button connectBtn = makeActionButton("Подключиться", 0xFF1E88E5);
        LinearLayout.LayoutParams connLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        connLp.leftMargin = dp(10);
        connectBtn.setOnClickListener(v -> {
            String pwd = passInput.getText().toString();
            if (pwd.length() < 8) {
                Toast.makeText(context, "Пароль должен быть не короче 8 символов", Toast.LENGTH_SHORT).show();
                return;
            }
            connectToNetwork(ssid, pwd);
            triggerScan();
        });
        btnRow.addView(connectBtn, connLp);

        listContainer.addView(btnRow);
        passInput.requestFocus();
    }

    @SuppressLint("MissingPermission")
    private void connectToNetwork(@NonNull String ssid, @Nullable String password) {
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + ssid + "\"";
        if (TextUtils.isEmpty(password)) {
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            conf.preSharedKey = "\"" + password + "\"";
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        int netId = wifiManager.addNetwork(conf);
        if (netId != -1) {
            wifiManager.disconnect();
            wifiManager.enableNetwork(netId, true);
            wifiManager.reconnect();
            Toast.makeText(context, "Подключение к " + ssid + "...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, "Не удалось сохранить сеть " + ssid, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isSecured(@Nullable String capabilities) {
        if (capabilities == null) return false;
        return capabilities.contains("WEP") || capabilities.contains("PSK") || capabilities.contains("EAP");
    }

    private Button makeActionButton(@NonNull String label, int bgColor) {
        Button btn = new Button(context);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        return btn;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }
}