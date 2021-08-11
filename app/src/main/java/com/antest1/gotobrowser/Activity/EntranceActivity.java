package com.antest1.gotobrowser.Activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.antest1.gotobrowser.Browser.WebViewManager;
import com.antest1.gotobrowser.BuildConfig;
import com.antest1.gotobrowser.Helpers.BackPressCloseHandler;
import com.antest1.gotobrowser.Helpers.KcUtils;
import com.antest1.gotobrowser.Helpers.VersionDatabase;
import com.antest1.gotobrowser.R;
import com.antest1.gotobrowser.Subtitle.Kc3SubtitleCheck;

import java.io.File;
import java.util.List;
import java.util.Locale;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import static com.antest1.gotobrowser.Constants.ACTION_SHOWKEYBOARD;
import static com.antest1.gotobrowser.Constants.ACTION_SHOWPANEL;
import static com.antest1.gotobrowser.Constants.CONN_DMM;
import static com.antest1.gotobrowser.Constants.GITHUBAPI_ROOT;
import static com.antest1.gotobrowser.Constants.PREF_ADJUSTMENT;
import static com.antest1.gotobrowser.Constants.PREF_ALTER_ENDPOINT;
import static com.antest1.gotobrowser.Constants.PREF_ALTER_GADGET;
import static com.antest1.gotobrowser.Constants.PREF_ALTER_METHOD;
import static com.antest1.gotobrowser.Constants.PREF_ALTER_METHOD_PROXY;
import static com.antest1.gotobrowser.Constants.PREF_CONNECTOR;
import static com.antest1.gotobrowser.Constants.PREF_DMM_ID;
import static com.antest1.gotobrowser.Constants.PREF_DMM_PASS;
import static com.antest1.gotobrowser.Constants.PREF_KEYBOARD;
import static com.antest1.gotobrowser.Constants.PREF_LANDSCAPE;
import static com.antest1.gotobrowser.Constants.PREF_LATEST_URL;
import static com.antest1.gotobrowser.Constants.PREF_BROADCAST;
import static com.antest1.gotobrowser.Constants.PREF_PANELSTART;
import static com.antest1.gotobrowser.Constants.PREF_SILENT;
import static com.antest1.gotobrowser.Constants.PREF_TP_DISCLAIMED;
import static com.antest1.gotobrowser.Constants.URL_LIST;
import static com.antest1.gotobrowser.Constants.VERSION_TABLE_VERSION;
import static com.antest1.gotobrowser.Helpers.KcUtils.clearApplicationCache;
import static com.antest1.gotobrowser.Helpers.KcUtils.getRetrofitAdapter;

public class EntranceActivity extends AppCompatActivity {
    private BackPressCloseHandler backPressCloseHandler;
    private SharedPreferences sharedPref;
    private TextView selectButton;
    private VersionDatabase versionTable;
    private Kc3SubtitleCheck updateCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isTaskRoot()) finish();
        setContentView(R.layout.activity_entrance);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.hide();

        updateCheck = getRetrofitAdapter(getApplicationContext(), GITHUBAPI_ROOT).create(Kc3SubtitleCheck.class);
        KcUtils.requestLatestAppVersion(this, updateCheck, true);

        versionTable = new VersionDatabase(getApplicationContext(), null, VERSION_TABLE_VERSION);
        backPressCloseHandler = new BackPressCloseHandler(this);
        sharedPref = getSharedPreferences(getString(R.string.preference_key), Context.MODE_PRIVATE);
        SettingsActivity.setInitialSettings(sharedPref);

        SharedPreferences.Editor editor = sharedPref.edit();

        ImageView settingsButton = findViewById(R.id.icon_setting);
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(EntranceActivity.this, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        ImageView msgButton = findViewById(R.id.icon_msg);
        msgButton.setOnClickListener(v -> {
            String url = "http://luckyjervis.com/noti.html";
            CustomTabsIntent.Builder intentBuilder = new CustomTabsIntent.Builder();
            intentBuilder.setShowTitle(true);
            intentBuilder.setToolbarColor(ContextCompat.getColor(getApplicationContext(), R.color.colorSettingsBackground));
            intentBuilder.enableUrlBarHiding();

            final CustomTabsIntent customTabsIntent = intentBuilder.build();
            final List<ResolveInfo> customTabsApps = getPackageManager().queryIntentActivities(customTabsIntent.intent, 0);
            if (customTabsApps.size() > 0) {
                customTabsIntent.launchUrl(EntranceActivity.this, Uri.parse(url));
            } else {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
            }
        });

        Switch landscapeSwitch = findViewById(R.id.switch_landscape);
        landscapeSwitch.setChecked(sharedPref.getBoolean(PREF_LANDSCAPE, false));
        landscapeSwitch.setOnCheckedChangeListener((buttonView, isChecked)
                -> editor.putBoolean(PREF_LANDSCAPE, isChecked).apply());

        Switch adjustmentSwitch = findViewById(R.id.switch_adjustment);
        adjustmentSwitch.setChecked(sharedPref.getBoolean(PREF_ADJUSTMENT, false));
        adjustmentSwitch.setOnCheckedChangeListener((buttonView, isChecked)
                -> editor.putBoolean(PREF_ADJUSTMENT, isChecked).apply());

        Switch silentSwitch = findViewById(R.id.switch_silent);
        silentSwitch.setChecked(sharedPref.getBoolean(PREF_SILENT, false));
        silentSwitch.setOnCheckedChangeListener((buttonView, isChecked)
                -> editor.putBoolean(PREF_SILENT, isChecked).apply());

        Switch broadcastSwitch = findViewById(R.id.switch_broadcast);
        broadcastSwitch.setChecked(sharedPref.getBoolean(PREF_BROADCAST, false));
        broadcastSwitch.setOnCheckedChangeListener((buttonView, isChecked)
                -> editor.putBoolean(PREF_BROADCAST, isChecked).apply()
        );

        CheckBox showControlPanelCheckbox = findViewById(R.id.layout_control);
        showControlPanelCheckbox.setChecked(sharedPref.getBoolean(PREF_PANELSTART, false));
        showControlPanelCheckbox.setOnCheckedChangeListener((buttonView, isChecked)
                -> editor.putBoolean(PREF_PANELSTART, isChecked).apply());

        CheckBox showKeyboardCheckbox = findViewById(R.id.layout_keyboard);
        showKeyboardCheckbox.setChecked(sharedPref.getBoolean(PREF_KEYBOARD, false));
        showKeyboardCheckbox.setOnCheckedChangeListener((buttonView, isChecked)
                -> editor.putBoolean(PREF_KEYBOARD, isChecked).apply());

        selectButton = findViewById(R.id.connector_select);
        selectButton.setOnClickListener(v -> showConnectorSelectionDialog());
        String connector = sharedPref.getString(PREF_CONNECTOR, null);
        if (connector != null) {
            selectButton.setText(connector);
        } else {
            selectButton.setText(getString(R.string.select_server));
        }

        TextView autoCompleteButton = findViewById(R.id.webview_autocomplete);
        autoCompleteButton.setOnClickListener(v -> showAutoCompleteDialog());

        TextView clearButton = findViewById(R.id.webview_clear);
        clearButton.setOnClickListener(v -> showCacheClearDialog());

        TextView startButton = findViewById(R.id.webview_start);
        startButton.setOnClickListener(v -> {
            String pref_connector = sharedPref.getString(PREF_CONNECTOR, null);
            if (pref_connector != null) {
                if (pref_connector.equals(CONN_DMM)) {
                    startBrowserActivity();
                } else {
                    showThirdPartyConnectorDialog();
                }
            }

        });

        TextView versionText = findViewById(R.id.version_info);
        versionText.setText(String.format(Locale.US, getString(R.string.version_format), BuildConfig.VERSION_NAME));

        WebViewManager.clearKcCacheProxy();
    }

    @Override
    public void onBackPressed() {
        backPressCloseHandler.onBackPressed();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void showConnectorSelectionDialog() {
        final String[] listItems = getResources().getStringArray(R.array.connector_list);
        int connector_idx = -1;
        String connector1 = sharedPref.getString(PREF_CONNECTOR, null);
        for (int i = 0; i < listItems.length; i++) {
            if (listItems[i].equals(connector1)) {
                connector_idx = i;
                break;
            }
        }
        AlertDialog.Builder mBuilder = new AlertDialog.Builder(EntranceActivity.this);
        mBuilder.setTitle(getString(R.string.select_server));
        mBuilder.setSingleChoiceItems(listItems, connector_idx, (dialogInterface, i) -> {
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putString(PREF_CONNECTOR, listItems[i]);
            editor.putString(PREF_LATEST_URL, URL_LIST[i]);
            editor.apply();
            selectButton.setText(listItems[i]);
            KcUtils.showToast(getApplicationContext(), URL_LIST[i]);
            dialogInterface.dismiss();
        });
        AlertDialog mDialog = mBuilder.create();
        mDialog.show();
    }

    private void showAutoCompleteDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(EntranceActivity.this);
        View dialogView = getLayoutInflater().inflate(R.layout.login_form, null);
        final EditText formEmail = dialogView.findViewById(R.id.input_id);
        final EditText formPassword = dialogView.findViewById(R.id.input_pw);
        formEmail.setText(sharedPref.getString(PREF_DMM_ID, ""));
        formPassword.setText(sharedPref.getString(PREF_DMM_PASS, ""));
        builder.setView(dialogView);
        builder.setPositiveButton(R.string.text_save, (dialog, which) -> {
            String login_id = formEmail.getText().toString();
            String login_password = formPassword.getText().toString();
            sharedPref.edit().putString(PREF_DMM_ID, login_id).apply();
            sharedPref.edit().putString(PREF_DMM_PASS, login_password).apply();
            dialog.dismiss();
        });
        builder.setNegativeButton(R.string.text_cancel, (dialog, which) -> dialog.cancel());
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void showCacheClearDialog() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(EntranceActivity.this);
        alertDialogBuilder.setTitle(R.string.cache_clear_text);
        alertDialogBuilder
                .setCancelable(false)
                .setMessage(getString(R.string.clearcache_msg))
                .setPositiveButton(R.string.action_ok,
                        (dialog, id) -> {
                            clearBrowserCache();
                            KcUtils.showToast(getApplicationContext(), R.string.cache_cleared_toast);
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.action_cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private void clearBrowserCache() {
        WebView webview = new WebView(getApplicationContext());
        webview.clearCache(true);
        versionTable.clearVersionDatabase();
        String cache_dir = KcUtils.getAppCacheFileDir(getApplicationContext(), "/cache/");
        clearApplicationCache(getApplicationContext(), getCacheDir());
        clearApplicationCache(getApplicationContext(), new File(cache_dir));
    }

    private void startBrowserActivity() {
        String pref_connector = sharedPref.getString(PREF_CONNECTOR, null);
        if (pref_connector == null) {
            KcUtils.showToast(getApplicationContext(), R.string.select_server_toast);
        } else {
            Intent intent = new Intent(EntranceActivity.this, BrowserActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(WebViewManager.OPEN_KANCOLLE);

            String options = "";
            CheckBox showControlPanelCheckbox = findViewById(R.id.layout_control);
            CheckBox showKeyboardCheckbox = findViewById(R.id.layout_keyboard);
            if (showControlPanelCheckbox.isChecked()) options = options.concat(ACTION_SHOWPANEL);
            if (showKeyboardCheckbox.isChecked()) options = options.concat(ACTION_SHOWKEYBOARD);
            intent.putExtra("options", options);

            String login_id = sharedPref.getString(PREF_DMM_ID, "");
            String login_password = sharedPref.getString(PREF_DMM_PASS, "");
            intent.putExtra("login_id", login_id);
            intent.putExtra("login_pw", login_password);

            boolean prefAlterGadget = sharedPref.getBoolean(PREF_ALTER_GADGET, false);
            boolean isProxyMethod = sharedPref.getString(PREF_ALTER_METHOD, "").equals(PREF_ALTER_METHOD_PROXY);
            String alterEndpoint = sharedPref.getString(PREF_ALTER_ENDPOINT, "");

            if (prefAlterGadget && isProxyMethod && pref_connector.equals(CONN_DMM)) {
                WebViewManager.setKcCacheProxy(alterEndpoint, () -> {
                    startActivity(intent);
                    finish();
                });
            } else {
                startActivity(intent);
                finish();
            }
        }
    }

    private void showThirdPartyConnectorDialog() {
        boolean disclaimed = sharedPref.getBoolean(PREF_TP_DISCLAIMED, false);
        if (disclaimed) {
            startBrowserActivity();
            return;
        }

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(EntranceActivity.this);
        alertDialogBuilder.setTitle("Disclaimer");
        alertDialogBuilder
                .setCancelable(false)
                .setMessage(getString(R.string.thirdpartyconnector_msg))
                .setPositiveButton(R.string.action_ok,
                        (dialog, id) -> {
                            sharedPref.edit().putBoolean(PREF_TP_DISCLAIMED, true).apply();
                            dialog.dismiss();
                            startBrowserActivity();
                        })
                .setNegativeButton(R.string.action_cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }
}
