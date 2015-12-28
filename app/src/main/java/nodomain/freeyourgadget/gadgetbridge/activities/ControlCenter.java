package nodomain.freeyourgadget.gadgetbridge.activities;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.ChartsActivity;
import nodomain.freeyourgadget.gadgetbridge.adapter.GBDeviceAdapter;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class ControlCenter extends Activity {
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private static final Logger LOG = LoggerFactory.getLogger(ControlCenter.class);

    public static final String ACTION_REFRESH_DEVICELIST
            = "nodomain.freeyourgadget.gadgetbridge.controlcenter.action.set_version";

    private TextView hintTextView;
    private SwipeRefreshLayout swipeLayout;
    private GBDeviceAdapter mGBDeviceAdapter;
    private GBDevice selectedDevice = null;

    private final List<GBDevice> deviceList = new ArrayList<>();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case GBApplication.ACTION_QUIT:
                    finish();
                    break;
                case ACTION_REFRESH_DEVICELIST:
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    try {
                        refreshPairedDevices();
                    }catch(Exception e)
                    {
                        LOG.error("Error: "+ e.getMessage());
                    }
                    break;
                case GBDevice.ACTION_DEVICE_CHANGED:
                    GBDevice dev = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                    if (dev.getAddress() != null) {
                        int index = deviceList.indexOf(dev); // search by address
                        if (index >= 0) {
                            deviceList.set(index, dev);
                        } else {
                            deviceList.add(dev);
                        }
                    }
                    updateSelectedDevice(dev);
                    refreshPairedDevices();

                    refreshBusyState(dev);
                    enableSwipeRefresh(selectedDevice);
                    break;
            }
        }
    };

    private void updateSelectedDevice(GBDevice dev) {
        if (selectedDevice == null) {
            selectedDevice = dev;
        } else {
            if (!selectedDevice.equals(dev)) {
                if (selectedDevice.isConnected() && dev.isConnected()) {
                    LOG.warn("multiple connected devices -- this is currently not really supported");
                    selectedDevice = dev; // use the last one that changed
                }
                if (!selectedDevice.isConnected()) {
                    selectedDevice = dev; // use the last one that changed
                }
            }
        }
    }

    private void refreshBusyState(GBDevice dev) {
        if (dev.isBusy()) {
            swipeLayout.setRefreshing(true);
        } else {
            boolean wasBusy = swipeLayout.isRefreshing();
            if (wasBusy) {
                swipeLayout.setRefreshing(false);
            }
        }
        mGBDeviceAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controlcenter);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("This app needs location access");
                builder.setMessage("Please grant location access so this app can detect beacons.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                        }
                    }
                });
                builder.show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    LOG.error("coarse location permission granted");
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover beacons when in the background.");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {

                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }

                    });
                    builder.show();
                }
                return;
            }
        }
        hintTextView = (TextView) findViewById(R.id.hintTextView);
        ListView deviceListView = (ListView) findViewById(R.id.deviceListView);
        mGBDeviceAdapter = new GBDeviceAdapter(this, deviceList);
        deviceListView.setAdapter(this.mGBDeviceAdapter);
        deviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView parent, View v, int position, long id) {
                GBDevice gbDevice = deviceList.get(position);
                if (gbDevice.isConnected()) {
                    DeviceCoordinator coordinator = DeviceHelper.getInstance().getCoordinator(gbDevice);
                    Class<? extends Activity> primaryActivity = coordinator.getPrimaryActivity();
                    if (primaryActivity != null) {
                        Intent startIntent = new Intent(ControlCenter.this, primaryActivity);
                        startIntent.putExtra(GBDevice.EXTRA_DEVICE, gbDevice);
                        startActivity(startIntent);
                    }
                } else {
                    GBApplication.deviceService().connect(deviceList.get(position));
                }
            }
        });

        swipeLayout = (SwipeRefreshLayout) findViewById(R.id.controlcenter_swipe_layout);
        swipeLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                fetchActivityData();
            }
        });

        registerForContextMenu(deviceListView);

        IntentFilter filterLocal = new IntentFilter();
        filterLocal.addAction(GBApplication.ACTION_QUIT);
        filterLocal.addAction(ACTION_REFRESH_DEVICELIST);
        filterLocal.addAction(GBDevice.ACTION_DEVICE_CHANGED);
        filterLocal.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filterLocal);

        registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));

        try {
            refreshPairedDevices();
        }
        catch(Exception e)
        {
            LOG.error("Error: " + e.getMessage());
        }
        /*
         * Ask for permission to intercept notifications on first run.
         */
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (sharedPrefs.getBoolean("firstrun", true)) {
            sharedPrefs.edit().putBoolean("firstrun", false).apply();
            Intent enableIntent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            startActivity(enableIntent);
        }
        GBApplication.deviceService().start();

        enableSwipeRefresh(selectedDevice);
        if (GB.isBluetoothEnabled() && deviceList.isEmpty()) {
            // start discovery when no devices are present
            startActivity(new Intent(this, DiscoveryActivity.class));
        } else {
            GBApplication.deviceService().requestDeviceInfo();
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterView.AdapterContextMenuInfo acmi = (AdapterView.AdapterContextMenuInfo) menuInfo;
        selectedDevice = deviceList.get(acmi.position);
        if (selectedDevice != null && selectedDevice.isBusy()) {
            // no context menu when device is busy
            return;
        }
        getMenuInflater().inflate(R.menu.controlcenter_context, menu);

        DeviceCoordinator coordinator = DeviceHelper.getInstance().getCoordinator(selectedDevice);
        if (!coordinator.supportsActivityDataFetching()) {
            menu.removeItem(R.id.controlcenter_fetch_activity_data);
        }
        if (!coordinator.supportsScreenshots()) {
            menu.removeItem(R.id.controlcenter_take_screenshot);
        }

        if (selectedDevice.getState() == GBDevice.State.NOT_CONNECTED) {
            menu.removeItem(R.id.controlcenter_disconnect);
        }
        if (!selectedDevice.isInitialized()) {
            menu.removeItem(R.id.controlcenter_find_device);
            menu.removeItem(R.id.controlcenter_fetch_activity_data);
            menu.removeItem(R.id.controlcenter_configure_alarms);
            menu.removeItem(R.id.controlcenter_take_screenshot);
        }

        menu.setHeaderTitle(selectedDevice.getName());
    }

    private void enableSwipeRefresh(GBDevice device) {
        if (device == null) {
            swipeLayout.setEnabled(false);
        } else {
            DeviceCoordinator coordinator = DeviceHelper.getInstance().getCoordinator(device);
            boolean enable = coordinator.allowFetchActivityData(device);
            swipeLayout.setEnabled(enable);
        }
    }

    private void fetchActivityData() {
        if (selectedDevice == null) {
            return;
        }
        if (selectedDevice.isInitialized()) {
            GBApplication.deviceService().onFetchActivityData();
        } else {
            swipeLayout.setRefreshing(false);
            GB.toast(this, getString(R.string.device_not_connected), Toast.LENGTH_SHORT, GB.ERROR);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.controlcenter_start_sleepmonitor:
                if (selectedDevice != null) {
                    Intent startIntent;
                    startIntent = new Intent(ControlCenter.this, ChartsActivity.class);
                    startIntent.putExtra(GBDevice.EXTRA_DEVICE, selectedDevice);
                    startActivity(startIntent);
                }
                return true;
            case R.id.controlcenter_fetch_activity_data:
                fetchActivityData();
                return true;
            case R.id.controlcenter_disconnect:
                if (selectedDevice != null) {
                    selectedDevice = null;
                    GBApplication.deviceService().disconnect();
                }
                return true;
            case R.id.controlcenter_find_device:
                if (selectedDevice != null) {
                    findDevice(true);
                    ProgressDialog.show(
                            this,
                            getString(R.string.control_center_find_lost_device),
                            getString(R.string.control_center_cancel_to_stop_vibration),
                            true, true,
                            new DialogInterface.OnCancelListener() {
                                @Override
                                public void onCancel(DialogInterface dialog) {
                                    findDevice(false);
                                }
                            });
                }
                return true;
            case R.id.controlcenter_configure_alarms:
                if (selectedDevice != null) {
                    Intent startIntent;
                    startIntent = new Intent(ControlCenter.this, ConfigureAlarms.class);
                    startActivity(startIntent);
                }
                return true;
            case R.id.controlcenter_take_screenshot:
                if (selectedDevice != null) {
                    GBApplication.deviceService().onScreenshotReq();
                }
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private void findDevice(boolean start) {
        GBApplication.deviceService().onFindDevice(start);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent settingsIntent = new Intent(this, SettingsActivity.class);
                startActivity(settingsIntent);
                return true;
            case R.id.action_debug:
                Intent debugIntent = new Intent(this, DebugActivity.class);
                startActivity(debugIntent);
                return true;
            case R.id.action_quit:
                GBApplication.deviceService().quit();

                Intent quitIntent = new Intent(GBApplication.ACTION_QUIT);
                LocalBroadcastManager.getInstance(this).sendBroadcast(quitIntent);
                return true;
            case R.id.action_discover:
                Intent discoverIntent = new Intent(this, DiscoveryActivity.class);
                startActivity(discoverIntent);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    private void refreshPairedDevices() {
        Set<GBDevice> availableDevices = DeviceHelper.getInstance().getAvailableDevices(this);
        deviceList.retainAll(availableDevices);
        for (GBDevice availableDevice : availableDevices) {
            if (!deviceList.contains(availableDevice)) {
                deviceList.add(availableDevice);
            }
        }
        boolean connected = false;
        for (GBDevice device : deviceList) {
            if (device.isConnected() || device.isConnecting()) {
                connected = true;
                break;
            }
        }

        if (connected) {
            hintTextView.setText(R.string.tap_connected_device_for_app_mananger);
        } else if (!deviceList.isEmpty()) {
            hintTextView.setText(R.string.tap_a_device_to_connect);
        }

        mGBDeviceAdapter.notifyDataSetChanged();
    }
}
