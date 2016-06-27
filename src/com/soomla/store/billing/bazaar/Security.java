package com.soomla.store.billing.bazaar;

import android.text.TextUtils;
import android.util.Log;
import com.soomla.util.Base64;
import com.soomla.util.Base64DecoderException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

public class Security
{
    private static final String TAG = "IABUtil/Security";
    private static final String KEY_FACTORY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA1withRSA";

    public static boolean verifyPurchase(String base64PublicKey, String signedData, String signature)
    {
        if ((TextUtils.isEmpty(signedData)) || (TextUtils.isEmpty(base64PublicKey)) || (TextUtils.isEmpty(signature)))
        {
            Log.e("IABUtil/Security", "Purchase verification failed: missing data.");
            if (BazaarIabService.AllowAndroidTestPurchases)
            {
                Log.e("IABUtil/Security", "Allowing empty signatures ...");
                return true;
            }
            return false;
        }
        PublicKey key = generatePublicKey(base64PublicKey);
        return verify(key, signedData, signature);
    }

    public static PublicKey generatePublicKey(String encodedPublicKey)
    {
        try
        {
            byte[] decodedKey = Base64.decode(encodedPublicKey);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(new X509EncodedKeySpec(decodedKey));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new RuntimeException(e);
        }
        catch (InvalidKeySpecException e)
        {
            Log.e("IABUtil/Security", "Invalid key specification.");
            throw new IllegalArgumentException(e);
        }
        catch (Base64DecoderException e)
        {
            Log.e("IABUtil/Security", "Base64 decoding failed.");
            throw new IllegalArgumentException(e);
        }
    }

    public static boolean verify(PublicKey publicKey, String signedData, String signature)
    {
        try
        {
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initVerify(publicKey);
            sig.update(signedData.getBytes());
            if (!sig.verify(Base64.decode(signature)))
            {
                Log.e("IABUtil/Security", "Signature verification failed.");
                return false;
            }
            return true;
        }
        catch (NoSuchAlgorithmException e)
        {
            Log.e("IABUtil/Security", "NoSuchAlgorithmException.");
        }
        catch (InvalidKeyException e)
        {
            Log.e("IABUtil/Security", "Invalid key specification.");
        }
        catch (SignatureException e)
        {
            Log.e("IABUtil/Security", "Signature exception.");
        }
        catch (Base64DecoderException e)
        {
            Log.e("IABUtil/Security", "Base64 decoding failed.");
        }
        return false;
    }
}
