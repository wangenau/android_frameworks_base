/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.wifi.IWifiManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiStateMachine;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiWatchdogStateMachine;
import android.net.DhcpInfo;
import android.net.DhcpResults;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.Messenger;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.server.am.BatteryStatsService;
import static com.android.server.wifi.WifiController.CMD_AIRPLANE_TOGGLED;
import static com.android.server.wifi.WifiController.CMD_BATTERY_CHANGED;
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_LOCKS_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SCAN_ALWAYS_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SCREEN_OFF;
import static com.android.server.wifi.WifiController.CMD_SCREEN_ON;
import static com.android.server.wifi.WifiController.CMD_SET_AP;
import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;
/**
 * WifiService handles remote WiFi operation requests by implementing
 * the IWifiManager interface.
 *
 * @hide
 */
public final class WifiService extends IWifiManager.Stub {
    private static final String TAG = "WifiService";
    private static final boolean DBG = false;

    final WifiStateMachine mWifiStateMachine;

    private final Context mContext;

    final LockList mLocks = new LockList();
    // some wifi lock statistics
    private int mFullHighPerfLocksAcquired;
    private int mFullHighPerfLocksReleased;
    private int mFullLocksAcquired;
    private int mFullLocksReleased;
    private int mScanLocksAcquired;
    private int mScanLocksReleased;

    private final List<Multicaster> mMulticasters =
            new ArrayList<Multicaster>();
    private int mMulticastEnabled;
    private int mMulticastDisabled;

    private AtomicBoolean mDeviceProvisioned = new AtomicBoolean();
    private AtomicBoolean mNotifyScanMode = new AtomicBoolean();

    private final IBatteryStats mBatteryStats;
    private final AppOpsManager mAppOps;

    private String mInterfaceName;

    /* Tracks the open wi-fi network notification */
    private WifiNotificationController mNotificationController;
    /* Polls traffic stats and notifies clients */
    private WifiTrafficPoller mTrafficPoller;
    /* Tracks the persisted states for wi-fi & airplane mode */
    final WifiSettingsStore mSettingsStore;

    /* The work source (UID) that triggered the current WIFI scan, synchronized
     * on this */
    private WorkSource mScanWorkSource;

    private boolean mIsReceiverRegistered = false;

    /**
     * Asynchronous channel to WifiStateMachine
     */
    private AsyncChannel mWifiStateMachineChannel;

    /**
     * Handles client connections
     */
    private class ClientHandler extends Handler {

        ClientHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        if (DBG) Slog.d(TAG, "New client listening to asynchronous messages");
                        // We track the clients by the Messenger
                        // since it is expected to be always available
                        mTrafficPoller.addClient(msg.replyTo);
                    } else {
                        Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        if (DBG) Slog.d(TAG, "Send failed, client connection lost");
                    } else {
                        if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                    }
                    mTrafficPoller.removeClient(msg.replyTo);
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, this, msg.replyTo);
                    break;
                }
                /* Client commands are forwarded to state machine */
                case WifiManager.CONNECT_NETWORK:
                case WifiManager.SAVE_NETWORK:
                case WifiManager.FORGET_NETWORK:
                case WifiManager.START_WPS:
                case WifiManager.CANCEL_WPS:
                case WifiManager.DISABLE_NETWORK:
                case WifiManager.RSSI_PKTCNT_FETCH: {
                    mWifiStateMachine.sendMessage(Message.obtain(msg));
                    break;
                }
                default: {
                    Slog.d(TAG, "ClientHandler.handleMessage ignoring msg=" + msg);
                    break;
                }
            }
        }
    }
    private ClientHandler mClientHandler;

    /**
     * Handles interaction with WifiStateMachine
     */
    private class WifiStateMachineHandler extends Handler {
        private AsyncChannel mWsmChannel;

        WifiStateMachineHandler(android.os.Looper looper) {
            super(looper);
            mWsmChannel = new AsyncChannel();
            mWsmChannel.connect(mContext, this, mWifiStateMachine.getHandler());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiStateMachineChannel = mWsmChannel;
                    } else {
                        Slog.e(TAG, "WifiStateMachine connection failure, error=" + msg.arg1);
                        mWifiStateMachineChannel = null;
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    Slog.e(TAG, "WifiStateMachine channel lost, msg.arg1 =" + msg.arg1);
                    mWifiStateMachineChannel = null;
                    //Re-establish connection to state machine
                    mWsmChannel.connect(mContext, this, mWifiStateMachine.getHandler());
                    break;
                }
                default: {
                    Slog.d(TAG, "WifiStateMachineHandler.handleMessage ignoring msg=" + msg);
                    break;
                }
            }
        }
    }
    WifiStateMachineHandler mWifiStateMachineHandler;

    private WifiWatchdogStateMachine mWifiWatchdogStateMachine;

    public WifiService(Context context) {
        mContext = context;

        mInterfaceName =  SystemProperties.get("wifi.interface", "wlan0");

        mWifiStateMachine = new WifiStateMachine(mContext, mInterfaceName);
        mWifiStateMachine.enableRssiPolling(true);
        mBatteryStats = BatteryStatsService.getService();
        mAppOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);

        mNotificationController = new WifiNotificationController(mContext, mWifiStateMachine);
        mTrafficPoller = new WifiTrafficPoller(mContext, mInterfaceName);
        mSettingsStore = new WifiSettingsStore(mContext);

        HandlerThread wifiThread = new HandlerThread("WifiService");
        wifiThread.start();
        mClientHandler = new ClientHandler(wifiThread.getLooper());
        mWifiStateMachineHandler = new WifiStateMachineHandler(wifiThread.getLooper());
        mWifiController = new WifiController(mContext, this, wifiThread.getLooper());
        mWifiController.start();

        registerForScanModeChange();
        registerForDeviceProvisionedChange();
        registerForNotifyUserOnScanModeChange();
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (mSettingsStore.handleAirplaneModeToggled()) {
                            mWifiController.sendMessage(CMD_AIRPLANE_TOGGLED);
                        }
                    }
                },
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent.getAction().equals(
                                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                            noteScanEnd();
                        }
                    }
                }, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    private WifiController mWifiController;

    /** Tell battery stats about a new WIFI scan */
    private void noteScanStart() {
        WorkSource scanWorkSource = null;
        synchronized (WifiService.this) {
            if (mScanWorkSource != null) {
                // Scan already in progress, don't add this one to battery stats
                return;
            }
            scanWorkSource = new WorkSource(Binder.getCallingUid());
            mScanWorkSource = scanWorkSource;
        }

        long id = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteWifiScanStartedFromSource(scanWorkSource);
        } catch (RemoteException e) {
            Log.w(TAG, e);
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    /** Tell battery stats that the current WIFI scan has completed */
    private void noteScanEnd() {
        WorkSource scanWorkSource = null;
        synchronized (WifiService.this) {
            scanWorkSource = mScanWorkSource;
            mScanWorkSource = null;
        }
        if (scanWorkSource != null) {
            try {
                mBatteryStats.noteWifiScanStoppedFromSource(scanWorkSource);
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        }
    }

    /**
     * Check if Wi-Fi needs to be enabled and start
     * if needed
     *
     * This function is used only at boot time
     */
    public void checkAndStartWifi() {
        /* Check if wi-fi needs to be enabled */
        boolean wifiEnabled = mSettingsStore.isWifiToggleEnabled();
        Slog.i(TAG, "WifiService starting up with Wi-Fi " +
                (wifiEnabled ? "enabled" : "disabled"));

        // If we are already disabled (could be due to airplane mode), avoid changing persist
        // state here
        if (wifiEnabled) setWifiEnabled(wifiEnabled);

        mWifiWatchdogStateMachine = WifiWatchdogStateMachine.
               makeWifiWatchdogStateMachine(mContext);

    }

    /**
     * see {@link android.net.wifi.WifiManager#pingSupplicant()}
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean pingSupplicant() {
        enforceAccessPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncPingSupplicant(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#startScan()}
     */
    public void startScan() {
        enforceChangePermission();
        mWifiStateMachine.startScan();
        noteScanStart();
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                                                "WifiService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                                                "WifiService");

    }

    private void enforceMulticastChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                "WifiService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)}
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    public synchronized boolean setWifiEnabled(boolean enable) {
        enforceChangePermission();
        Slog.d(TAG, "setWifiEnabled: " + enable + " pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
        if (DBG) {
            Slog.e(TAG, "Invoking mWifiStateMachine.setWifiEnabled\n");
        }

        /*
        * Caller might not have WRITE_SECURE_SETTINGS,
        * only CHANGE_WIFI_STATE is enforced
        */

        long ident = Binder.clearCallingIdentity();
        try {

            /* Turning off Wi-Fi when scans are still available */
            if (!enable && isScanningAlwaysAvailable()) {
                /* Notify if device is provisioned and user has not opted out of the notification */
                if (mNotifyScanMode.get() && mDeviceProvisioned.get()) {
                    Intent intent = new Intent(WifiManager.ACTION_NOTIFY_SCAN_ALWAYS_AVAILABLE);
                    mContext.startActivityAsUser(intent, null, UserHandle.CURRENT);
                }
            }

            if (! mSettingsStore.handleWifiToggled(enable)) {
                // Nothing to do if wifi cannot be toggled
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        mWifiController.sendMessage(CMD_WIFI_TOGGLED);

        if (enable) {
            if (!mIsReceiverRegistered) {
                registerForBroadcasts();
                mIsReceiverRegistered = true;
            }
        } else if (mIsReceiverRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mIsReceiverRegistered = false;
        }

        return true;
    }

    /**
     * see {@link WifiManager#getWifiState()}
     * @return One of {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    public int getWifiEnabledState() {
        enforceAccessPermission();
        return mWifiStateMachine.syncGetWifiState();
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiApEnabled(WifiConfiguration, boolean)}
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @param enabled true to enable and false to disable
     */
    public void setWifiApEnabled(WifiConfiguration wifiConfig, boolean enabled) {
        enforceChangePermission();
        mWifiController.obtainMessage(CMD_SET_AP, enabled ? 1 : 0, 0, wifiConfig).sendToTarget();
    }

    /**
     * see {@link WifiManager#getWifiApState()}
     * @return One of {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *         {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *         {@link WifiManager#WIFI_AP_STATE_FAILED}
     */
    public int getWifiApEnabledState() {
        enforceAccessPermission();
        return mWifiStateMachine.syncGetWifiApState();
    }

    /**
     * see {@link WifiManager#getWifiApConfiguration()}
     * @return soft access point configuration
     */
    public WifiConfiguration getWifiApConfiguration() {
        enforceAccessPermission();
        return mWifiStateMachine.syncGetWifiApConfiguration();
    }

    /**
     * see {@link WifiManager#setWifiApConfiguration(WifiConfiguration)}
     * @param wifiConfig WifiConfiguration details for soft access point
     */
    public void setWifiApConfiguration(WifiConfiguration wifiConfig) {
        enforceChangePermission();
        if (wifiConfig == null)
            return;
        mWifiStateMachine.setWifiApConfiguration(wifiConfig);
    }

    /**
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    public boolean isScanningAlwaysAvailable() {
        enforceAccessPermission();
        return mSettingsStore.isScanAlwaysAvailable();
    }

    /**
     * see {@link android.net.wifi.WifiManager#disconnect()}
     */
    public void disconnect() {
        enforceChangePermission();
        mWifiStateMachine.disconnectCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#reconnect()}
     */
    public void reconnect() {
        enforceChangePermission();
        mWifiStateMachine.reconnectCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#reassociate()}
     */
    public void reassociate() {
        enforceChangePermission();
        mWifiStateMachine.reassociateCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#getConfiguredNetworks()}
     * @return the list of configured networks
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        enforceAccessPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetConfiguredNetworks(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOrUpdateNetwork(WifiConfiguration)}
     * @return the supplicant-assigned identifier for the new or updated
     * network if the operation succeeds, or {@code -1} if it fails
     */
    public int addOrUpdateNetwork(WifiConfiguration config) {
        enforceChangePermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncAddOrUpdateNetwork(mWifiStateMachineChannel, config);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return -1;
        }
    }

     /**
     * See {@link android.net.wifi.WifiManager#removeNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean removeNetwork(int netId) {
        enforceChangePermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncRemoveNetwork(mWifiStateMachineChannel, netId);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#enableNetwork(int, boolean)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @param disableOthers if true, disable all other networks.
     * @return {@code true} if the operation succeeded
     */
    public boolean enableNetwork(int netId, boolean disableOthers) {
        enforceChangePermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncEnableNetwork(mWifiStateMachineChannel, netId,
                    disableOthers);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#disableNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean disableNetwork(int netId) {
        enforceChangePermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncDisableNetwork(mWifiStateMachineChannel, netId);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#getConnectionInfo()}
     * @return the Wi-Fi information, contained in {@link WifiInfo}.
     */
    public WifiInfo getConnectionInfo() {
        enforceAccessPermission();
        /*
         * Make sure we have the latest information, by sending
         * a status request to the supplicant.
         */
        return mWifiStateMachine.syncRequestConnectionInfo();
    }

    /**
     * Return the results of the most recent access point scan, in the form of
     * a list of {@link ScanResult} objects.
     * @return the list of results
     */
    public List<ScanResult> getScanResults(String callingPackage) {
        enforceAccessPermission();
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        if (mAppOps.noteOp(AppOpsManager.OP_WIFI_SCAN, uid, callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
            return new ArrayList<ScanResult>();
        }
        try {
            int currentUser = ActivityManager.getCurrentUser();
            if (userId != currentUser) {
                return new ArrayList<ScanResult>();
            } else {
                return mWifiStateMachine.syncGetScanResultsList();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Tell the supplicant to persist the current list of configured networks.
     * @return {@code true} if the operation succeeded
     *
     * TODO: deprecate this
     */
    public boolean saveConfiguration() {
        boolean result = true;
        enforceChangePermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncSaveConfig(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * Set the country code
     * @param countryCode ISO 3166 country code.
     * @param persist {@code true} if the setting should be remembered.
     *
     * The persist behavior exists so that wifi can fall back to the last
     * persisted country code on a restart, when the locale information is
     * not available from telephony.
     */
    public void setCountryCode(String countryCode, boolean persist) {
        Slog.i(TAG, "WifiService trying to set country code to " + countryCode +
                " with persist set to " + persist);
        enforceChangePermission();
        mWifiStateMachine.setCountryCode(countryCode, persist);
    }

    /**
     * Set the operational frequency band
     * @param band One of
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_AUTO},
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_5GHZ},
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_2GHZ},
     * @param persist {@code true} if the setting should be remembered.
     *
     */
    public void setFrequencyBand(int band, boolean persist) {
        enforceChangePermission();
        if (!isDualBandSupported()) return;
        Slog.i(TAG, "WifiService trying to set frequency band to " + band +
                " with persist set to " + persist);
        mWifiStateMachine.setFrequencyBand(band, persist);
    }


    /**
     * Get the operational frequency band
     */
    public int getFrequencyBand() {
        enforceAccessPermission();
        return mWifiStateMachine.getFrequencyBand();
    }

    public boolean isDualBandSupported() {
        //TODO: Should move towards adding a driver API that checks at runtime
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wifi_dual_band_support);
    }

    /**
     * Return the DHCP-assigned addresses from the last successful DHCP request,
     * if any.
     * @return the DHCP information
     * @deprecated
     */
    public DhcpInfo getDhcpInfo() {
        enforceAccessPermission();
        DhcpResults dhcpResults = mWifiStateMachine.syncGetDhcpResults();
        if (dhcpResults.linkProperties == null) return null;

        DhcpInfo info = new DhcpInfo();
        for (LinkAddress la : dhcpResults.linkProperties.getLinkAddresses()) {
            InetAddress addr = la.getAddress();
            if (addr instanceof Inet4Address) {
                info.ipAddress = NetworkUtils.inetAddressToInt((Inet4Address)addr);
                break;
            }
        }
        for (RouteInfo r : dhcpResults.linkProperties.getRoutes()) {
            if (r.isDefaultRoute()) {
                InetAddress gateway = r.getGateway();
                if (gateway instanceof Inet4Address) {
                    info.gateway = NetworkUtils.inetAddressToInt((Inet4Address)gateway);
                }
            } else if (r.hasGateway() == false) {
                LinkAddress dest = r.getDestination();
                if (dest.getAddress() instanceof Inet4Address) {
                    info.netmask = NetworkUtils.prefixLengthToNetmaskInt(
                            dest.getNetworkPrefixLength());
                }
            }
        }
        int dnsFound = 0;
        for (InetAddress dns : dhcpResults.linkProperties.getDnses()) {
            if (dns instanceof Inet4Address) {
                if (dnsFound == 0) {
                    info.dns1 = NetworkUtils.inetAddressToInt((Inet4Address)dns);
                } else {
                    info.dns2 = NetworkUtils.inetAddressToInt((Inet4Address)dns);
                }
                if (++dnsFound > 1) break;
            }
        }
        InetAddress serverAddress = dhcpResults.serverAddress;
        if (serverAddress instanceof Inet4Address) {
            info.serverAddress = NetworkUtils.inetAddressToInt((Inet4Address)serverAddress);
        }
        info.leaseDuration = dhcpResults.leaseDuration;

        return info;
    }

    /**
     * see {@link android.net.wifi.WifiManager#startWifi}
     *
     */
    public void startWifi() {
        enforceConnectivityInternalPermission();
        /* TODO: may be add permissions for access only to connectivity service
         * TODO: if a start issued, keep wifi alive until a stop issued irrespective
         * of WifiLock & device idle status unless wifi enabled status is toggled
         */

        mWifiStateMachine.setDriverStart(true);
        mWifiStateMachine.reconnectCommand();
    }

    public void captivePortalCheckComplete() {
        enforceConnectivityInternalPermission();
        mWifiStateMachine.captivePortalCheckComplete();
    }

    /**
     * see {@link android.net.wifi.WifiManager#stopWifi}
     *
     */
    public void stopWifi() {
        enforceConnectivityInternalPermission();
        /*
         * TODO: if a stop is issued, wifi is brought up only by startWifi
         * unless wifi enabled status is toggled
         */
        mWifiStateMachine.setDriverStart(false);
    }

    /**
     * see {@link android.net.wifi.WifiManager#addToBlacklist}
     *
     */
    public void addToBlacklist(String bssid) {
        enforceChangePermission();

        mWifiStateMachine.addToBlacklist(bssid);
    }

    /**
     * see {@link android.net.wifi.WifiManager#clearBlacklist}
     *
     */
    public void clearBlacklist() {
        enforceChangePermission();

        mWifiStateMachine.clearBlacklist();
    }

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiService
     */
    public Messenger getWifiServiceMessenger() {
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mClientHandler);
    }

    /** Get a reference to WifiStateMachine handler for AsyncChannel communication */
    public Messenger getWifiStateMachineMessenger() {
        enforceAccessPermission();
        enforceChangePermission();
        return mWifiStateMachine.getMessenger();
    }

    /**
     * Get the IP and proxy configuration file
     */
    public String getConfigFile() {
        enforceAccessPermission();
        return mWifiStateMachine.getConfigFile();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mWifiController.sendMessage(CMD_SCREEN_ON);
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mWifiController.sendMessage(CMD_SCREEN_OFF);
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                int pluggedType = intent.getIntExtra("plugged", 0);
                mWifiController.sendMessage(CMD_BATTERY_CHANGED, pluggedType, 0, null);
            } else if (action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        BluetoothAdapter.STATE_DISCONNECTED);
                mWifiStateMachine.sendBluetoothAdapterStateChange(state);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                boolean emergencyMode = intent.getBooleanExtra("phoneinECMState", false);
                mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, emergencyMode ? 1 : 0, 0);
            }
        }
    };

    /**
     * Observes settings changes to scan always mode.
     */
    private void registerForScanModeChange() {
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                mSettingsStore.handleWifiScanAlwaysAvailableToggled();
                mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE),
                false, contentObserver);
    }

    private void getPersistedDeviceProvisioned() {
        mDeviceProvisioned.set(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0);
    }

    private void getPersistedNotifyScanMode() {
        mNotifyScanMode.set(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_NOTIFY_SCAN_ALWAYS_AVAILABLE, 1) == 1);
    }

    /**
     * Observes settings changes to notify the user when scan mode is active and
     * Wi-Fi is turned off
     */
    private void registerForNotifyUserOnScanModeChange() {
            ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                getPersistedNotifyScanMode();
            }
        };

        getPersistedNotifyScanMode();
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_NOTIFY_SCAN_ALWAYS_AVAILABLE),
                false, contentObserver);
    }

    /*
     * Observes settings changes device provisioned status
     */
    private void registerForDeviceProvisionedChange() {
       ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                getPersistedDeviceProvisioned();
            }
        };

        getPersistedDeviceProvisioned();
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false, contentObserver);
    }

    private void registerForBroadcasts() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("Wi-Fi is " + mWifiStateMachine.syncGetWifiStateByName());
        pw.println("Stay-awake conditions: " +
                Settings.Global.getInt(mContext.getContentResolver(),
                                       Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0));
        pw.println("mDeviceProvisioned " + mDeviceProvisioned.get());
        pw.println("mNotifyScanMode " + mNotifyScanMode.get());
        pw.println("mMulticastEnabled " + mMulticastEnabled);
        pw.println("mMulticastDisabled " + mMulticastDisabled);
        mWifiController.dump(fd, pw, args);
        mSettingsStore.dump(fd, pw, args);
        mNotificationController.dump(fd, pw, args);
        mTrafficPoller.dump(fd, pw, args);

        pw.println("Latest scan results:");
        List<ScanResult> scanResults = mWifiStateMachine.syncGetScanResultsList();
        if (scanResults != null && scanResults.size() != 0) {
            pw.println("  BSSID              Frequency   RSSI  Flags             SSID");
            for (ScanResult r : scanResults) {
                pw.printf("  %17s  %9d  %5d  %-16s  %s%n",
                                         r.BSSID,
                                         r.frequency,
                                         r.level,
                                         r.capabilities,
                                         r.SSID == null ? "" : r.SSID);
            }
        }
        pw.println();
        pw.println("Locks acquired: " + mFullLocksAcquired + " full, " +
                mFullHighPerfLocksAcquired + " full high perf, " +
                mScanLocksAcquired + " scan");
        pw.println("Locks released: " + mFullLocksReleased + " full, " +
                mFullHighPerfLocksReleased + " full high perf, " +
                mScanLocksReleased + " scan");
        pw.println();
        pw.println("Locks held:");
        mLocks.dump(pw);

        mWifiWatchdogStateMachine.dump(fd, pw, args);
        pw.println();
        mWifiStateMachine.dump(fd, pw, args);
        pw.println();
    }

    private class WifiLock extends DeathRecipient {
        WifiLock(int lockMode, String tag, IBinder binder, WorkSource ws) {
            super(lockMode, tag, binder, ws);
        }

        public void binderDied() {
            synchronized (mLocks) {
                releaseWifiLockLocked(mBinder);
            }
        }

        public String toString() {
            return "WifiLock{" + mTag + " type=" + mMode + " binder=" + mBinder + "}";
        }
    }

    class LockList {
        private List<WifiLock> mList;

        private LockList() {
            mList = new ArrayList<WifiLock>();
        }

        synchronized boolean hasLocks() {
            return !mList.isEmpty();
        }

        synchronized int getStrongestLockMode() {
            if (mList.isEmpty()) {
                return WifiManager.WIFI_MODE_FULL;
            }

            if (mFullHighPerfLocksAcquired > mFullHighPerfLocksReleased) {
                return WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            }

            if (mFullLocksAcquired > mFullLocksReleased) {
                return WifiManager.WIFI_MODE_FULL;
            }

            return WifiManager.WIFI_MODE_SCAN_ONLY;
        }

        synchronized void updateWorkSource(WorkSource ws) {
            for (int i = 0; i < mLocks.mList.size(); i++) {
                ws.add(mLocks.mList.get(i).mWorkSource);
            }
        }

        private void addLock(WifiLock lock) {
            if (findLockByBinder(lock.mBinder) < 0) {
                mList.add(lock);
            }
        }

        private WifiLock removeLock(IBinder binder) {
            int index = findLockByBinder(binder);
            if (index >= 0) {
                WifiLock ret = mList.remove(index);
                ret.unlinkDeathRecipient();
                return ret;
            } else {
                return null;
            }
        }

        private int findLockByBinder(IBinder binder) {
            int size = mList.size();
            for (int i = size - 1; i >= 0; i--) {
                if (mList.get(i).mBinder == binder)
                    return i;
            }
            return -1;
        }

        private void dump(PrintWriter pw) {
            for (WifiLock l : mList) {
                pw.print("    ");
                pw.println(l);
            }
        }
    }

    void enforceWakeSourcePermission(int uid, int pid) {
        if (uid == android.os.Process.myUid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                pid, uid, null);
    }

    public boolean acquireWifiLock(IBinder binder, int lockMode, String tag, WorkSource ws) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (lockMode != WifiManager.WIFI_MODE_FULL &&
                lockMode != WifiManager.WIFI_MODE_SCAN_ONLY &&
                lockMode != WifiManager.WIFI_MODE_FULL_HIGH_PERF) {
            Slog.e(TAG, "Illegal argument, lockMode= " + lockMode);
            if (DBG) throw new IllegalArgumentException("lockMode=" + lockMode);
            return false;
        }
        if (ws != null && ws.size() == 0) {
            ws = null;
        }
        if (ws != null) {
            enforceWakeSourcePermission(Binder.getCallingUid(), Binder.getCallingPid());
        }
        if (ws == null) {
            ws = new WorkSource(Binder.getCallingUid());
        }
        WifiLock wifiLock = new WifiLock(lockMode, tag, binder, ws);
        synchronized (mLocks) {
            return acquireWifiLockLocked(wifiLock);
        }
    }

    private void noteAcquireWifiLock(WifiLock wifiLock) throws RemoteException {
        switch(wifiLock.mMode) {
            case WifiManager.WIFI_MODE_FULL:
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
            case WifiManager.WIFI_MODE_SCAN_ONLY:
                mBatteryStats.noteFullWifiLockAcquiredFromSource(wifiLock.mWorkSource);
                break;
        }
    }

    private void noteReleaseWifiLock(WifiLock wifiLock) throws RemoteException {
        switch(wifiLock.mMode) {
            case WifiManager.WIFI_MODE_FULL:
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
            case WifiManager.WIFI_MODE_SCAN_ONLY:
                mBatteryStats.noteFullWifiLockReleasedFromSource(wifiLock.mWorkSource);
                break;
        }
    }

    private boolean acquireWifiLockLocked(WifiLock wifiLock) {
        if (DBG) Slog.d(TAG, "acquireWifiLockLocked: " + wifiLock);

        mLocks.addLock(wifiLock);

        long ident = Binder.clearCallingIdentity();
        try {
            noteAcquireWifiLock(wifiLock);
            switch(wifiLock.mMode) {
            case WifiManager.WIFI_MODE_FULL:
                ++mFullLocksAcquired;
                break;
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                ++mFullHighPerfLocksAcquired;
                break;

            case WifiManager.WIFI_MODE_SCAN_ONLY:
                ++mScanLocksAcquired;
                break;
            }
            mWifiController.sendMessage(CMD_LOCKS_CHANGED);
            return true;
        } catch (RemoteException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void updateWifiLockWorkSource(IBinder lock, WorkSource ws) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (ws != null && ws.size() == 0) {
            ws = null;
        }
        if (ws != null) {
            enforceWakeSourcePermission(uid, pid);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLocks) {
                int index = mLocks.findLockByBinder(lock);
                if (index < 0) {
                    throw new IllegalArgumentException("Wifi lock not active");
                }
                WifiLock wl = mLocks.mList.get(index);
                noteReleaseWifiLock(wl);
                wl.mWorkSource = ws != null ? new WorkSource(ws) : new WorkSource(uid);
                noteAcquireWifiLock(wl);
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean releaseWifiLock(IBinder lock) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        synchronized (mLocks) {
            return releaseWifiLockLocked(lock);
        }
    }

    private boolean releaseWifiLockLocked(IBinder lock) {
        boolean hadLock;

        WifiLock wifiLock = mLocks.removeLock(lock);

        if (DBG) Slog.d(TAG, "releaseWifiLockLocked: " + wifiLock);

        hadLock = (wifiLock != null);

        long ident = Binder.clearCallingIdentity();
        try {
            if (hadLock) {
                noteReleaseWifiLock(wifiLock);
                switch(wifiLock.mMode) {
                    case WifiManager.WIFI_MODE_FULL:
                        ++mFullLocksReleased;
                        break;
                    case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                        ++mFullHighPerfLocksReleased;
                        break;
                    case WifiManager.WIFI_MODE_SCAN_ONLY:
                        ++mScanLocksReleased;
                        break;
                }
                mWifiController.sendMessage(CMD_LOCKS_CHANGED);
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return hadLock;
    }

    private abstract class DeathRecipient
            implements IBinder.DeathRecipient {
        String mTag;
        int mMode;
        IBinder mBinder;
        WorkSource mWorkSource;

        DeathRecipient(int mode, String tag, IBinder binder, WorkSource ws) {
            super();
            mTag = tag;
            mMode = mode;
            mBinder = binder;
            mWorkSource = ws;
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }
    }

    private class Multicaster extends DeathRecipient {
        Multicaster(String tag, IBinder binder) {
            super(Binder.getCallingUid(), tag, binder, null);
        }

        public void binderDied() {
            Slog.e(TAG, "Multicaster binderDied");
            synchronized (mMulticasters) {
                int i = mMulticasters.indexOf(this);
                if (i != -1) {
                    removeMulticasterLocked(i, mMode);
                }
            }
        }

        public String toString() {
            return "Multicaster{" + mTag + " binder=" + mBinder + "}";
        }

        public int getUid() {
            return mMode;
        }
    }

    public void initializeMulticastFiltering() {
        enforceMulticastChangePermission();

        synchronized (mMulticasters) {
            // if anybody had requested filters be off, leave off
            if (mMulticasters.size() != 0) {
                return;
            } else {
                mWifiStateMachine.startFilteringMulticastV4Packets();
            }
        }
    }

    public void acquireMulticastLock(IBinder binder, String tag) {
        enforceMulticastChangePermission();

        synchronized (mMulticasters) {
            mMulticastEnabled++;
            mMulticasters.add(new Multicaster(tag, binder));
            // Note that we could call stopFilteringMulticastV4Packets only when
            // our new size == 1 (first call), but this function won't
            // be called often and by making the stopPacket call each
            // time we're less fragile and self-healing.
            mWifiStateMachine.stopFilteringMulticastV4Packets();
        }

        int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteWifiMulticastEnabled(uid);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void releaseMulticastLock() {
        enforceMulticastChangePermission();

        int uid = Binder.getCallingUid();
        synchronized (mMulticasters) {
            mMulticastDisabled++;
            int size = mMulticasters.size();
            for (int i = size - 1; i >= 0; i--) {
                Multicaster m = mMulticasters.get(i);
                if ((m != null) && (m.getUid() == uid)) {
                    removeMulticasterLocked(i, uid);
                }
            }
        }
    }

    private void removeMulticasterLocked(int i, int uid)
    {
        Multicaster removed = mMulticasters.remove(i);

        if (removed != null) {
            removed.unlinkDeathRecipient();
        }
        if (mMulticasters.size() == 0) {
            mWifiStateMachine.startFilteringMulticastV4Packets();
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteWifiMulticastDisabled(uid);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean isMulticastEnabled() {
        enforceAccessPermission();

        synchronized (mMulticasters) {
            return (mMulticasters.size() > 0);
        }
    }
}