package com.soomla.store.billing.bazaar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import com.soomla.SoomlaApp;
import com.soomla.SoomlaUtils;
import com.soomla.store.SoomlaStore;
import com.soomla.store.billing.IIabService;
import com.soomla.store.billing.IabCallbacks;
import com.soomla.store.billing.IabCallbacks.IabInitListener;
import com.soomla.store.billing.IabCallbacks.OnConsumeListener;
import com.soomla.store.billing.IabCallbacks.OnFetchSkusDetailsListener;
import com.soomla.store.billing.IabCallbacks.OnPurchaseListener;
import com.soomla.store.billing.IabCallbacks.OnRestorePurchasesListener;
import com.soomla.store.billing.IabException;
import com.soomla.store.billing.IabHelper;
import com.soomla.store.billing.IabHelper.FetchSkusDetailsFinishedListener;
import com.soomla.store.billing.IabHelper.OnIabPurchaseFinishedListener;
import com.soomla.store.billing.IabHelper.OnIabSetupFinishedListener;
import com.soomla.store.billing.IabHelper.RestorePurchasessFinishedListener;
import com.soomla.store.billing.IabInventory;
import com.soomla.store.billing.IabPurchase;
import com.soomla.store.billing.IabResult;
import com.soomla.store.billing.IabSkuDetails;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BazaarIabService
        implements IIabService
{
    private static final String TAG = "SOOMLA BazaarIabService";
    private BazaarIabHelper mHelper;

    public void initializeBillingService(IabCallbacks.IabInitListener iabListener)
    {
        startIabHelper(new OnIabSetupFinishedListener(iabListener));
    }

    public void startIabServiceInBg(IabCallbacks.IabInitListener iabListener)
    {
        this.keepIabServiceOpen = true;
        startIabHelper(new OnIabSetupFinishedListener(iabListener));
    }

    public void stopIabServiceInBg(IabCallbacks.IabInitListener iabListener)
    {
        this.keepIabServiceOpen = false;
        stopIabHelper(iabListener);
    }

    public void restorePurchasesAsync(IabCallbacks.OnRestorePurchasesListener restorePurchasesListener)
    {
        this.mHelper.restorePurchasesAsync((RestorePurchasessFinishedListener) new RestorePurchasesFinishedListener(restorePurchasesListener));
    }

    public void fetchSkusDetailsAsync(List<String> skus, IabCallbacks.OnFetchSkusDetailsListener fetchSkusDetailsListener)
    {
        this.mHelper.fetchSkusDetailsAsync(skus, (IabHelper.FetchSkusDetailsFinishedListener) new FetchSkusDetailsFinishedListener(fetchSkusDetailsListener));
    }

    public boolean isIabServiceInitialized()
    {
        return this.mHelper != null;
    }

    public void consume(IabPurchase purchase)
            throws IabException
    {
        this.mHelper.consume(purchase);
    }

    @Override
    public void configVerifyPurchases(Map<String, Object> map) {
        SoomlaUtils.LogError(TAG,"configVerifyPurchases on bazaar not supported.");
    }

    @Override
    public boolean shouldVerifyPurchases() {
        return false;
    }

    public void consumeAsync(IabPurchase purchase, final IabCallbacks.OnConsumeListener consumeListener)
    {
        this.mHelper.consumeAsync(purchase, new BazaarIabHelper.OnConsumeFinishedListener()
        {
            public void onConsumeFinished(IabPurchase purchase, IabResult result)
            {
                if (result.isSuccess()) {
                    consumeListener.success(purchase);
                } else {
                    consumeListener.fail(result.getMessage());
                }
            }
        });
    }

    public void setPublicKey(String publicKey)
    {
        SharedPreferences prefs = SoomlaApp.getAppContext().getSharedPreferences("store.prefs", 0);

        SharedPreferences.Editor edit = prefs.edit();
        if ((publicKey != null) && (publicKey.length() != 0))
        {
            edit.putString("PO#SU#SO#GU", publicKey);
        }
        else if (prefs.getString("PO#SU#SO#GU", "").length() == 0)
        {
            String err = "publicKey is null or empty. Can't initialize store!!";
            SoomlaUtils.LogError("SOOMLA BazaarIabService", err);
        }
        edit.commit();
    }

    @Override
    public void launchPurchaseFlow(String s, String s1, OnPurchaseListener onPurchaseListener, String s2) {
        launchPurchaseFlow(s1,onPurchaseListener,s2);
    }

    public void launchPurchaseFlow(String sku, IabCallbacks.OnPurchaseListener purchaseListener, String extraData)
    {
        SharedPreferences prefs = SoomlaApp.getAppContext().getSharedPreferences("store.prefs", 0);

        String publicKey = prefs.getString("PO#SU#SO#GU", "");
        if ((publicKey.length() == 0) || (publicKey.equals("[YOUR PUBLIC KEY FROM THE MARKET]")))
        {
            SoomlaUtils.LogError("SOOMLA BazaarIabService", "You didn't provide a public key! You can't make purchases. the key: " + publicKey);
            throw new IllegalStateException();
        }
        try
        {
            Intent intent = new Intent(SoomlaApp.getAppContext(), IabActivity.class);
            intent.putExtra("ID#sku", sku);
            intent.putExtra("ID#extraData", extraData);

            this.mSavedOnPurchaseListener = purchaseListener;
            if ((SoomlaApp.getAppContext() instanceof Activity))
            {
                Activity activity = (Activity)SoomlaApp.getAppContext();
                activity.startActivity(intent);
            }
            else
            {
                intent.setFlags(268435456);
                SoomlaApp.getAppContext().startActivity(intent);
            }
        }
        catch (Exception e)
        {
            String msg = "(launchPurchaseFlow) Error purchasing item " + e.getMessage();
            SoomlaUtils.LogError("SOOMLA BazaarIabService", msg);
            purchaseListener.fail(msg);
        }
    }

    private synchronized void startIabHelper(OnIabSetupFinishedListener onIabSetupFinishedListener)
    {
        if (isIabServiceInitialized())
        {
            SoomlaUtils.LogDebug("SOOMLA BazaarIabService", "The helper is started. Just running the post start function.");
            if ((onIabSetupFinishedListener != null) && (onIabSetupFinishedListener.getIabInitListener() != null)) {
                onIabSetupFinishedListener.getIabInitListener().success(true);
            }
            return;
        }
        SoomlaUtils.LogDebug("SOOMLA BazaarIabService", "Creating IAB helper.");
        this.mHelper = new BazaarIabHelper();

        SoomlaUtils.LogDebug("SOOMLA BazaarIabService", "IAB helper Starting setup.");
        this.mHelper.startSetup(onIabSetupFinishedListener);
    }

    private synchronized void stopIabHelper(IabCallbacks.IabInitListener iabInitListener)
    {
        if (this.keepIabServiceOpen)
        {
            String msg = "Not stopping Bazaar Service b/c the user run 'startIabServiceInBg'. Keeping it open.";
            if (iabInitListener != null) {
                iabInitListener.fail(msg);
            } else {
                SoomlaUtils.LogDebug("SOOMLA BazaarIabService", msg);
            }
            return;
        }
        if (this.mHelper == null)
        {
            String msg = "Tried to stop Bazaar Service when it was null.";
            if (iabInitListener != null) {
                iabInitListener.fail(msg);
            } else {
                SoomlaUtils.LogDebug("SOOMLA BazaarIabService", msg);
            }
            return;
        }
        if (!this.mHelper.isAsyncInProgress())
        {
            SoomlaUtils.LogDebug("SOOMLA BazaarIabService", "Stopping Bazaar Service");
            this.mHelper.dispose();
            this.mHelper = null;
            if (iabInitListener != null) {
                iabInitListener.success(true);
            }
        }
        else
        {
            String msg = "Cannot stop Bazaar Service during async process. Will be stopped when async operation is finished.";
            if (iabInitListener != null) {
                iabInitListener.fail(msg);
            } else {
                SoomlaUtils.LogDebug("SOOMLA BazaarIabService", msg);
            }
        }
    }

    private class RestorePurchasesFinishedListener
            implements IabHelper.RestorePurchasessFinishedListener
    {
        private IabCallbacks.OnRestorePurchasesListener mRestorePurchasesListener;

        public RestorePurchasesFinishedListener(IabCallbacks.OnRestorePurchasesListener restorePurchasesListener)
        {
            this.mRestorePurchasesListener = restorePurchasesListener;
        }

        public void onRestorePurchasessFinished(IabResult result, IabInventory inventory)
        {
            SoomlaUtils.LogDebug("SOOMLA BazaarIabService", "Restore Purchases succeeded");
            if ((result.getResponse() == 0) && (this.mRestorePurchasesListener != null))
            {
                List<String> itemSkus = inventory.getAllOwnedSkus("inapp");
                List<IabPurchase> purchases = new ArrayList();
                for (String sku : itemSkus)
                {
                    IabPurchase purchase = inventory.getPurchase(sku);
                    purchases.add(purchase);
                }
                this.mRestorePurchasesListener.success(purchases);
            }
            else
            {
                SoomlaUtils.LogError("SOOMLA BazaarIabService", "Wither mRestorePurchasesListener==null OR Restore purchases error: " + result.getMessage());
                if (this.mRestorePurchasesListener != null) {
                    this.mRestorePurchasesListener.fail(result.getMessage());
                }
            }
            BazaarIabService.this.stopIabHelper(null);
        }
    }

    private class FetchSkusDetailsFinishedListener
            implements IabHelper.FetchSkusDetailsFinishedListener
    {
        private IabCallbacks.OnFetchSkusDetailsListener mFetchSkusDetailsListener;

        public FetchSkusDetailsFinishedListener(IabCallbacks.OnFetchSkusDetailsListener fetchSkusDetailsListener)
        {
            this.mFetchSkusDetailsListener = fetchSkusDetailsListener;
        }

        public void onFetchSkusDetailsFinished(IabResult result, IabInventory inventory)
        {
            SoomlaUtils.LogDebug("SOOMLA BazaarIabService", "Restore Purchases succeeded");
            if ((result.getResponse() == 0) && (this.mFetchSkusDetailsListener != null))
            {
                List<String> skuList = inventory.getAllQueriedSkus(false);
                List<IabSkuDetails> skuDetails = new ArrayList();
                for (String sku : skuList)
                {
                    IabSkuDetails skuDetail = inventory.getSkuDetails(sku);
                    if (skuDetail != null) {
                        skuDetails.add(skuDetail);
                    }
                }
                this.mFetchSkusDetailsListener.success(skuDetails);
            }
            else
            {
                SoomlaUtils.LogError("SOOMLA BazaarIabService", "Wither mFetchSkusDetailsListener==null OR Fetching details error: " + result.getMessage());
                if (this.mFetchSkusDetailsListener != null) {
                    this.mFetchSkusDetailsListener.fail(result.getMessage());
                }
            }
            BazaarIabService.this.stopIabHelper(null);
        }
    }

    private class OnIabSetupFinishedListener
            implements IabHelper.OnIabSetupFinishedListener
    {
        private IabCallbacks.IabInitListener mIabInitListener;

        public IabCallbacks.IabInitListener getIabInitListener()
        {
            return this.mIabInitListener;
        }

        public OnIabSetupFinishedListener(IabCallbacks.IabInitListener iabListener)
        {
            this.mIabInitListener = iabListener;
        }

        public void onIabSetupFinished(IabResult result)
        {
            SoomlaUtils.LogDebug("SOOMLA BazaarIabService", "IAB helper Setup finished.");
            if (result.isFailure())
            {
                if (this.mIabInitListener != null) {
                    this.mIabInitListener.fail(result.getMessage());
                }
                return;
            }
            if (this.mIabInitListener != null) {
                this.mIabInitListener.success(false);
            }
        }
    }

    private static class OnIabPurchaseFinishedListener
            implements IabHelper.OnIabPurchaseFinishedListener
    {
        public void onIabPurchaseFinished(IabResult result, IabPurchase purchase)
        {
            SoomlaUtils.LogDebug("SOOMLA BazaarIabService", "IabPurchase finished: " + result + ", purchase: " + purchase);

            BazaarIabService.getInstance().mWaitingServiceResponse = false;
            if (result.getResponse() == 0) {
                BazaarIabService.getInstance().mSavedOnPurchaseListener.success(purchase);
            } else if (result.getResponse() == 1) {
                BazaarIabService.getInstance().mSavedOnPurchaseListener.cancelled(purchase);
            } else if (result.getResponse() == 7) {
                BazaarIabService.getInstance().mSavedOnPurchaseListener.alreadyOwned(purchase);
            } else {
                BazaarIabService.getInstance().mSavedOnPurchaseListener.fail(result.getMessage());
            }
            BazaarIabService.getInstance().mSavedOnPurchaseListener = null;

            BazaarIabService.getInstance().stopIabHelper(null);
        }
    }

    public static class IabActivity
            extends Activity
    {
        protected void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);

            Intent intent = getIntent();
            String productId = intent.getStringExtra("ID#sku");
            String payload = intent.getStringExtra("ID#extraData");
            try
            {
                BazaarIabService.OnIabPurchaseFinishedListener onIabPurchaseFinishedListener = new BazaarIabService.OnIabPurchaseFinishedListener();
                BazaarIabService.getInstance().mWaitingServiceResponse = true;
                BazaarIabService.getInstance().mHelper.launchPurchaseFlow(this, productId, onIabPurchaseFinishedListener, payload);
            }
            catch (Exception e)
            {
                finish();

                String msg = "Error purchasing item " + e.getMessage();
                SoomlaUtils.LogError("SOOMLA BazaarIabService", msg);
                BazaarIabService.getInstance().mWaitingServiceResponse = false;
                if (BazaarIabService.getInstance().mSavedOnPurchaseListener != null)
                {
                    BazaarIabService.getInstance().mSavedOnPurchaseListener.fail(msg);
                    BazaarIabService.getInstance().mSavedOnPurchaseListener = null;
                }
            }
        }

        protected void onActivityResult(int requestCode, int resultCode, Intent data)
        {
            if (!BazaarIabService.getInstance().mHelper.handleActivityResult(requestCode, resultCode, data)) {
                super.onActivityResult(requestCode, resultCode, data);
            }
            finish();
        }

        protected void onPause()
        {
            super.onPause();
        }

        boolean firstTime = true;

        protected void onResume()
        {
            super.onResume();
            this.firstTime = false;
        }

        protected void onStop()
        {
            super.onStop();
        }

        protected void onStart()
        {
            super.onStart();
            if ((!this.firstTime) && ((SoomlaApp.getAppContext() instanceof Activity)))
            {
                BazaarIabService.getInstance().mHelper.handleActivityResult(10001, 0, null);

                Intent tabIntent = new Intent(this, ((Activity)SoomlaApp.getAppContext()).getClass());
                tabIntent.setFlags(67108864);

                startActivity(tabIntent);
            }
        }

        protected void onDestroy()
        {
            if (BazaarIabService.getInstance().mWaitingServiceResponse)
            {
                BazaarIabService.getInstance().mWaitingServiceResponse = false;
                String err = "IabActivity is destroyed during purchase.";
                SoomlaUtils.LogError("SOOMLA BazaarIabService", err);
                if (BazaarIabService.getInstance().mSavedOnPurchaseListener != null)
                {
                    BazaarIabService.getInstance().mSavedOnPurchaseListener.fail(err);
                    BazaarIabService.getInstance().mSavedOnPurchaseListener = null;
                }
            }
            super.onDestroy();
        }
    }

    public static BazaarIabService getInstance()
    {
        return (BazaarIabService)SoomlaStore.getInstance().getInAppBillingService();
    }

    private boolean keepIabServiceOpen = false;
    private boolean mWaitingServiceResponse = false;
    public static final String PUBLICKEY_KEY = "PO#SU#SO#GU";
    private static final String SKU = "ID#sku";
    private static final String EXTRA_DATA = "ID#extraData";
    private IabCallbacks.OnPurchaseListener mSavedOnPurchaseListener = null;
    public static boolean AllowAndroidTestPurchases = false;
}
