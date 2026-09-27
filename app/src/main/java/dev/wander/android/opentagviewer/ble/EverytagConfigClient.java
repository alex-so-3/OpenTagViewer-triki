package dev.wander.android.opentagviewer.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client for the Everytag-compatible configuration service that DIY OpenHaystack / Google Find
 * Hub tags expose (<a href="https://github.com/vasimv/Everytag">Everytag</a>): a password write,
 * then 4-byte little-endian settings. Some firmwares add a readable info characteristic (e9)
 * with the current values; without it the settings can be written but not read back.
 *
 * <p>All methods block: call them from a background thread.
 */
@SuppressLint("MissingPermission")   // callers check BlePermissions first
public final class EverytagConfigClient implements AutoCloseable {
    public static final UUID SERVICE = UUID.fromString("5cfce313-a7e3-45c3-933d-418b8100da7f");
    public static final int AUTH = 0xbdf, PERIOD = 0xbdd, FMDN_ON = 0xbdb, APPLE_ON = 0xbdc,
            STILL = 0xbe0, TXPOWER = 0xbe1, MOTION = 0xbe6, DFU = 0xbe7, INFO = 0xbe9;
    /** Nordic Secure DFU service and its Buttonless (no bonds) characteristic. */
    public static final UUID DFU_SERVICE = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb");
    public static final UUID BUTTONLESS = UUID.fromString("8ec90003-f315-4f60-9fb8-838830daea50");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long OP_TIMEOUT_S = 10;

    public static UUID chr(int shortId) {
        return UUID.fromString(String.format("8c5de%03x-ad8d-4810-a31f-53862e79ee77", shortId));
    }

    /** Values read from the info characteristic (e9). */
    public static final class Info {
        public int appVersion, batteryMv, stillTimeoutS, motionThresholdMg, period, txPower;
        public long statusFlags;
        public boolean appleEnabled, fmdnEnabled, moving, imuOk;
    }

    private final Context context;
    private BluetoothGatt gatt;
    private BluetoothGattService service;
    private volatile CountDownLatch opLatch;
    private volatile int opStatus;
    private volatile byte[] opValue;
    private final CountDownLatch disconnected = new CountDownLatch(1);

    public EverytagConfigClient(Context context) {
        this.context = context.getApplicationContext();
    }

    private BluetoothLeScanner scanner() throws IOException {
        BluetoothManager m = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = m == null ? null : m.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth is off");
        return adapter.getBluetoothLeScanner();
    }

    private BluetoothDevice scan(BluetoothLeScanner scanner, java.util.List<ScanFilter> filters, long timeoutS)
            throws IOException, InterruptedException {
        AtomicReference<BluetoothDevice> found = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                found.compareAndSet(null, result.getDevice());
                latch.countDown();
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(filters, settings, cb);
        try {
            latch.await(timeoutS, TimeUnit.SECONDS);
        } finally {
            scanner.stopScan(cb);
        }
        if (found.get() == null) throw new IOException("Device not found nearby");
        return found.get();
    }

    /** Finds the device advertising at any of these addresses (its current key's MACs). */
    public BluetoothDevice findByAddress(Collection<String> macs, long timeoutS) throws IOException, InterruptedException {
        BluetoothLeScanner scanner = scanner();
        java.util.List<ScanFilter> filters = new java.util.ArrayList<>();
        for (String mac : macs) filters.add(new ScanFilter.Builder().setDeviceAddress(mac.toUpperCase(java.util.Locale.ROOT)).build());
        if (filters.isEmpty()) throw new IOException("No address known for this device");
        return scan(scanner, filters, timeoutS);
    }

    /**
     * A Nordic Secure DFU bootloader nearby: it advertises the DFU service 0xFE59. Used after a
     * Buttonless DFU jump, when the bootloader comes up under an address of its own.
     */
    public BluetoothDevice findDfuBootloader(long timeoutS) throws IOException, InterruptedException {
        return scan(scanner(), Collections.singletonList(new ScanFilter.Builder()
                .setServiceUuid(new android.os.ParcelUuid(DFU_SERVICE)).build()), timeoutS);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                finish(status, null);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected.countDown();
                finish(status == BluetoothGatt.GATT_SUCCESS ? -1 : status, null);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            finish(status, null);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            finish(status, null);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            finish(status, value);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, android.bluetooth.BluetoothGattDescriptor d, int status) {
            finish(status, null);
        }
    };

    private void finish(int status, byte[] value) {
        opStatus = status;
        opValue = value;
        CountDownLatch l = opLatch;
        if (l != null) l.countDown();
    }

    private void await(String what) throws IOException, InterruptedException {
        if (!opLatch.await(OP_TIMEOUT_S, TimeUnit.SECONDS)) throw new IOException(what + ": timeout");
        if (opStatus != BluetoothGatt.GATT_SUCCESS) throw new IOException(what + ": error " + opStatus);
    }

    /** Connects, discovers the service and authenticates with the tag's password. */
    public void connect(BluetoothDevice device, String password) throws IOException, InterruptedException {
        opLatch = new CountDownLatch(1);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        await("connect");
        opLatch = new CountDownLatch(1);
        gatt.discoverServices();
        await("discover");
        service = gatt.getService(SERVICE);
        if (service == null) throw new IOException("This device has no Everytag configuration service");
        write(AUTH, password.getBytes(StandardCharsets.US_ASCII));
    }

    /** Connects without authenticating, e.g. only for the standard DFU service. */
    public void connectOnly(BluetoothDevice device) throws IOException, InterruptedException {
        opLatch = new CountDownLatch(1);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        await("connect");
        opLatch = new CountDownLatch(1);
        gatt.discoverServices();
        await("discover");
        service = gatt.getService(SERVICE);
    }

    /**
     * Asks the device to restart into its DFU bootloader through Nordic's standard Buttonless
     * DFU service (0x01 = enter bootloader). Falls back to Everytag's own DFU switch (e7) when
     * only that exists.
     *
     * @return false when the device offers neither
     */
    public boolean enterBootloader() throws IOException, InterruptedException {
        BluetoothGattService dfu = gatt.getService(DFU_SERVICE);
        BluetoothGattCharacteristic buttonless = dfu == null ? null : dfu.getCharacteristic(BUTTONLESS);
        if (buttonless != null) {
            gatt.setCharacteristicNotification(buttonless, true);
            android.bluetooth.BluetoothGattDescriptor cccd = buttonless.getDescriptor(CCCD);
            if (cccd != null) {
                opLatch = new CountDownLatch(1);
                gatt.writeDescriptor(cccd, android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                await("enable indications");
            }
            opLatch = new CountDownLatch(1);
            gatt.writeCharacteristic(buttonless, new byte[]{0x01}, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            await("enter bootloader");
            return true;
        }
        if (service != null && service.getCharacteristic(chr(DFU)) != null) {
            write(DFU, new byte[]{1});
            return true;
        }
        return false;
    }

    /** Whether the device has the optional info characteristic (current values readable). */
    public boolean hasInfo() {
        return service != null && service.getCharacteristic(chr(INFO)) != null;
    }

    public void write(int shortId, byte[] value) throws IOException, InterruptedException {
        BluetoothGattCharacteristic c = service.getCharacteristic(chr(shortId));
        if (c == null) throw new IOException("Characteristic " + Integer.toHexString(shortId) + " missing (old firmware?)");
        opLatch = new CountDownLatch(1);
        gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        await("write " + Integer.toHexString(shortId));
    }

    public void writeU32(int shortId, long value) throws IOException, InterruptedException {
        write(shortId, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array());
    }

    public byte[] read(int shortId) throws IOException, InterruptedException {
        BluetoothGattCharacteristic c = service.getCharacteristic(chr(shortId));
        if (c == null) throw new IOException("Characteristic " + Integer.toHexString(shortId) + " missing (old firmware?)");
        opLatch = new CountDownLatch(1);
        gatt.readCharacteristic(c);
        try {
            await("read " + Integer.toHexString(shortId));
        } catch (IOException e) {
            // The tag answers "read not permitted" when the password was wrong
            if (opStatus == BluetoothGatt.GATT_READ_NOT_PERMITTED) throw new IOException("Wrong password");
            throw e;
        }
        return opValue;
    }

    /** Also verifies the password: the tag refuses this read without it. */
    public Info info() throws IOException, InterruptedException {
        ByteBuffer b = ByteBuffer.wrap(read(INFO)).order(ByteOrder.LITTLE_ENDIAN);
        Info i = new Info();
        b.get();   // layout version
        i.appVersion = b.getShort() & 0xffff;
        i.batteryMv = b.getShort() & 0xffff;
        i.stillTimeoutS = b.getShort() & 0xffff;
        i.motionThresholdMg = b.getShort() & 0xffff;
        i.statusFlags = b.getInt() & 0xffffffffL;
        i.period = b.get();
        i.txPower = b.get();
        i.appleEnabled = b.get() != 0;
        i.fmdnEnabled = b.get() != 0;
        i.moving = b.get() != 0;
        i.imuOk = b.get() != 0;
        return i;
    }

    /** Disconnects and waits for the link to drop (the tag applies settings then). */
    @Override
    public void close() {
        if (gatt == null) return;
        gatt.disconnect();
        try {
            disconnected.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        gatt.close();
        gatt = null;
    }
}
