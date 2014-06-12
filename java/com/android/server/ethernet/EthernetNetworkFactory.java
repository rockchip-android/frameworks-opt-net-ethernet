/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.ethernet;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.InterfaceConfiguration;
import android.net.NetworkUtils;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkRequest;
import android.net.EthernetManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.net.BaseNetworkObserver;

import java.net.Inet4Address;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Manages connectivity for an Ethernet interface.
 *
 * Ethernet Interfaces may be present at boot time or appear after boot (e.g.,
 * for Ethernet adapters connected over USB). This class currently supports
 * only one interface. When an interface appears on the system (or is present
 * at boot time) this class will start tracking it and bring it up, and will
 * attempt to connect when requested. Any other interfaces that subsequently
 * appear will be ignored until the tracked interface disappears. Only
 * interfaces whose names match the <code>config_ethernet_iface_regex</code>
 * regular expression are tracked.
 *
 * This class reports a static network score of 70 when it is tracking an
 * interface and that interface's link is up, and a score of 0 otherwise.
 *
 * @hide
 */
class EthernetNetworkFactory {
    private static final String NETWORK_TYPE = "Ethernet";
    private static final String TAG = "EthernetNetworkFactory";
    private static final int NETWORK_SCORE = 70;
    private static final boolean DBG = true;

    /** Tracks interface changes. Called from NetworkManagementService. */
    private InterfaceObserver mInterfaceObserver;

    /** For static IP configuration */
    private EthernetManager mEthernetManager;

    /** To set link state and configure IP addresses. */
    private INetworkManagementService mNMService;

    /* To communicate with ConnectivityManager */
    private NetworkCapabilities mNetworkCapabilities;
    private NetworkAgent mNetworkAgent;
    private LocalNetworkFactory mFactory;
    private Context mContext;

    /** Product-dependent regular expression of interface names we track. */
    private static String mIfaceMatch = "";

    /** Data members. All accesses to these must be synchronized(this). */
    private static String mIface = "";
    private String mHwAddr;
    private static boolean mLinkUp;
    private NetworkInfo mNetworkInfo;
    private LinkProperties mLinkProperties;

    EthernetNetworkFactory() {
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, NETWORK_TYPE, "");
        mLinkProperties = new LinkProperties();
        initNetworkCapabilities();
    }

    private class LocalNetworkFactory extends NetworkFactory {
        LocalNetworkFactory(String name, Context context, Looper looper) {
            super(looper, context, name, new NetworkCapabilities());
        }

        protected void startNetwork() {
            onRequestNetwork();
        }
        protected void stopNetwork() {
            onCancelRequest();
        }
    }


    /**
     * Updates interface state variables.
     * Called on link state changes or on startup.
     */
    private void updateInterfaceState(String iface, boolean up) {
        if (!mIface.equals(iface)) {
            return;
        }
        Log.d(TAG, "updateInterface: " + iface + " link " + (up ? "up" : "down"));

        synchronized(this) {
            mLinkUp = up;
            mNetworkInfo.setIsAvailable(up);
            if (!up) {
                // Tell the agent we're disconnected. It will call disconnect().
                mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, mHwAddr);
            }
            updateAgent();
            mFactory.setScoreFilter(up ? NETWORK_SCORE : -1);
        }
    }

    private class InterfaceObserver extends BaseNetworkObserver {
        @Override
        public void interfaceLinkStateChanged(String iface, boolean up) {
            updateInterfaceState(iface, up);
        }

        @Override
        public void interfaceAdded(String iface) {
            maybeTrackInterface(iface);
        }

        @Override
        public void interfaceRemoved(String iface) {
            stopTrackingInterface(iface);
        }
    }

    private void setInterfaceUp(String iface) {
        // Bring up the interface so we get link status indications.
        try {
            mNMService.setInterfaceUp(iface);
            String hwAddr = null;
            InterfaceConfiguration config = mNMService.getInterfaceConfig(iface);

            if (config == null) {
                Log.e(TAG, "Null iterface config for " + iface + ". Bailing out.");
                return;
            }

            synchronized (this) {
                if (mIface.isEmpty()) {
                    mIface = iface;
                    mHwAddr = config.getHardwareAddress();
                    mNetworkInfo.setIsAvailable(true);
                    mNetworkInfo.setExtraInfo(mHwAddr);
                } else {
                    Log.e(TAG, "Interface unexpectedly changed from " + iface + " to " + mIface);
                    mNMService.setInterfaceDown(iface);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error upping interface " + mIface + ": " + e);
        }
    }

    private boolean maybeTrackInterface(String iface) {
        // If we don't already have an interface, and if this interface matches
        // our regex, start tracking it.
        if (!iface.matches(mIfaceMatch) || !mIface.isEmpty())
            return false;

        Log.d(TAG, "Started tracking interface " + iface);
        setInterfaceUp(iface);
        return true;
    }

    private void stopTrackingInterface(String iface) {
        if (!iface.equals(mIface))
            return;

        Log.d(TAG, "Stopped tracking interface " + iface);
        onCancelRequest();
        synchronized (this) {
            mIface = "";
            mHwAddr = null;
            mNetworkInfo.setExtraInfo(null);
            mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, NETWORK_TYPE, "");
            mLinkProperties = new LinkProperties();
        }
    }

    private void setStaticIpAddress(LinkProperties linkProperties) {
        Log.i(TAG, "Applying static IPv4 configuration to " + mIface + ": " + mLinkProperties);
        try {
            InterfaceConfiguration config = mNMService.getInterfaceConfig(mIface);
            for (LinkAddress address: linkProperties.getLinkAddresses()) {
                // IPv6 uses autoconfiguration.
                if (address.getAddress() instanceof Inet4Address) {
                    config.setLinkAddress(address);
                    // This API only supports one IPv4 address.
                    mNMService.setInterfaceConfig(mIface, config);
                    break;
                }
            }
        } catch(RemoteException e) {
           Log.e(TAG, "Setting static IP address failed: " + e.getMessage());
        } catch(IllegalStateException e) {
           Log.e(TAG, "Setting static IP address failed: " + e.getMessage());
        }
    }

    public void updateAgent() {
        synchronized (EthernetNetworkFactory.this) {
            if (mNetworkAgent == null) return;
            if (DBG) {
                Log.i(TAG, "Updating mNetworkAgent with: " +
                      mNetworkCapabilities + ", " +
                      mNetworkInfo + ", " +
                      mLinkProperties);
            }
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            mNetworkAgent.sendLinkProperties(mLinkProperties);
            mNetworkAgent.sendNetworkScore(mLinkUp? NETWORK_SCORE : -1);
        }
    }

    /* Called by the NetworkFactory on the handler thread. */
    public void onRequestNetwork() {
        // TODO: Handle DHCP renew.
        Thread dhcpThread = new Thread(new Runnable() {
            public void run() {
                if (DBG) Log.i(TAG, "dhcpThread(+" + mIface + "): mNetworkInfo=" + mNetworkInfo);
                LinkProperties linkProperties;

                IpConfiguration config = mEthernetManager.getConfiguration();

                if (config.ipAssignment == IpAssignment.STATIC) {
                    linkProperties = config.linkProperties;
                    setStaticIpAddress(linkProperties);
                } else {
                    mNetworkInfo.setDetailedState(DetailedState.OBTAINING_IPADDR, null, mHwAddr);

                    DhcpResults dhcpResults = new DhcpResults();
                    // TODO: Handle DHCP renewals better.
                    // In general runDhcp handles DHCP renewals for us, because
                    // the dhcp client stays running, but if the renewal fails,
                    // we will lose our IP address and connectivity without
                    // noticing.
                    if (!NetworkUtils.runDhcp(mIface, dhcpResults)) {
                        Log.e(TAG, "DHCP request error:" + NetworkUtils.getDhcpError());
                        mFactory.setScoreFilter(-1);
                        return;
                    }
                    linkProperties = dhcpResults.linkProperties;
                    linkProperties.setInterfaceName(mIface);
                }
                if (config.proxySettings == ProxySettings.STATIC) {
                    linkProperties.setHttpProxy(config.linkProperties.getHttpProxy());
                }

                synchronized(EthernetNetworkFactory.this) {
                    mLinkProperties = linkProperties;
                    mNetworkInfo.setIsAvailable(true);
                    mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, mHwAddr);

                    // Create our NetworkAgent.
                    mNetworkAgent = new NetworkAgent(mFactory.getLooper(), mContext,
                            NETWORK_TYPE, mNetworkInfo, mNetworkCapabilities, mLinkProperties,
                            NETWORK_SCORE) {
                        public void unwanted() {
                            EthernetNetworkFactory.this.onCancelRequest();
                        };
                    };
                }
            }
        });
        dhcpThread.start();
    }

    /**
      * Clears layer 3 properties and reports disconnect.
      * Does not touch interface state variables such as link state and MAC address.
      * Called when the tracked interface loses link or disappears.
      */
    public void onCancelRequest() {
        NetworkUtils.stopDhcp(mIface);

        synchronized(this) {
            mLinkProperties.clear();
            mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, mHwAddr);
            updateAgent();
            mNetworkAgent = null;
        }

        try {
            mNMService.clearInterfaceAddresses(mIface);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear addresses or disable ipv6" + e);
        }
    }

    /**
     * Begin monitoring connectivity
     */
    public synchronized void start(Context context, Handler target) {
        // The services we use.
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNMService = INetworkManagementService.Stub.asInterface(b);
        mEthernetManager = (EthernetManager) context.getSystemService(Context.ETHERNET_SERVICE);

        // Interface match regex.
        mIfaceMatch = context.getResources().getString(
                com.android.internal.R.string.config_ethernet_iface_regex);

        // Create and register our NetworkFactory.
        mFactory = new LocalNetworkFactory(NETWORK_TYPE, context, target.getLooper());
        mFactory.setCapabilityFilter(mNetworkCapabilities);
        mFactory.setScoreFilter(-1); // this set high when we have an iface
        mFactory.register();

        mContext = context;

        // Start tracking interface change events.
        mInterfaceObserver = new InterfaceObserver();
        try {
            mNMService.registerObserver(mInterfaceObserver);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register InterfaceObserver " + e);
        }

        // If an Ethernet interface is already connected, start tracking that.
        // Otherwise, the first Ethernet interface to appear will be tracked.
        try {
            final String[] ifaces = mNMService.listInterfaces();
            for (String iface : ifaces) {
                synchronized(this) {
                    if (maybeTrackInterface(iface)) {
                        // We have our interface. Track it.
                        // Note: if the interface already has link (e.g., if we
                        // crashed and got restarted while it was running),
                        // we need to fake a link up notification so we start
                        // configuring it. Since we're already holding the lock,
                        // any real link up/down notification will only arrive
                        // after we've done this.
                        if (mNMService.getInterfaceConfig(iface).hasFlag("running")) {
                            updateInterfaceState(iface, true);
                        }
                        break;
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not get list of interfaces " + e);
        }
    }

    public synchronized void stop() {
        onCancelRequest();
        mIface = "";
        mHwAddr = null;
        mLinkUp = false;
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, NETWORK_TYPE, "");
        mLinkProperties = new LinkProperties();
        mFactory.unregister();
    }

    private void initNetworkCapabilities() {
        mNetworkCapabilities = new NetworkCapabilities();
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        // We have no useful data on bandwidth. Say 100M up and 100M down. :-(
        mNetworkCapabilities.setLinkUpstreamBandwidthKbps(100 * 1000);
        mNetworkCapabilities.setLinkDownstreamBandwidthKbps(100 * 1000);
    }
}