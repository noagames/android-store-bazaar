package com.soomla.store.billing.bazaar;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import com.android.vending.billing.IInAppBillingService;
import com.android.vending.billing.IInAppBillingService.Stub;
import com.soomla.SoomlaApp;
import com.soomla.SoomlaUtils;
import com.soomla.store.billing.IabException;
import com.soomla.store.billing.IabHelper;
import com.soomla.store.billing.IabInventory;
import com.soomla.store.billing.IabPurchase;
import com.soomla.store.billing.IabResult;
import com.soomla.store.billing.IabSkuDetails;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONException;

public class BazaarIabHelper
        extends IabHelper
{
    public static final int SKU_QUERY_MAX_CHUNK_SIZE = 19;
    public static final String RESPONSE_CODE = "RESPONSE_CODE";
    public static final String RESPONSE_GET_SKU_DETAILS_LIST = "DETAILS_LIST";
    public static final String RESPONSE_BUY_INTENT = "BUY_INTENT";
    public static final String RESPONSE_INAPP_PURCHASE_DATA = "INAPP_PURCHASE_DATA";
    public static final String RESPONSE_INAPP_SIGNATURE = "INAPP_DATA_SIGNATURE";
    public static final String RESPONSE_INAPP_ITEM_LIST = "INAPP_PURCHASE_ITEM_LIST";
    public static final String RESPONSE_INAPP_PURCHASE_DATA_LIST = "INAPP_PURCHASE_DATA_LIST";
    public static final String RESPONSE_INAPP_SIGNATURE_LIST = "INAPP_DATA_SIGNATURE_LIST";
    public static final String INAPP_CONTINUATION_TOKEN = "INAPP_CONTINUATION_TOKEN";
    public static final String GET_SKU_DETAILS_ITEM_LIST = "ITEM_ID_LIST";
    public static final String GET_SKU_DETAILS_ITEM_TYPE_LIST = "ITEM_TYPE_LIST";

    public BazaarIabHelper()
    {
        SoomlaUtils.LogDebug(TAG, "BazaarIabHelper helper created.");
    }

    protected void startSetupInner()
    {
        this.mServiceConn = new ServiceConnection()
        {
            public void onServiceDisconnected(ComponentName name)
            {
                SoomlaUtils.LogDebug(BazaarIabHelper.TAG, "Billing service disconnected.");
                BazaarIabHelper.this.mService = null;
            }

            public void onServiceConnected(ComponentName name, IBinder service)
            {
                SoomlaUtils.LogDebug(BazaarIabHelper.TAG, "Billing service connected.");
                BazaarIabHelper.this.mService = IInAppBillingService.Stub.asInterface(service);
                String packageName = SoomlaApp.getAppContext().getPackageName();
                try
                {
                    SoomlaUtils.LogDebug(BazaarIabHelper.TAG, "Checking for in-app billing 3 support.");

                    int response = BazaarIabHelper.this.mService.isBillingSupported(3, packageName, "inapp");
                    if (response != 0)
                    {
                        BazaarIabHelper.this.setupFailed(new IabResult(response, "Error checking for billing v3 support."));
                        return;
                    }
                    SoomlaUtils.LogDebug(BazaarIabHelper.TAG, "In-app billing version 3 supported for " + packageName);

                    BazaarIabHelper.this.setupSuccess();
                }
                catch (RemoteException e)
                {
                    BazaarIabHelper.this.setupFailed(new IabResult(64535, "RemoteException while setting up in-app billing."));
                    e.printStackTrace();
                }
            }
        };
        Intent serviceIntent = new Intent("ir.cafebazaar.pardakht.InAppBillingService.BIND");
        serviceIntent.setPackage("com.farsitel.bazaar");
        if (!SoomlaApp.getAppContext().getPackageManager().queryIntentServices(serviceIntent, 0).isEmpty()) {
            SoomlaApp.getAppContext().bindService(serviceIntent, this.mServiceConn, Context.BIND_AUTO_CREATE);
        } else {
            setupFailed(new IabResult(3, "Billing service unavailable on device."));
        }
    }


    public void dispose()
    {
        SoomlaUtils.LogDebug(TAG, "Disposing.");
        super.dispose();
        if (this.mServiceConn != null)
        {
            SoomlaUtils.LogDebug(TAG, "Unbinding from service.");
            if ((SoomlaApp.getAppContext() != null) && (this.mService != null)) {
                SoomlaApp.getAppContext().unbindService(this.mServiceConn);
            }
            this.mServiceConn = null;
            this.mService = null;
        }
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode != 10001) {
            return false;
        }
        checkSetupDoneAndThrow("handleActivityResult");
        if (resultCode == 0)
        {
            SoomlaUtils.LogDebug(TAG, "IabPurchase canceled.");
            try
            {
                IabPurchase purchase = new IabPurchase(this.mPurchasingItemType, "{\"productId\":" + this.mPurchasingItemSku + "}", null);
                IabResult result = new IabResult(1, "User canceled.");
                purchaseFailed(result, purchase);
                return true;
            }
            catch (JSONException e)
            {
                SoomlaUtils.LogError(TAG, "Failed to generate canceled purchase.");
                e.printStackTrace();
                IabResult result = new IabResult(64534, "Failed to generate canceled purchase.");
                purchaseFailed(result, null);
                return true;
            }
        }
        if (data == null)
        {
            SoomlaUtils.LogError(TAG, "Null data in IAB activity result.");
            IabResult result = new IabResult(64534, "Null data in IAB result");
            purchaseFailed(result, null);
            return true;
        }
        int responseCode = getResponseCodeFromIntent(data);
        String purchaseData = data.getStringExtra("INAPP_PURCHASE_DATA");
        String dataSignature = data.getStringExtra("INAPP_DATA_SIGNATURE");
        if ((resultCode == -1) && (responseCode == 0))
        {
            SoomlaUtils.LogDebug(TAG, "Successful resultcode from purchase activity.");
            SoomlaUtils.LogDebug(TAG, "IabPurchase data: " + purchaseData);
            SoomlaUtils.LogDebug(TAG, "Data signature: " + dataSignature);
            SoomlaUtils.LogDebug(TAG, "Extras: " + data.getExtras());
            SoomlaUtils.LogDebug(TAG, "Expected item type: " + this.mPurchasingItemType);
            if ((purchaseData == null) || (dataSignature == null))
            {
                SoomlaUtils.LogError(TAG, "BUG: either purchaseData or dataSignature is null.");
                SoomlaUtils.LogDebug(TAG, "Extras: " + data.getExtras().toString());
                IabResult result = new IabResult(64528, "IAB returned null purchaseData or dataSignature");
                purchaseFailed(result, null);
                return true;
            }
            IabPurchase purchase = null;
            try
            {
                purchase = new IabPurchase(this.mPurchasingItemType, purchaseData, dataSignature);
                String sku = purchase.getSku();

                SharedPreferences prefs = SoomlaApp.getAppContext().getSharedPreferences("store.prefs", 0);

                String publicKey = prefs.getString("PO#SU#SO#GU", "");
                if (!Security.verifyPurchase(publicKey, purchaseData, dataSignature))
                {
                    SoomlaUtils.LogError(TAG, "IabPurchase signature verification FAILED for sku " + sku);
                    IabResult result = new IabResult(64533, "Signature verification failed for sku " + sku);
                    purchaseFailed(result, purchase);
                    return true;
                }
                SoomlaUtils.LogDebug(TAG, "IabPurchase signature successfully verified.");
            }
            catch (JSONException e)
            {
                SoomlaUtils.LogError(TAG, "Failed to parse purchase data.");
                e.printStackTrace();
                IabResult result = new IabResult(64534, "Failed to parse purchase data.");
                purchaseFailed(result, null);
                return true;
            }
            purchaseSucceeded(purchase);
        }
        else if (resultCode == -1)
        {
            SoomlaUtils.LogDebug(TAG, "Result code was OK but in-app billing response was not OK: " + IabResult.getResponseDesc(responseCode));
            IabResult result = new IabResult(responseCode, "Problem purchashing item.");
            purchaseFailed(result, null);
        }
        else
        {
            SoomlaUtils.LogError(TAG, "IabPurchase failed. Result code: " + Integer.toString(resultCode) + ". Response: " + IabResult.getResponseDesc(responseCode));

            IabResult result = new IabResult(64530, "Unknown purchase response.");
            purchaseFailed(result, null);
        }
        return true;
    }

    public void consume(IabPurchase itemInfo)
            throws IabException
    {
        checkSetupDoneAndThrow("consume");
        if (!itemInfo.getItemType().equals("inapp")) {
            throw new IabException(64526, "Items of type '" + itemInfo.getItemType() + "' can't be consumed.");
        }
        try
        {
            String token = itemInfo.getToken();
            String sku = itemInfo.getSku();
            if ((token == null) || (token.equals("")))
            {
                SoomlaUtils.LogError(TAG, "Can't consume " + sku + ". No token.");
                throw new IabException(64529, "PurchaseInfo is missing token for sku: " + sku + " " + itemInfo);
            }
            SoomlaUtils.LogDebug(TAG, "Consuming sku: " + sku + ", token: " + token);
            int response = this.mService.consumePurchase(3, SoomlaApp.getAppContext().getPackageName(), token);
            if (response == 0)
            {
                SoomlaUtils.LogDebug(TAG, "Successfully consumed sku: " + sku);
            }
            else
            {
                SoomlaUtils.LogDebug(TAG, "Error consuming consuming sku " + sku + ". " + IabResult.getResponseDesc(response));
                throw new IabException(response, "Error consuming sku " + sku);
            }
        }
        catch (RemoteException e)
        {
            throw new IabException(64535, "Remote exception while consuming. PurchaseInfo: " + itemInfo, e);
        }
    }

    public void consumeAsync(IabPurchase purchase, OnConsumeFinishedListener listener)
    {
        checkSetupDoneAndThrow("consume");
        List<IabPurchase> purchases = new ArrayList();
        purchases.add(purchase);
        consumeAsyncInternal(purchases, listener, null);
    }

    public void consumeAsync(List<IabPurchase> purchases, OnConsumeMultiFinishedListener listener)
    {
        checkSetupDoneAndThrow("consume");
        consumeAsyncInternal(purchases, null, listener);
    }

    protected void restorePurchasesAsyncInner()
    {
        new Thread(new Runnable()
        {
            public void run()
            {
                IabInventory inv = null;
                try
                {
                    inv = BazaarIabHelper.this.restorePurchases();
                }
                catch (IabException ex)
                {
                    IabResult result = ex.getResult();
                    BazaarIabHelper.this.restorePurchasesFailed(result);
                    return;
                }
                BazaarIabHelper.this.restorePurchasesSuccess(inv);
            }
        }).start();
    }

    protected void fetchSkusDetailsAsyncInner(final List<String> skus)
    {
        new Thread(new Runnable()
        {
            public void run()
            {
                IabInventory inv = null;
                try
                {
                    inv = BazaarIabHelper.this.fetchSkusDetails(skus);
                }
                catch (IabException ex)
                {
                    IabResult result = ex.getResult();
                    BazaarIabHelper.this.fetchSkusDetailsFailed(result);
                    return;
                }
                BazaarIabHelper.this.fetchSkusDetailsSuccess(inv);
            }
        }).start();
    }

    @Override
    protected void launchPurchaseFlowInner(Activity activity, String s, String s1, String s2) {
        try {
            launchPurchaseFlowInner(activity,s1,s2);
        } catch (SendIntentException e) {
            SoomlaUtils.LogError(TAG,"Something went wrong during launch: "+e.getMessage());
        }
    }

    protected void launchPurchaseFlowInner(Activity act, String sku, String extraData) throws SendIntentException {
        try
        {
            SoomlaUtils.LogDebug(TAG, "Constructing buy intent for " + sku + ", item type: " + "inapp");
            Bundle buyIntentBundle = this.mService.getBuyIntent(3, SoomlaApp.getAppContext().getPackageName(), sku, "inapp", extraData);
            buyIntentBundle.putString("PURCHASE_SKU", sku);
            int response = getResponseCodeFromBundle(buyIntentBundle);
            if (response != 0)
            {
                SoomlaUtils.LogError(TAG, "Unable to buy item, Error response: " + IabResult.getResponseDesc(response));

                IabPurchase failPurchase = new IabPurchase("inapp", "{\"productId\":" + sku + "}", null);
                IabResult result = new IabResult(response, "Unable to buy item");
                purchaseFailed(result, failPurchase);
                act.finish();
                return;
            }
            PendingIntent pendingIntent = (PendingIntent)buyIntentBundle.getParcelable("BUY_INTENT");
            SoomlaUtils.LogDebug(TAG, "Launching buy intent for " + sku + ". Request code: " + 10001);
            this.mPurchasingItemSku = sku;
            this.mPurchasingItemType = "inapp";

            act.startIntentSenderForResult(pendingIntent.getIntentSender(), 10001, new Intent(), Integer.valueOf(0).intValue(), Integer.valueOf(0).intValue(), Integer.valueOf(0).intValue());
        }
        catch (IntentSender.SendIntentException e)
        {
            SoomlaUtils.LogError(TAG, "SendIntentException while launching purchase flow for sku " + sku);
            e.printStackTrace();

            IabResult result = new IabResult(64532, "Failed to send intent.");
            purchaseFailed(result, null);
        }
        catch (RemoteException e)
        {
            SoomlaUtils.LogError(TAG, "RemoteException while launching purchase flow for sku " + sku);
            e.printStackTrace();

            IabResult result = new IabResult(64535, "Remote exception while starting purchase flow");
            purchaseFailed(result, null);
        }
        catch (JSONException e)
        {
            SoomlaUtils.LogError(TAG, "Failed to generate failing purchase.");
            e.printStackTrace();

            IabResult result = new IabResult(64534, "Failed to generate failing purchase.");
            purchaseFailed(result, null);
        }
    }

    private void consumeAsyncInternal(final List<IabPurchase> purchases, final OnConsumeFinishedListener singleListener, final OnConsumeMultiFinishedListener multiListener)
    {
        final Handler handler = new Handler();
        flagStartAsync("consume");
        new Thread(new Runnable()
        {
            public void run()
            {
                final List<IabResult> results = new ArrayList();
                for (IabPurchase purchase : purchases) {
                    try
                    {
                        BazaarIabHelper.this.consume(purchase);
                        results.add(new IabResult(0, "Successful consume of sku " + purchase.getSku()));
                    }
                    catch (IabException ex)
                    {
                        results.add(ex.getResult());
                    }
                }
                BazaarIabHelper.this.flagEndAsync();
                if (singleListener != null) {
                    handler.post(new Runnable()
                    {
                        public void run()
                        {
                            singleListener.onConsumeFinished((IabPurchase)purchases.get(0), (IabResult)results.get(0));
                        }
                    });
                }
                if (multiListener != null) {
                    handler.post(new Runnable()
                    {
                        public void run()
                        {
                            multiListener.onConsumeMultiFinished(purchases,results);
                        }
                    });
                }
            }
        }).start();
    }

    private IabInventory fetchSkusDetails(List<String> skus)
            throws IabException
    {
        checkSetupDoneAndThrow("fetchSkusDetails");
        try
        {
            IabInventory inv = new IabInventory();
            int r = querySkuDetails("inapp", inv, skus);
            if (r != 0) {
                throw new IabException(r, "Error refreshing inventory (querying prices of items).");
            }
            return inv;
        }
        catch (RemoteException e)
        {
            throw new IabException(64535, "Remote exception while refreshing inventory.", e);
        }
        catch (JSONException e)
        {
            throw new IabException(64534, "Error parsing JSON response while refreshing inventory.", e);
        }
    }

    private int queryPurchases(IabInventory inv, String itemType)
            throws JSONException, RemoteException
    {
        SoomlaUtils.LogDebug(TAG, "Querying owned items, item type: " + itemType);
        SoomlaUtils.LogDebug(TAG, "Package name: " + SoomlaApp.getAppContext().getPackageName());
        boolean verificationFailed = false;
        String continueToken = null;
        do
        {
            SoomlaUtils.LogDebug(TAG, "Calling getPurchases with continuation token: " + continueToken);
            Bundle ownedItems = this.mService.getPurchases(3, SoomlaApp.getAppContext().getPackageName(), itemType, continueToken);

            int response = getResponseCodeFromBundle(ownedItems);
            SoomlaUtils.LogDebug(TAG, "Owned items response: " + String.valueOf(response));
            if (response != 0)
            {
                SoomlaUtils.LogDebug(TAG, "getPurchases() failed: " + IabResult.getResponseDesc(response));
                return response;
            }
            if ((!ownedItems.containsKey("INAPP_PURCHASE_ITEM_LIST")) || (!ownedItems.containsKey("INAPP_PURCHASE_DATA_LIST")) || (!ownedItems.containsKey("INAPP_DATA_SIGNATURE_LIST")))
            {
                SoomlaUtils.LogError(TAG, "Bundle returned from getPurchases() doesn't contain required fields.");
                return 64534;
            }
            ArrayList<String> ownedSkus = ownedItems.getStringArrayList("INAPP_PURCHASE_ITEM_LIST");

            ArrayList<String> purchaseDataList = ownedItems.getStringArrayList("INAPP_PURCHASE_DATA_LIST");

            ArrayList<String> signatureList = ownedItems.getStringArrayList("INAPP_DATA_SIGNATURE_LIST");

            SharedPreferences prefs = SoomlaApp.getAppContext().getSharedPreferences("store.prefs", 0);

            String publicKey = prefs.getString("PO#SU#SO#GU", "");
            for (int i = 0; i < purchaseDataList.size(); i++)
            {
                String purchaseData = (String)purchaseDataList.get(i);
                String signature = (String)signatureList.get(i);
                String sku = (String)ownedSkus.get(i);
                if (Security.verifyPurchase(publicKey, purchaseData, signature))
                {
                    SoomlaUtils.LogDebug(TAG, "Sku is owned: " + sku);
                    IabPurchase purchase = new IabPurchase(itemType, purchaseData, signature);
                    if (TextUtils.isEmpty(purchase.getToken()))
                    {
                        SoomlaUtils.LogWarning(TAG, "BUG: empty/null token!");
                        SoomlaUtils.LogDebug(TAG, "IabPurchase data: " + purchaseData);
                    }
                    inv.addPurchase(purchase);
                }
                else
                {
                    SoomlaUtils.LogWarning(TAG, "IabPurchase signature verification **FAILED**. Not adding item.");
                    SoomlaUtils.LogDebug(TAG, "   IabPurchase data: " + purchaseData);
                    SoomlaUtils.LogDebug(TAG, "   Signature: " + signature);
                    verificationFailed = true;
                }
            }
            continueToken = ownedItems.getString("INAPP_CONTINUATION_TOKEN");
            SoomlaUtils.LogDebug(TAG, "Continuation token: " + continueToken);
        } while (!TextUtils.isEmpty(continueToken));
        return verificationFailed ? 64533 : 0;
    }

    private IabInventory restorePurchases()
            throws IabException
    {
        checkSetupDoneAndThrow("restorePurchases");
        try
        {
            IabInventory inv = new IabInventory();
            int r = queryPurchases(inv, "inapp");
            if (r != 0) {
                throw new IabException(r, "Error refreshing inventory (querying owned items).");
            }
            return inv;
        }
        catch (RemoteException e)
        {
            throw new IabException(64535, "Remote exception while refreshing inventory.", e);
        }
        catch (JSONException e)
        {
            throw new IabException(64534, "Error parsing JSON response while refreshing inventory.", e);
        }
    }

    private int querySkuDetails(String itemType, IabInventory inv, List<String> skus)
            throws RemoteException, JSONException
    {
        SoomlaUtils.LogDebug(TAG, "Querying SKU details.");

        Set<String> skuSet = new HashSet(skus);
        ArrayList<String> skuList = new ArrayList(skuSet);
        if (skuList.size() == 0)
        {
            SoomlaUtils.LogDebug(TAG, "queryPrices: nothing to do because there are no SKUs.");
            return 0;
        }
        int chunkIndex = 1;
        while (skuList.size() > 0)
        {
            ArrayList<String> skuSubList = new ArrayList(skuList.subList(0, Math.min(19, skuList.size())));

            skuList.removeAll(skuSubList);
            int chunkResponse = querySkuDetailsChunk(itemType, inv, skuSubList);
            if (chunkResponse != 0)
            {
                SoomlaUtils.LogDebug(TAG, String.format("querySkuDetails[chunk=%d] failed: %s", new Object[] { Integer.valueOf(chunkIndex), IabResult.getResponseDesc(chunkResponse) }));

                return chunkResponse;
            }
            chunkIndex++;
        }
        return 0;
    }

    private int querySkuDetailsChunk(String itemType, IabInventory inv, ArrayList<String> chunkSkuList)
            throws RemoteException, JSONException
    {
        Bundle querySkus = new Bundle();
        querySkus.putStringArrayList("ITEM_ID_LIST", chunkSkuList);
        Bundle skuDetails = this.mService.getSkuDetails(3, SoomlaApp.getAppContext().getPackageName(), itemType, querySkus);
        if (!skuDetails.containsKey("DETAILS_LIST"))
        {
            int response = getResponseCodeFromBundle(skuDetails);
            if (response != 0)
            {
                SoomlaUtils.LogDebug(TAG, "querySkuDetailsChunk() failed: " + IabResult.getResponseDesc(response));
                return response;
            }
            SoomlaUtils.LogError(TAG, "querySkuDetailsChunk() returned a bundle with neither an error nor a detail list.");
            return 64534;
        }
        ArrayList<String> responseList = skuDetails.getStringArrayList("DETAILS_LIST");
        for (String thisResponse : responseList)
        {
            IabSkuDetails d = new IabSkuDetails(itemType, thisResponse);
            SoomlaUtils.LogDebug(TAG, "Got sku details: " + d);
            inv.addSkuDetails(d);
        }
        return 0;
    }

    private int getResponseCodeFromBundle(Bundle b)
    {
        Object o = b.get("RESPONSE_CODE");
        if (o == null)
        {
            SoomlaUtils.LogDebug(TAG, "Bundle with null response code, assuming OK (known issue)");
            return 0;
        }
        if ((o instanceof Integer)) {
            return ((Integer)o).intValue();
        }
        if ((o instanceof Long)) {
            return (int)((Long)o).longValue();
        }
        SoomlaUtils.LogError(TAG, "Unexpected type for bundle response code.");
        SoomlaUtils.LogError(TAG, o.getClass().getName());
        throw new RuntimeException("Unexpected type for bundle response code: " + o.getClass().getName());
    }

    private int getResponseCodeFromIntent(Intent i)
    {
        Object o = i.getExtras().get("RESPONSE_CODE");
        if (o == null)
        {
            SoomlaUtils.LogError(TAG, "Intent with no response code, assuming OK (known issue)");
            return 0;
        }
        if ((o instanceof Integer)) {
            return ((Integer)o).intValue();
        }
        if ((o instanceof Long)) {
            return (int)((Long)o).longValue();
        }
        SoomlaUtils.LogError(TAG, "Unexpected type for intent response code.");
        SoomlaUtils.LogError(TAG, o.getClass().getName());
        throw new RuntimeException("Unexpected type for intent response code: " + o.getClass().getName());
    }

    private static String TAG = "SOOMLA BazaarIabHelper";
    private IInAppBillingService mService;
    private ServiceConnection mServiceConn;
    private String mPurchasingItemType;
    private String mPurchasingItemSku;
    private static final int RC_REQUEST = 10001;

    public static abstract interface OnConsumeMultiFinishedListener
    {
        public abstract void onConsumeMultiFinished(List<IabPurchase> paramList, List<IabResult> paramList1);
    }

    public static abstract interface OnConsumeFinishedListener
    {
        public abstract void onConsumeFinished(IabPurchase paramIabPurchase, IabResult paramIabResult);
    }
}
