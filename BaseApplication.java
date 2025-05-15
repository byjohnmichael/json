package com.clearos.clearlife.networking;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.buglife.sdk.Buglife;
import com.clearos.clearlife.BootReceiver;
import com.clearos.clearlife.R;
import com.clearos.clearlife.SystemProperties;
import com.clearos.clearlife.activity.AppSettingsActivity;
import com.clearos.clearlife.activity.HelpAndSupportActivity;
import com.clearos.clearlife.activity.MainActivity;
import com.clearos.clearlife.family.GmPolicyApplication;
import com.clearos.clearlife.family.ScanDisableGmActivity;
import com.clearos.clearlife.helper.ClearNodeApp;
import com.clearos.clearlife.helper.SessionManager;
import com.clearos.clearlife.service.AppUpdatesService;
import com.clearos.clearlife.service.CryptoService;
import com.clearos.clearlife.wizard.WifiSetupWizard;
import com.clearos.dlt.ClearPackages;
import com.clearos.dlt.CryptoKey;
import com.clearos.dlt.CryptoKeysService;
import com.clearos.dlt.DecryptedMqttMessage;
import com.clearos.dlt.DerivedKeyClient;
import com.clearos.dlt.DidAuthStandaloneApi;
import com.clearos.dlt.DidKeys;
import com.clearos.dlt.KeyClientApplication;
import com.clearos.dlt.SharedPrefs;
import com.clearos.gm.UiPolicyContainer;
import com.goterl.lazysodium.exceptions.SodiumException;
import com.goterl.lazysodium.utils.Key;

import org.stellar.sdk.KeyPair;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import okhttp3.Callback;
import okhttp3.Response;

import static com.clearos.clearlife.family.GmPolicyApplication.CLEARGM_PACKAGE_NAME;
import static com.clearos.mqtt.MqttAndroidClient.START_FOREGROUND_ACTION;
import static com.clearos.mqtt.MqttAndroidClient.STOP_FOREGROUND_ACTION;

@ReportsCrashes(
        mailTo = "support@clear.software", //emailId
        mode = ReportingInteractionMode.TOAST,
        resToastText = R.string.crashreport)

public class BaseApplication extends KeyClientApplication {
    private static final String CLEARNODE_HOST_PREFS_KEY = "settings/homeServerHost";
    public static final String DEFAULT_WALLET_NAME = "Default Wallet";

    private static BaseApplication mInstance;
    public static SessionManager sessionManager;
    public CryptoKey userKeys;
    public DidKeys appKeys;
    public static final String MASTER_DID_PREF_KEY = "master-did";
    public static final String APP_DID_PREF_KEY = "application-did";
    private static final String TAG = "BaseApp";
    private static final String[] CLEAR_DNS_SERVERS = new String[] {
        "ns1.cleardns.network"
    };
    private Api api;
    private Map<String, DidAuthStandaloneApi> nodeAppApis = new HashMap<>();
    private Map<String, String> walletAddressesMap = new HashMap<>();

    private static boolean inAppUpdateServiceStarted = false;
    public static final Object inAppUpdateSync = new Object();

    private static MqttNotificationHandler mqttClientHandler = null;

    private boolean hasTriggeredDnsUpdate = false;
    private boolean hasTriggeredMobileDnsUpdate = false;
    private boolean initialDnsSetupRun = false;
    private static final Object dnsUpdateSync = new Object();
    private boolean isDemo = false;

    private static List<String> customResolveVariables = new ArrayList<>();
    private List<InetAddress> clearDnsAddresses = null;
    private Handler dnsChecker;
    public static final AtomicBoolean hasAutoGmSetupTriggered = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        SystemProperties.restoreKeys();
        Buglife.initWithApiKey(this, "bugger");
        Buglife.setHelpActivityName(new ComponentName(this, HelpAndSupportActivity.class));

        if (mInstance == null) {
            mInstance = this;
        }
        try {
            ACRA.init(this);  // ACRA is used for generating crash report.
        } catch (Exception e) {
            e.printStackTrace();
        }

        String server = null;

        try {
            server = SharedPrefs.getInstance(this).getValue(GmPolicyApplication.IS_DEMO_PREFS_KEY);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Couldn't access shared preferences; we're probably in DE mode.");
        }

        isDemo = server != null && !server.equals(getString(R.string.settingsProduction));
        Log.i(TAG, String.format("Server connections isDemo=%b from %s", isDemo, server));

        addRegistrationCallback(GmPolicyApplication.CLEARGM_PACKAGE_NAME, kca -> {
            if (kca.getKeyClient(GmPolicyApplication.CLEARGM_PACKAGE_NAME) != null) {
                api = new Api(kca.getKeyClient(GmPolicyApplication.CLEARGM_PACKAGE_NAME), isDemo);
                storeWalletAddresses();  // Store wallet addresses after API is initialized

                initializeHomeServer(ClearNodeApp.CLEARSHARE_PACKAGE_NAME, ClearNodeApp.CLEARSHARE_PORT);
                initializeHomeServer(ClearNodeApp.CLEARCOIN_PACKAGE_NAME, ClearNodeApp.CLEARCOIN_PORT);
            } else
                Log.w(TAG, "Key client application is null; can't get keys.");
            return null;
        });
        dnsChecker = new Handler();
        canCheckSetDns();

        // For non-setup-wizard application starts, make sure we trigger an automatic setup after
        // 30 seconds.
        boolean disabled = SystemProperties.getGmDisableStatus(getApplicationContext());
        // TODO re-enable the commented code after phase 1 roll-out.
        if (WifiSetupWizard.isClearLifeWizardDisabled(getApplicationContext()) && !disabled) {
            Log.i(TAG, "Disabling GM as part of phase 1 roll-out.");
            ScanDisableGmActivity.processBackgroundDisabling(getApplicationContext());
        } else {
            Log.i(TAG, "Not disabling GM; we're either in the wizard or we've done it before.");
        }
//        if (WifiSetupWizard.isClearLifeWizardDisabled(getApplicationContext()) &&
//            !disabled) {
//            Log.d(TAG, "Setting alarm to auto-setup GM after 30 seconds.");
//            BootReceiver.setAutoGmSetupAlarm(getApplicationContext(), 30);
//        } else
//            Log.d(TAG, "GM is disabled, not setting auto-setup alarm.");
    }

    /**
     * Derives a key pair that matches the one used by ClearLIFE server edition on home servers.
     */
    public DidKeys deriveServerAppKeys(String packageName) {
        DidKeys master = ClearNode.getClearNodeMasterKeys(null);
        if (master == null)
            return null;

        String context = CryptoKey.getShortKeyContext(packageName);
        try {
            Log.d(TAG, String.format("Deriving app keys for %s using base keys with DID %s; Context = %s", packageName, master.getDid(), context));
            DidKeys result = CryptoKey.toDidKeys(CryptoKey.deriveKeyPair(master.getKeys(), 0, context));
            Log.i(TAG, String.format("Derived %s as DID for package %s", result.getDid(), packageName));
            return result;
        } catch (SodiumException e) {
            Log.e(TAG, "While deriving standalone server application keys.", e);
            return null;
        }
    }

    private void initializeHomeServer(String packageName, int port) {
        Log.i(TAG, "Initializing home server API key client for " + packageName);
        if (ClearNodeApp.CLEARSHARE_PACKAGE_NAME.equals(packageName)) {
            DidKeys appKeys = deriveServerAppKeys(packageName);
            DidAuthStandaloneApi api = new ClearShareApi(appKeys, getClearNodeHost(), port);
            nodeAppApis.put(packageName, api);
        }
    }

    /**
     * Returns the default ClearPAY wallet key pair.
     * @return Null if there is a crypto error.
     */
    public KeyPair getClearPayWalletKeys() {
        return getClearPayWalletKeys(DEFAULT_WALLET_NAME);
    }

    /**
     * Returns the key pair for a ClearPAY wallet.
     * @param walletName Name of the wallet to derive keys for.
     * @return Null if there is a crypto error.
     */
    public KeyPair getClearPayWalletKeys(String walletName) {
        DerivedKeyClient keyClient = getKeyClient(ClearPackages.pay.getVal());
        try {
            String b58Hash = keyClient.hashInBase58(walletName);
            Key walletSeed = keyClient.deriveSeed(b58Hash.substring(0, 8), 0);
            KeyPair keyPair = KeyPair.fromSecretSeed(walletSeed.getAsBytes());
            Log.i("PUBLIC KEY: ", keyPair.getAccountId());
            return keyPair;
        } catch (SodiumException e) {
            Log.e(TAG, "Error deriving ClearPAY wallet keys", e);
            return null;
        }
    }

    /**
     * Gets a home server API for the specified package name.
     * @param packageName Fully-qualified name of the package that matches the derived keys
     *                    being used on the server.
     */
    public DidAuthStandaloneApi getHomeServerApi(String packageName) {
        return nodeAppApis.getOrDefault(packageName, null);
    }

    public Api getApi() {
        return api;
    }

    public static synchronized MqttNotificationHandler getMqttClientHandler() {
        return mqttClientHandler;
    }

    /**
     * Configures the monitors that change the ClearGM DNS server list in `resolv.conf` whenever
     * there is a change in network connectivity. BUT, only if ClearGM is actually running!
     */
    private void canCheckSetDns() {
        if (SystemProperties.isClearGmEnabled()) {
            SystemProperties.createDnsSalt();
            configureMobileOnlyDns();
            recompileDnsIpList();
            registerNetworkMonitor();
            initialDnsSetupRun = true;
        }

        if (!initialDnsSetupRun) {
            // Check this again in five minutes from now.
            dnsChecker.postDelayed(this::canCheckSetDns, TimeUnit.MINUTES.toMillis(2));
        } else
            dnsChecker = null;
    }

    /**
     * Returns a `,`-separated list of the custom `resolv.conf` variables the ClearLIFE is using
     * to configure the DNS resolvers in the OS layer.
     * @return Null if there aren't any yet.
     */
    public static String getCustomResolveVariables() {
        if (customResolveVariables.size() == 0)
            return null;
        else
            return String.join(",", customResolveVariables.toArray(new String[0]));
    }

    /**
     * Starts the in-app updates service if it hasn't already been started.
     */
    public static void startInAppUpdatesService() {
        Log.d(TAG, "Start app-updates service fired; checking if it is already running.");
        synchronized (inAppUpdateSync) {
            if (!inAppUpdateServiceStarted) {
                Context context = BaseApplication.getInstance().getApplicationContext();
                Intent serviceIntent = new Intent();
                serviceIntent.setClassName(context, AppUpdatesService.serviceName);
                serviceIntent.setAction(com.clearos.updates.AppUpdatesService.START_SERVICE_ACTION);
                Log.d(TAG, "Starting up service since it is not running.");
                try {
                    Object updateService = context.startService(serviceIntent);
                    if (updateService == null) {
                        Log.w(TAG, "Trouble starting updates service.");
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Service is probably already running.");
                }
                inAppUpdateServiceStarted = true;
            } else Log.d(TAG, "Update service is already running.");
        }
    }

    /**
     * Refreshes the OYP code in the underlying crypto service hosted by ClearLIFE.
     */
    public static void refreshOypInService() {
        Context appContext = BaseApplication.getInstance().getApplicationContext();
        Intent refresh = CryptoKeysService.getOypRefreshIntent(
                appContext,
                CryptoService.serviceName
        );
        appContext.startService(refresh);
    }

    public void setUserKeys(CryptoKey ck) {
        userKeys = ck;
    }
    public CryptoKey getUserKeys() {
        return userKeys;
    }

    /**
     * Returns the master DID from the user's keys if they are available.
     * @return Null if the users keys are null.
     */
    public String getMasterDid() {
        if (userKeys == null)
            return null;

        return userKeys.getDid();
    }

    /**
     * Returns the current home master key pair. WARNING: this should only be used for signing
     * FIDO API requests.
     * @return Null if the user keys are not available.
     */
    public DidKeys getHomeMaster() {
        if (userKeys == null)
            return null;

        try {
            return userKeys.getHomeMaster();
        } catch (SodiumException e) {
            Log.e(TAG, "Error while deriving home master key.", e);
            return null;
        }
    }

    /**
     * Sets the application global user keys object.
     * @param name First and last name of the user to store with this DID.
     * @param displayName Name to display to others (more anonymous).
     * @param callback Function to call with the appKeys once the master registration is complete.
     */
    public void registerUserKeys(String name, String displayName, Function<DidKeys, Void> callback) {
        final SharedPrefs prefs = SharedPrefs.getInstance(getApplicationContext());
        // If the user has set custom home server, load it here. Eventually...
        userKeys.initDataCustodian(getString(R.string.apiKey));

        try {
            appKeys = userKeys.getAppKeyPair();
        } catch (Exception e) {
            Toast.makeText(this, "Error deriving application cryptographic keys.", Toast.LENGTH_LONG).show();
        }

        String masterDid = prefs.getValue(MASTER_DID_PREF_KEY);
        Log.d(TAG, "Found Master DID " + masterDid + " in Shared Preferences.");

        if (masterDid == null || !masterDid.equals(userKeys.getDid())) {
            try {
                userKeys.publishMasterKey(name, displayName, "", new okhttp3.Callback() {
                    @Override
                    public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                        Log.e("KEYS", "IO Failed publishing master key to home server.");
                        callback.apply(null);
                    }

                    @Override
                    public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws IOException {
                        if (response.code() >= 200 && response.code() < 300) {
                            prefs.saveValue(MASTER_DID_PREF_KEY, userKeys.getDid());
                            Log.i(TAG, "Published master public key.");
                            callback.apply(appKeys);
                        } else {
                            Log.e(TAG, "API error publishing master public key: " + Objects.requireNonNull(response.body()).string());
                            callback.apply(null);
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Unable to publish the master public key.", e);
                callback.apply(null);
            }
        } else {
            callback.apply(appKeys);
        }

        String appDid = prefs.getValue(APP_DID_PREF_KEY);
        Log.d(TAG, "Found App DID " + appDid + " in Shared Preferences.");

        if (appDid == null || !appDid.equals(appKeys.getDid())) {
            try {
                userKeys.publishAppKey(this, new Callback() {
                    @Override
                    public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                        Log.e(TAG, "IO failure publishing application public key.");
                    }

                    @Override
                    public void onResponse(@NonNull okhttp3.Call call, @NonNull Response response) {

                        Log.d(TAG, "response code: "+ response.code());

                        if (response.code() >= 200 && response.code() < 300) {
                            prefs.saveValue(APP_DID_PREF_KEY, appKeys.getDid());
                            Log.i(TAG, "Published application public key.");
                        } else {
                            Log.e(TAG,"API error publishing application public key.");
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Unable to publish the application public key.", e);
            }
        }
    }

    private Void onKeyClientNotificationReceived(DecryptedMqttMessage message) {
        getKeyClient().pushNotificationOnNotificationTray(message);
        return null;
    }

    public void startCryptoServices(String packageName, Function<DerivedKeyClient, Void> callback) {
        // Setup the push notification client now that we have cryptographic keys available.
        if (mqttClientHandler == null) {
            CharSequence channelName = getString(R.string.MqttChannelName);
            String description = getString(R.string.MqttChannelDesc);
            String channelId = getString(R.string.MqttChannelId);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(channelId, channelName, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            try {
                assert notificationManager != null;
                notificationManager.createNotificationChannel(channel);
            } catch (Exception e) {
                Log.e(TAG, "Unable to create push notification channel for MQTT.", e);
            }
            setupKeys(dk -> {
                if (dk != null && userKeys != null) {
                    getKeyClient(getPackageName()).setToastKeysConnected(false);
                    Log.i(TAG, "Derived keys setup properly; starting MQTT client handler.");
                    mqttClientHandler = new MqttNotificationHandler(getApplicationContext(), getKeyClient(getPackageName()), userKeys.getDid(), isDemo);
                    Intent serviceStartIntent = new Intent();
                    serviceStartIntent.setClassName(getApplicationContext(), MqttForegroundClient.serviceName);
                    serviceStartIntent.setAction(START_FOREGROUND_ACTION);
                    Object service = getApplicationContext().startService(serviceStartIntent);
                    if (service == null) {
                        Log.w(TAG, "Trouble starting foreground service.");
                    }
                } else {
                    Log.e(TAG, "Error while setting up key client; can't setup MQTT client either.");
                }
                setupKeys(packageName, pdk -> {
                    if (pdk != null) {
                        DerivedKeyClient gmClient = getKeyClient(packageName);
                        gmClient.setToastKeysConnected(false);
                        callback.apply(gmClient);
                    } else
                        callback.apply(null);

                    return null;
                }, this::onKeyClientNotificationReceived);

                return null;
            }, this::onKeyClientNotificationReceived);
        } else
            callback.apply(getKeyClient(packageName));
    }

    public static void startDecentralizedServices(Context context, Runnable callback) {
        GmPolicyApplication.getInstance().startCryptoServices(CLEARGM_PACKAGE_NAME, keyClient -> {
            Log.d(TAG, "Crypto services setup; initializing ClearGM keys for family management.");
            if (keyClient != null && !MainActivity.isDerivedDevice()) {
                GmPolicyApplication.getInstance().setupKeys(CLEARGM_PACKAGE_NAME, appKeys -> {
                    Log.d(TAG, "App keys initialization produced: " + appKeys);
                    Log.d(TAG, "Retrieving cached features and app list.");
                    UiPolicyContainer.getFeaturesList(context, true);
                    UiPolicyContainer.getAppsList(context, true);
                    UiPolicyContainer.initializeAppDomains(context);

                    return null;
                }, null, AppSettingsActivity.isDemoApiServer(context));
            }

            if (callback != null)
                callback.run();

            return null;
        });
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        Intent serviceStopIntent = new Intent();
        serviceStopIntent.setClassName(getApplicationContext(), MqttForegroundClient.serviceName);
        serviceStopIntent.setAction(STOP_FOREGROUND_ACTION);
        Object service = getApplicationContext().startService(serviceStopIntent);
        if (service == null) {
            Log.w(TAG, "Trouble stopping foreground service.");
        }

        synchronized (inAppUpdateSync) {
            if (inAppUpdateServiceStarted) {
                Intent serviceIntent = new Intent();
                serviceIntent.setClassName(getApplicationContext(), AppUpdatesService.serviceName);
                serviceIntent.setAction(com.clearos.updates.AppUpdatesService.STOP_SERVICE_ACTION);
                Object updateService = getApplicationContext().startService(serviceIntent);
                if (updateService == null) {
                    Log.w(TAG, "Trouble stopping updates service.");
                }
                inAppUpdateServiceStarted = false;
            }
        }
    }

    public void threadSafeToast(final String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    //To create instance of base class
    public static synchronized BaseApplication getInstance() {
        return mInstance;
    }

    //To create sessionmanager instance
    public SessionManager getSession() {
        if (sessionManager == null) {
            sessionManager = new SessionManager(getApplicationContext());
        }
        return sessionManager;
    }

    private void timedDnsUpdateTrigger() {
        if (com.clearos.gm.SystemProperties.isClearGmEnabled()) {
            synchronized (dnsUpdateSync) {
                if (!hasTriggeredDnsUpdate) {
                    hasTriggeredDnsUpdate = true;
                    Log.d(TAG, "ClearGM is enabled, updating DNS settings for resolv.conf");
                    new Handler().postDelayed(this::recompileDnsIpList, 5000);
                }
            }
        }
    }

    /**
     * Converts a list of IP addresses to a `;`-separated string.
     */
    private String iNetArrayToString(List<InetAddress> addresses) {
        Set<String> unique = new HashSet<>();
        for (int i=0; i<addresses.size(); i++) {
            String host = addresses.get(i).getHostAddress();
            if (host != null && !host.contains("192.168"))
                unique.add(host);
        }

        return String.join(";", unique);
    }

    /**
     * Sets the list of DNS servers that the ClearGM resolver should use as upstream references.
     */
    private void setOsDnsServers(List<InetAddress> servers) {
        String addresses = iNetArrayToString(servers);
        Log.i(TAG, "Resetting the resolv.conf list of addresses to " + addresses);
        SystemProperties.resetDnsResolvConf(addresses);
        hasTriggeredDnsUpdate = false;
    }

    /**
     * Recompiles a list of DNS server addresses from all currently active interfaces.
     */
    private void recompileDnsIpList() {
        ConnectivityManager cm =
                (ConnectivityManager)getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        List<InetAddress> allDns = new ArrayList<>();

        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                LinkProperties p = cm.getLinkProperties(n);
                Log.d(TAG, String.format("Examining network %s for DNS servers on %s.", n, p));
                if (p != null) {
                    Log.d(TAG, String.format("Adding %s as DNS servers from networks.", p.getDnsServers()));
                    allDns.addAll(p.getDnsServers());
                }
            }
        }

        if (allDns.size() == 0) {
            try {
                Log.d(TAG, "Adding Open DNS addresses for ClearGM as defaults.");
                allDns.add(InetAddress.getByName("208.67.222.222"));
                allDns.add(InetAddress.getByName("2620:119:35::35"));
            } catch (UnknownHostException e) {
                Log.e(TAG, "Error adding default Open DNS addresses for ClearGM.", e);
            }
        }

        setOsDnsServers(allDns);
    }

    private void registerNetworkMonitor() {
        Log.d(TAG, "Registering network monitor for changes.");
        ConnectivityManager cm =
                (ConnectivityManager)getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm != null) {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLost(@NonNull Network network) {
                    super.onLost(network);
                    Log.d(TAG, "Recompiling DNS server list in response to network lost.");
                    timedDnsUpdateTrigger();
                }

                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);
                    Log.d(TAG, "Recompiling DNS server list in response to network available.");
                    timedDnsUpdateTrigger();
                }

                @Override
                public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
                    super.onLinkPropertiesChanged(network, linkProperties);
                    Log.d(TAG, "Recompiling DNS server list in response to link properties changed.");
                    timedDnsUpdateTrigger();
                }
            };
            cm.requestNetwork(request, callback);
            Log.d(TAG, "Network monitor callback has been registered.");
        }
    }

    /**
     * Returns a list of ClearDNS server addresses to configure for solving the MMS problem.
     */
    private void getClearDns(Function<List<InetAddress>, Void> callback) {
        if (clearDnsAddresses != null) {
            callback.apply(clearDnsAddresses);
            return;
        }

        Log.d(TAG, "Querying network with internet for ClearDNS servers.");
        ConnectivityManager cm =
                (ConnectivityManager)getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkRequest r = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        if (cm != null)
            cm.requestNetwork(r, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);
                    synchronized (dnsUpdateSync) {
                        // We don't need to keep resolving these.
                        if (hasTriggeredMobileDnsUpdate && clearDnsAddresses == null) {
                            cm.unregisterNetworkCallback(this);
                            return;
                        } else
                            hasTriggeredMobileDnsUpdate = true;
                    }

                    clearDnsAddresses = new ArrayList<>();
                    Log.d(TAG, String.format("Querying network %s for ClearDNS servers.", network));
                    InetAddress[] hardClear = null;

                    try {
                        hardClear = new InetAddress[]{
                                InetAddress.getByName("104.131.31.114"),
                                Inet6Address.getByName("2604:a880:800:14::128:9000")
                        };

                        for (String cDns: CLEAR_DNS_SERVERS) {
                            InetAddress[] r = network.getAllByName(cDns);
                            Log.d(TAG, String.format("Received %s for ClearDNS query %s", Arrays.toString(r), cDns));
                            clearDnsAddresses.addAll(Arrays.asList(r));
                        }
                    } catch (UnknownHostException e) {
                        Log.e(TAG, "Error adding ClearDNS as default fallback on Mobile only resolution. Using hard-coded value.", e);
                        clearDnsAddresses = Arrays.asList(hardClear);
                    }

                    callback.apply(clearDnsAddresses);
                }
            });
        else
            callback.apply(null);
    }

    /**
     * Configures ClearLIFE to respond to network available events that have the capabilities of
     * an MMS-enabled, SIM data connection. When a new network becomes available, ClearGM resolver
     * in the OS-layer is configured to *exclusively* use the set of DNS servers on that connection.
     */
    private void configureMobileOnlyDns() {
        Log.d(TAG, "Requesting MMS-enabled, cellular transport network for mobile-only DNS.");
        ConnectivityManager cm =
                (ConnectivityManager)getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm != null) {
            NetworkRequest r = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_MMS)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build();
            cm.requestNetwork(r, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);

                    LinkProperties properties = cm.getLinkProperties(network);
                    List<InetAddress> allDns = new ArrayList<>();
                    Log.d(TAG, String.format("Examining network %s for DNS servers on %s.", network, properties));
                    if (properties != null) {
                        Log.d(TAG, String.format("Adding %s as DNS servers from networks.", properties.getDnsServers()));
                        allDns.addAll(properties.getDnsServers());
                    }

                    // Add the ClearDNS servers to the mix as well to resolve whatever the mobile network
                    // doesn't want to resolve.
                    getClearDns(addresses -> {
                        if (addresses == null || addresses.size() == 0) {
                            try {
                                Log.i(TAG, "Adding Google DNS as fallback since ClearDNS not available.");
                                allDns.add(InetAddress.getByName("8.8.8.8"));
                            } catch (UnknownHostException e) {
                                Log.e(TAG, "Error adding Google DNS as default fallback on Mobile only resolution", e);
                            }
                        } else {
                            allDns.addAll(addresses);
                        }

                        configureCustomResolveFiles(allDns);
                        return null;
                    });
                }
            });
        }
    }

    /**
     * Configures the custom resolv.conf files in the OS layer based on MMSC endpoints in the APN
     * settings.
     */
    private void configureCustomResolveFiles(List<InetAddress> dns) {
        Log.d(TAG, "Configuring custom resolve files via APN settings with " + dns);
        if (dns.size() > 0) {
            String tmobile = "tmobile";
            String servers = iNetArrayToString(dns);
            SystemProperties.resetDnsResolvConf(servers, tmobile);
            // Enable this if we want to use only ClearDNS.
            // SystemProperties.resetDnsResolvConf(servers);
            customResolveVariables.add(tmobile);
        }
    }

    /**
     * Gets the currently configured ClearNODE internet host name.
     */
    public static String getClearNodeHost() {
        SharedPrefs prefs = SharedPrefs.getInstance(BaseApplication.getInstance());
        return prefs.getValue(CLEARNODE_HOST_PREFS_KEY);
    }

    /**
     * Saves the host name from connecting a ClearNODE.
     * @param host DDNS host name to use for communicating with the ClearNODE.
     */
    public static void saveClearNodeHost(String host) {
        Log.d(TAG, String.format("Saving `%s` as host name for ClearNODE.", host));
        SharedPrefs prefs = SharedPrefs.getInstance(BaseApplication.getInstance());
        prefs.saveValue(CLEARNODE_HOST_PREFS_KEY, host);
    }

    /**
     * Stores wallet addresses for all configured wallet apps.
     */
    private void storeWalletAddresses() {
        for (Map.Entry<String, String> entry : Constants.WALLET_APP_PACKAGE_NAME_DICT.entrySet()) {
            String walletAppName = entry.getKey();
            String walletAppPackageName = entry.getValue();
            
            DerivedKeyClient keyClient = getKeyClient(ClearPackages.pay.getVal());
            try {
                String b58Hash = keyClient.hashInBase58(Constants.DIGITAL_WORLD_WALLET_NAME);
                String context = b58Hash.substring(0, 8);
                DidKeys appKeys = keyClient.getAppKeys(walletAppPackageName, "", 0);
                if (appKeys != null) {
                    KeyPair base = new KeyPair(appKeys.getKeys());
                    Key walletSeed = CryptoKeysService.getInstance().deriveSeed(base, context, 0).getSeed();
                    KeyPair keyPair = KeyPair.fromSecretSeed(walletSeed.getAsBytes());
                    walletAddressesMap.put(walletAppName, keyPair.getAccountId());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error storing wallet address for " + walletAppName, e);
            }
        }
    }

    /**
     * Gets the map of wallet addresses.
     * @return Map of wallet app names to their account IDs.
     */
    public Map<String, String> getWalletAddressesMap() {
        return walletAddressesMap;
    }
}
