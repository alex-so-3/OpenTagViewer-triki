package dev.wander.android.opentagviewer;

import android.bluetooth.BluetoothDevice;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.DeviceDfuService;
import dev.wander.android.opentagviewer.ble.EverytagConfigClient;
import dev.wander.android.opentagviewer.databinding.ActivityDeviceSettingsBinding;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.source.SourceLinks;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

/**
 * Settings kept on the device itself, over Bluetooth:
 * <ul>
 *   <li>Everytag-compatible configuration (advertising interval, power, motion, networks,
 *   password) - see {@link EverytagConfigClient};</li>
 *   <li>a firmware update from a file, with Nordic's standard Secure DFU: the device is asked to
 *   restart into its bootloader (Buttonless DFU) and the package is sent to it.</li>
 * </ul>
 * The device is found by the addresses its current key advertises under, like "Play sound".
 */
public class DeviceSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_BEACON_ID = "beaconId";
    public static final String EXTRA_ACCESSORY_JSON = "accessoryJson";
    public static final String EXTRA_NAME = "name";

    private static final int[] INTERVALS = {1, 2, 4, 8};
    private static final long FIND_TIMEOUT_S = 45;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ActivityDeviceSettingsBinding binding;
    private SourceLinks links;
    private String beaconId;
    private String accessoryJson;
    private EverytagConfigClient.Info info;
    private Runnable afterPermission;

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                if (BlePermissions.granted(this) && this.afterPermission != null) this.afterPermission.run();
                this.afterPermission = null;
            });

    private final ActivityResultLauncher<String[]> pickFirmware = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::startFirmwareUpdate);

    private final DfuProgressListenerAdapter dfuListener = new DfuProgressListenerAdapter() {
        @Override
        public void onProgressChanged(final String address, final int percent, final float speed,
                                      final float avgSpeed, final int currentPart, final int partsTotal) {
            binding.deviceSettingsProgress.setIndeterminate(false);
            binding.deviceSettingsProgress.setProgressCompat(percent, true);
            status(getString(R.string.firmware_update_progress, percent));
        }

        @Override
        public void onDfuCompleted(final String address) {
            done(getString(R.string.firmware_update_done));
        }

        @Override
        public void onDfuAborted(final String address) {
            done(getString(R.string.firmware_update_aborted));
        }

        @Override
        public void onError(final String address, final int error, final int errorType, final String message) {
            done(getString(R.string.device_settings_error, message));
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.beaconId = this.getIntent().getStringExtra(EXTRA_BEACON_ID);
        this.accessoryJson = this.getIntent().getStringExtra(EXTRA_ACCESSORY_JSON);
        this.links = new SourceLinks(this);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_device_settings);
        WindowPaddingUtil.insetForSystemBarsAndKeyboard(this.binding.getRoot());
        this.binding.setHandleClickBack(this::finish);
        final String name = this.getIntent().getStringExtra(EXTRA_NAME);
        this.binding.setPageTitle(name == null ? getString(R.string.device_settings) : name);
        if (this.getSupportActionBar() != null) this.getSupportActionBar().hide();

        if (this.beaconId == null || this.accessoryJson == null) {
            // Opened from the firmware-update notification: only the progress matters
            this.binding.deviceSettingsRead.setEnabled(false);
            this.binding.deviceSettingsSave.setEnabled(false);
            this.binding.deviceSettingsFirmware.setEnabled(false);
        }

        this.binding.deviceSettingsPassword.setText(
                this.beaconId == null ? "" : this.links.configPassword(this.beaconId));
        this.binding.deviceSettingsInterval.setSimpleItems(getResources().getStringArray(R.array.advertising_intervals));
        this.binding.deviceSettingsTxPower.setSimpleItems(new String[]{"-8 dBm", "0 dBm", "+4 dBm"});
        this.status(getString(R.string.device_settings_hint));

        this.binding.deviceSettingsRead.setOnClickListener(v -> this.withBluetooth(this::read));
        this.binding.deviceSettingsSave.setOnClickListener(v -> this.withBluetooth(this::write));
        this.binding.deviceSettingsFirmware.setOnClickListener(v -> this.withBluetooth(() ->
                this.pickFirmware.launch(new String[]{"application/zip", "application/octet-stream"})));

        DfuServiceListenerHelper.registerProgressListener(this, this.dfuListener);
    }

    @Override
    protected void onDestroy() {
        DfuServiceListenerHelper.unregisterProgressListener(this, this.dfuListener);
        this.io.shutdownNow();
        super.onDestroy();
    }

    private void withBluetooth(final Runnable action) {
        if (BlePermissions.granted(this)) {
            action.run();
        } else {
            this.afterPermission = action;
            this.permissionLauncher.launch(BlePermissions.required());
        }
    }

    private void status(final String text) {
        this.binding.deviceSettingsStatus.setText(text);
    }

    private void busy(final String text) {
        this.status(text);
        this.binding.deviceSettingsProgress.setIndeterminate(true);
        this.binding.deviceSettingsProgress.setVisibility(View.VISIBLE);
        this.binding.deviceSettingsRead.setEnabled(false);
        this.binding.deviceSettingsSave.setEnabled(false);
        this.binding.deviceSettingsFirmware.setEnabled(false);
    }

    private void done(final String text) {
        runOnUiThread(() -> {
            this.status(text);
            this.binding.deviceSettingsProgress.setVisibility(View.GONE);
            this.binding.deviceSettingsRead.setEnabled(true);
            this.binding.deviceSettingsSave.setEnabled(true);
            this.binding.deviceSettingsFirmware.setEnabled(true);
        });
    }

    private String password() {
        return String.valueOf(this.binding.deviceSettingsPassword.getText());
    }

    /** Finds the device by its current addresses and connects. */
    private EverytagConfigClient connect(final boolean authenticate) throws Exception {
        final Set<String> macs = AppDependencies.accessoryMacResolver().currentMacAddresses(this.accessoryJson).keySet();
        final EverytagConfigClient client = new EverytagConfigClient(this);
        final BluetoothDevice device = client.findByAddress(macs, FIND_TIMEOUT_S);
        if (authenticate) client.connect(device, this.password());
        else client.connectOnly(device);
        return client;
    }

    private void read() {
        this.busy(getString(R.string.device_settings_searching));
        this.io.execute(() -> {
            try (EverytagConfigClient client = this.connect(true)) {
                if (!client.hasInfo()) {
                    this.links.setConfigPassword(this.beaconId, this.password());
                    this.done(getString(R.string.device_settings_no_info));
                    return;
                }
                final EverytagConfigClient.Info i = client.info();
                this.links.setConfigPassword(this.beaconId, this.password());
                runOnUiThread(() -> this.show(i));
            } catch (final Exception e) {
                this.done(getString(R.string.device_settings_error, e.getMessage()));
            }
        });
    }

    private void show(final EverytagConfigClient.Info i) {
        this.info = i;
        final String[] intervals = getResources().getStringArray(R.array.advertising_intervals);
        for (int k = 0; k < INTERVALS.length; k++) {
            if (INTERVALS[k] == i.period) this.binding.deviceSettingsInterval.setText(intervals[k], false);
        }
        this.binding.deviceSettingsTxPower.setText(
                new String[]{"-8 dBm", "0 dBm", "+4 dBm"}[Math.max(0, Math.min(2, i.txPower))], false);
        this.binding.deviceSettingsStillTimeout.setText(String.valueOf(i.stillTimeoutS));
        this.binding.deviceSettingsMotion.setText(String.valueOf(i.motionThresholdMg));
        this.binding.deviceSettingsApple.setChecked(i.appleEnabled);
        this.binding.deviceSettingsGoogle.setChecked(i.fmdnEnabled);
        this.done(getString(R.string.device_settings_info, i.appVersion, i.batteryMv / 1000.0,
                getString(i.moving ? R.string.device_moving : R.string.device_still)));
    }

    private static Integer parse(final CharSequence text) {
        try {
            return Integer.parseInt(String.valueOf(text).trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private int indexOf(final String[] items, final String value) {
        for (int k = 0; k < items.length; k++) if (items[k].equals(value)) return k;
        return -1;
    }

    /**
     * Writes what was changed (or, without info read back, every field that is filled in). The
     * device saves the settings and restarts once the connection closes.
     */
    private void write() {
        final String[] intervals = getResources().getStringArray(R.array.advertising_intervals);
        final int intervalIndex = this.indexOf(intervals, String.valueOf(this.binding.deviceSettingsInterval.getText()));
        final int txIndex = this.indexOf(new String[]{"-8 dBm", "0 dBm", "+4 dBm"},
                String.valueOf(this.binding.deviceSettingsTxPower.getText()));
        final Integer still = parse(this.binding.deviceSettingsStillTimeout.getText());
        final Integer motion = parse(this.binding.deviceSettingsMotion.getText());
        final String newPassword = String.valueOf(this.binding.deviceSettingsNewPassword.getText());
        final boolean apple = this.binding.deviceSettingsApple.isChecked();
        final boolean google = this.binding.deviceSettingsGoogle.isChecked();

        if ((still != null && (still < 30 || still > 7200)) || (motion != null && (motion < 0 || motion >= 2000))) {
            Toast.makeText(this, R.string.device_settings_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        if (!newPassword.isEmpty() && newPassword.length() != 8) {
            Toast.makeText(this, R.string.device_password_length, Toast.LENGTH_LONG).show();
            return;
        }

        final EverytagConfigClient.Info before = this.info;
        this.busy(getString(R.string.device_settings_searching));
        this.io.execute(() -> {
            try (EverytagConfigClient client = this.connect(true)) {
                if (client.hasInfo()) client.info();   // fails early on a wrong password
                if (intervalIndex >= 0 && (before == null || INTERVALS[intervalIndex] != before.period)) {
                    client.writeU32(EverytagConfigClient.PERIOD, INTERVALS[intervalIndex]);
                }
                if (txIndex >= 0 && (before == null || txIndex != before.txPower)) {
                    client.writeU32(EverytagConfigClient.TXPOWER, txIndex);
                }
                if (still != null && (before == null || still != before.stillTimeoutS)) {
                    client.writeU32(EverytagConfigClient.STILL, still);
                }
                if (motion != null && (before == null || motion != before.motionThresholdMg)) {
                    client.writeU32(EverytagConfigClient.MOTION, motion);
                }
                if (before == null || apple != before.appleEnabled) {
                    client.writeU32(EverytagConfigClient.APPLE_ON, apple ? 1 : 0);
                }
                if (before == null || google != before.fmdnEnabled) {
                    client.writeU32(EverytagConfigClient.FMDN_ON, google ? 1 : 0);
                }
                if (!newPassword.isEmpty()) {
                    client.write(EverytagConfigClient.AUTH, newPassword.getBytes(StandardCharsets.US_ASCII));
                    this.links.setConfigPassword(this.beaconId, newPassword);
                    runOnUiThread(() -> {
                        this.binding.deviceSettingsPassword.setText(newPassword);
                        this.binding.deviceSettingsNewPassword.setText("");
                    });
                } else {
                    this.links.setConfigPassword(this.beaconId, this.password());
                }
                this.done(getString(R.string.device_settings_written));
            } catch (final Exception e) {
                this.done(getString(R.string.device_settings_error, e.getMessage()));
            }
        });
    }

    private void startFirmwareUpdate(final Uri zip) {
        if (zip == null) return;
        this.busy(getString(R.string.firmware_update_entering));
        this.io.execute(() -> {
            try {
                // The standard Buttonless DFU needs no password; Everytag's own switch does
                try (EverytagConfigClient client = this.connect(false)) {
                    if (!client.enterBootloader()) {
                        this.done(getString(R.string.firmware_update_unsupported));
                        return;
                    }
                }
                // The bootloader comes up under an address of its own, advertising the DFU service
                final BluetoothDevice bootloader = new EverytagConfigClient(this).findDfuBootloader(30);
                runOnUiThread(() -> {
                    this.status(getString(R.string.firmware_update_progress, 0));
                    DfuServiceInitiator.createDfuNotificationChannel(this);
                    new DfuServiceInitiator(bootloader.getAddress())
                            .setKeepBond(false)
                            .setForeground(true)
                            .setPacketsReceiptNotificationsEnabled(true)
                            .setPacketsReceiptNotificationsValue(10)
                            .setZip(zip)
                            .start(this, DeviceDfuService.class);
                });
            } catch (final Exception e) {
                this.done(getString(R.string.device_settings_error, e.getMessage()));
            }
        });
    }
}
