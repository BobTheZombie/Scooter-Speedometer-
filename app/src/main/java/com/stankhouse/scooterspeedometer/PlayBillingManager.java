package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import com.android.billingclient.api.*;
import java.security.MessageDigest;
import java.util.*;

/** Google Play is the payment UI; RiderLink's server remains the entitlement authority. */
public final class PlayBillingManager implements PurchasesUpdatedListener {
    public static final String FOUNDER="rider_pro_founder_lifetime";
    public static final String PRO="rider_pro_lifetime";
    public static final String PLUS="riderlink_plus_monthly";
    public interface Listener { void ready(); void message(String value); void entitlementChanged(); }
    private final Activity activity;private final RiderLinkClient rider;private final Listener listener;
    private final Map<String,ProductDetails> products=new HashMap<>();
    private BillingClient billing;private boolean connected;
    public PlayBillingManager(Activity activity,RiderLinkClient rider,Listener listener){this.activity=activity;this.rider=rider;this.listener=listener;billing=BillingClient.newBuilder(activity).setListener(this).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).enableAutoServiceReconnection().build();connect();}
    private void connect(){billing.startConnection(new BillingClientStateListener(){public void onBillingSetupFinished(BillingResult result){connected=result.getResponseCode()==BillingClient.BillingResponseCode.OK;if(connected){queryProducts();restore();}else listener.message("Google Play billing: "+result.getDebugMessage());}public void onBillingServiceDisconnected(){connected=false;}});}
    private void queryProducts(){List<QueryProductDetailsParams.Product> list=new ArrayList<>();list.add(product(FOUNDER,BillingClient.ProductType.INAPP));list.add(product(PRO,BillingClient.ProductType.INAPP));list.add(product(PLUS,BillingClient.ProductType.SUBS));billing.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(list).build(),(result,details)->{products.clear();if(result.getResponseCode()==BillingClient.BillingResponseCode.OK)for(ProductDetails p:details.getProductDetailsList())products.put(p.getProductId(),p);listener.ready();});}
    private QueryProductDetailsParams.Product product(String id,String type){return QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build();}
    public boolean available(String id){return products.containsKey(id);}
    public String price(String id,String fallback){ProductDetails p=products.get(id);if(p==null)return fallback;if(PLUS.equals(id)){List<ProductDetails.SubscriptionOfferDetails> offers=p.getSubscriptionOfferDetails();if(offers!=null&&!offers.isEmpty()){List<ProductDetails.PricingPhase> phases=offers.get(0).getPricingPhases().getPricingPhaseList();if(!phases.isEmpty())return phases.get(phases.size()-1).getFormattedPrice()+"/month";}}List<ProductDetails.OneTimePurchaseOfferDetails> offers=p.getOneTimePurchaseOfferDetailsList();return offers==null||offers.isEmpty()?fallback:offers.get(0).getFormattedPrice();}
    public void buy(String id){if(!rider.signedIn()){listener.message("Sign in to RiderLink before purchasing");return;}ProductDetails p=products.get(id);if(p==null){listener.message("This product is not active in Google Play Console yet");return;}BillingFlowParams.ProductDetailsParams.Builder item=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p);String token=offerToken(p,id);if(token!=null&&!token.isEmpty())item.setOfferToken(token);BillingFlowParams flow=BillingFlowParams.newBuilder().setProductDetailsParamsList(Collections.singletonList(item.build())).setObfuscatedAccountId(hash(rider.userId())).build();BillingResult result=billing.launchBillingFlow(activity,flow);if(result.getResponseCode()!=BillingClient.BillingResponseCode.OK)listener.message(result.getDebugMessage());}
    private String offerToken(ProductDetails p,String id){if(PLUS.equals(id)){List<ProductDetails.SubscriptionOfferDetails> x=p.getSubscriptionOfferDetails();return x==null||x.isEmpty()?null:x.get(0).getOfferToken();}List<ProductDetails.OneTimePurchaseOfferDetails> x=p.getOneTimePurchaseOfferDetailsList();return x==null||x.isEmpty()?null:x.get(0).getOfferToken();}
    public void restore(){if(!connected)return;queryOwned(BillingClient.ProductType.INAPP);queryOwned(BillingClient.ProductType.SUBS);}
    private void queryOwned(String type){billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build(),(result,purchases)->{if(result.getResponseCode()==BillingClient.BillingResponseCode.OK)for(Purchase p:purchases)process(p);});}
    @Override public void onPurchasesUpdated(BillingResult result,List<Purchase> purchases){if(result.getResponseCode()==BillingClient.BillingResponseCode.USER_CANCELED)return;if(result.getResponseCode()!=BillingClient.BillingResponseCode.OK||purchases==null){listener.message("Purchase not completed: "+result.getDebugMessage());return;}for(Purchase p:purchases)process(p);}
    private void process(Purchase purchase){if(purchase.getPurchaseState()==Purchase.PurchaseState.PENDING){listener.message("Purchase pending. Access starts after Google confirms payment.");return;}if(purchase.getPurchaseState()!=Purchase.PurchaseState.PURCHASED)return;for(String id:purchase.getProducts())if(FOUNDER.equals(id)||PRO.equals(id)||PLUS.equals(id)){rider.verifyPlayPurchase(id,purchase.getPurchaseToken(),(ok,message,body)->{listener.message(ok?"Purchase verified • access restored":message);if(ok)listener.entitlementChanged();});return;}}
    private String hash(String value){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x));return s.toString();}catch(Exception e){return Integer.toHexString(value.hashCode());}}
    public void close(){if(billing!=null)billing.endConnection();}
}
