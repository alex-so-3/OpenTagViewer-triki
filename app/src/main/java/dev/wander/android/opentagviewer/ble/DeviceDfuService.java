package dev.wander.android.opentagviewer.ble;

import android.app.Activity;

import dev.wander.android.opentagviewer.DeviceSettingsActivity;
import no.nordicsemi.android.dfu.DfuBaseService;

/** Nordic Secure DFU over Bluetooth, for devices updated from {@link DeviceSettingsActivity}. */
public class DeviceDfuService extends DfuBaseService {
    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return DeviceSettingsActivity.class;
    }
}
