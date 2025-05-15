package com.clearos.clearlife.networking;

import static com.clearos.clearlife.networking.ClearNode.encryptForChallenge;
import static com.clearos.clearlife.networking.ClearNode.getEncryptedClearNodeSeed;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import com.clearos.clearlife.R;
import com.clearos.clearlife.family.GmPolicyApplication;
import com.clearos.dlt.CryptoKey;
import com.clearos.dlt.DataCustodian;
import com.clearos.dlt.DecryptedMqttMessage;
import com.clearos.dlt.DerivedKeyClient;
import com.clearos.dlt.DidAuthApiCallback;
import com.clearos.dlt.DidAuthClientApi;
import com.clearos.dlt.DidKeys;
import com.clearos.dlt.GsonIsoDateSerializer;
import com.clearos.dlt.SuccessResponse;
import com.clearos.dlt.fido.AddHeaderInterceptor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.goterl.lazysodium.exceptions.SodiumException;
import com.goterl.lazysodium.utils.Key;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.ipfs.multibase.Base58;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Api extends DidAuthClientApi {
    private static final String TAG = "Api";
    private static final Gson gson = (new GsonBuilder()).registerTypeAdapter(Date.class, new GsonIsoDateSerializer()).create();

    public Api(DerivedKeyClient keyClient, boolean isDemo) {
        super(keyClient);

        if (isDemo) {
            setServer(keyClient.getAppContext().getString(R.string.demoServerUrl));
        }
    }

    public static Map<String, Object> getMasterSeedSyncBody(String challenge, String oyn, DidKeys keys, boolean includeResetKeys) {
        // By default, we let the push notifications for a ClearNODE be authorized for 24 weeks.
        long expirationSeconds = Instant.now().getEpochSecond() + TimeUnit.DAYS.toSeconds(24*7);
        String expiration = String.format(Locale.getDefault(), "%d", expirationSeconds);

        Map<String, Object> body = new HashMap<>();
        body.put("vk", challenge);
        // Null string context is the default for application keys.
        body.put("seed", getEncryptedClearNodeSeed(challenge, null));
        if (oyn != null)
            body.put("oyn", encryptForChallenge(challenge, oyn));
        body.put("did", keys.getDid());
        body.put("expiration", expiration);
        if (includeResetKeys)
            body.put("resetSeed", getEncryptedClearNodeSeed(challenge, ClearNode.RESET_KEY_CONTEXT));

        try {
            body.put("signature", CryptoKey.signMessageToHex(expiration, keys));
        } catch (SodiumException e) {
            Log.e(TAG, "While trying to sign the notification auth expiration message.");
            body.put("signature", "");
        }

        return body;
    }

    /**
     * Sends a derived key for use in the ClearNODE's server version of ClearLIFE.
     */
    public void connectClearNode(Activity from, String challenge, Function<SuccessResponse, Void> callback) {
        // Before we can connect the node, we need to derive and register its application keys.
        // This allows the notifications to be sent using the correct signature. Signature can't
        // be verified by the home server unless the public key has been registered (KERI isn't
        // fully integrated yet).
        ClearNode.registerClearNodeKeys(keys -> {
            if (keys == null) {
                Log.w(TAG, "ClearNODE key registration failed; cannot continue connecting ClearNODE");
                callback.apply(null);
                return null;
            }

            GmPolicyApplication.getInstance().getOynCode(from, seed -> {
                if (seed != null) {
                    Log.d(TAG, "OYN code received correctly from encrypted storage.");
                    String oyn = seed.toStringAble().toString();
                    Map<String, Object> body = getMasterSeedSyncBody(challenge, oyn, keys, true);
                    String path = String.format("/id/did/nodesync/%s", challenge);
                    post(path, null, body, new DidAuthApiCallback<>(callback, "connectClearNode", SuccessResponse.class, this));
                } else {
                    Log.d(TAG, "Error retrieving OYN code; can't connect ClearNODE.");
                    SuccessResponse response = new SuccessResponse();
                    response.setSuccess(false);
                    callback.apply(response);
                }
                return null;
            }, null);

            return null;
        });
    }

    private void doLoginQrRequest(Request request, Callback callback) {
        OkHttpClient client = (new OkHttpClient.Builder()).addInterceptor(new AddHeaderInterceptor()).readTimeout(30L, TimeUnit.SECONDS).writeTimeout(40L, TimeUnit.SECONDS).connectTimeout(40L, TimeUnit.SECONDS).build();
        Call call = client.newCall(request);
        call.enqueue(callback);
    }

    /**
     * Approves a push notification by adding a signature and values for any parameterized fields.
     * @param extras JSON serialized mapping of additional values for custom CLEAR-implemented actions.
     * @param noMaster When true, use the resetter keys for signing the payload instead of the master
     *                 node keys.
     */
    public void approveNotification(DecryptedMqttMessage notification, String extras, boolean noMaster, Function<SuccessResponse, Void> callback) {
        DidKeys appKeys;
        if (noMaster)
            appKeys = ClearNode.getClearNodeMasterKeys(ClearNode.RESET_KEY_CONTEXT);
        else
            appKeys = ClearNode.getClearNodeMasterKeys(null);

        if (appKeys == null) {
            SuccessResponse response = new SuccessResponse();
            response.setSuccess(false);
            callback.apply(response);
            return;
        }

        String message = String.format(Locale.getDefault(),"%d/%s", Instant.now().toEpochMilli(), notification.getReference());
        String signature;
        try {
            signature = CryptoKey.signMessageToHex(message, appKeys);
        } catch (SodiumException e) {
            Log.e(TAG, "Error signing message for notification approval.", e);
            SuccessResponse response = new SuccessResponse();
            response.setSuccess(false);
            callback.apply(response);
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("fields", getGson().toJson(notification.getFields()));
        body.put("reference", notification.getReference());
        body.put("message", message);
        body.put("signature", signature);
        body.put("packageName", notification.getPackageName());
        body.put("did", appKeys.getDid());
        body.put("extras", extras);

        String path = String.format("/id/did/approval/%s", notification.getReference());
        post(path, null, body, new DidAuthApiCallback<>(callback, "connectClearNode", SuccessResponse.class, this));
    }

    public void passwordlessQRLogin(String verkey, String url, boolean isUUID, Map<String, String> walletAddressesMap, Function<SuccessResponse, Void> callback) {
        DidKeys appKeys = BaseApplication.getInstance().appKeys;

        if (isUUID)
            appKeys = BaseApplication.getInstance().userKeys.newDid();

        String timestamp = String.valueOf(Instant.now().toEpochMilli());

        String signature;
        try {
            signature = CryptoKey.signMessageToHex(timestamp, appKeys);
        } catch (SodiumException e) {
            Log.e(TAG, "Error signing message for notification approval.", e);
            BaseApplication.getInstance().threadSafeToast("Something went wrong. Please try again.");
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("did", appKeys.getDid());
        body.put("timestamp", timestamp);
        body.put("signature", signature);
        body.put("verkey", appKeys.getVerKey());

        String jsonBody = gson.toJson(body);

        HashMap<String, Object> encryptedBody = new HashMap<>();
        URI target;
        try {
            String encryptedData = CryptoKey.anonCrypt(jsonBody, Key.fromBytes(Base58.decode(verkey)));
            encryptedBody.put("data", encryptedData);
            encryptedBody.put("verkey", verkey);
            
            // Add wallets to the encrypted body
            if (walletAddressesMap != null && !walletAddressesMap.isEmpty()) {
                encryptedBody.put("wallets", walletAddressesMap);
            }
            
            target = new URI(url);
        } catch (SodiumException e) {
            Log.e(TAG, "Error encrypting body", e);
            BaseApplication.getInstance().threadSafeToast("Something went wrong encrypting the request. Please try again.");
            SuccessResponse response = new SuccessResponse();
            response.setSuccess(false);
            callback.apply(response);
            return;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Verkey error ==>.", e);
            BaseApplication.getInstance().threadSafeToast("Verkey error: " + e.getMessage() + "\n\nPlease contact support.");
            SuccessResponse response = new SuccessResponse();
            response.setSuccess(false);
            callback.apply(response);
            return;
        } catch (URISyntaxException e) {
            Log.e(TAG, "Error with URL");
            BaseApplication.getInstance().threadSafeToast("Something went wrong. Please try again.");
            SuccessResponse response = new SuccessResponse();
            response.setSuccess(false);
            callback.apply(response);
            return;
        }

        Map<String, String> headers = new HashMap<>();
        Headers headerbuild = Headers.of(headers);
        RequestBody jBody = RequestBody.create(gson.toJson(encryptedBody), DataCustodian.JSON);

        Request request;
        try {
            okhttp3.Request.Builder builder = (new okhttp3.Request.Builder()).url(target.toURL()).headers(headerbuild);
            request = builder.post(jBody).build();
        } catch (Exception e) {
            Log.e(TAG, "Problem building request", e);
            BaseApplication.getInstance().threadSafeToast("Something went wrong. Please try again.");
            SuccessResponse response = new SuccessResponse();
            response.setSuccess(false);
            callback.apply(response);
            return;
        }

        doLoginQrRequest(request, new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                SuccessResponse response = new SuccessResponse();
                response.setSuccess(false);
                callback.apply(response);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.code() >= 200 && response.code() < 300) {
                    SuccessResponse successResponse = new SuccessResponse();
                    successResponse.setSuccess(true);
                    callback.apply(successResponse);
                } else {
                    SuccessResponse successResponse = new SuccessResponse();
                    successResponse.setSuccess(false);
                    callback.apply(successResponse);
                }
            }
        });
    }
}
