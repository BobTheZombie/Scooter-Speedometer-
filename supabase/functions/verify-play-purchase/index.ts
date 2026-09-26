import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const headers={"content-type":"application/json"};
const allowed=new Set(["rider_pro_founder_lifetime","rider_pro_lifetime","riderlink_plus_monthly"]);
const encoder=new TextEncoder();

Deno.serve(async(req)=>{
  if(req.method!=="POST")return reply(405,{error:"POST required"});
  try{
    const auth=req.headers.get("authorization")||"";
    if(!auth.startsWith("Bearer "))return reply(401,{error:"Sign in to RiderLink first"});
    const url=need("SUPABASE_URL"),anon=need("SUPABASE_ANON_KEY"),service=need("SUPABASE_SERVICE_ROLE_KEY");
    const userClient=createClient(url,anon,{global:{headers:{Authorization:auth}}});
    const{data:{user},error:userError}=await userClient.auth.getUser();
    if(userError||!user)return reply(401,{error:"RiderLink session expired"});
    const body=await req.json(),product=String(body.product_id||""),token=String(body.purchase_token||"");
    if(!allowed.has(product)||token.length<20)return reply(400,{error:"Invalid Play purchase"});
    const accountHash=await sha256(user.id),tokenHash=await sha256(token),packageName=Deno.env.get("PLAY_PACKAGE_NAME")||"com.stankhouse.scooterspeedometer";
    const googleToken=await googleAccessToken();
    let orderId:string|null=null,expiresAt:string|null=null,autoRenewing=false,state="active",verified:any;
    if(product==="riderlink_plus_monthly"){
      verified=await googleJson(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`,googleToken);
      const line=(verified.lineItems||[]).find((x:any)=>x.productId===product)||verified.lineItems?.[0];
      expiresAt=line?.expiryTime||null;autoRenewing=Boolean(line?.autoRenewingPlan?.autoRenewEnabled);
      const validStates=new Set(["SUBSCRIPTION_STATE_ACTIVE","SUBSCRIPTION_STATE_IN_GRACE_PERIOD","SUBSCRIPTION_STATE_CANCELED"]);
      if(!line||line.productId!==product||!expiresAt||new Date(expiresAt)<=new Date()||!validStates.has(verified.subscriptionState))throw new Error("RiderLink+ subscription is not active");
      state=verified.subscriptionState==="SUBSCRIPTION_STATE_IN_GRACE_PERIOD"?"grace":"active";
      orderId=line.latestSuccessfulOrderId||null;
      if(verified.externalAccountIdentifiers?.obfuscatedExternalAccountId!==accountHash)throw new Error("Purchase belongs to a different RiderLink account");
    }else{
      verified=await googleJson(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/products/${encodeURIComponent(product)}/tokens/${encodeURIComponent(token)}`,googleToken);
      if(Number(verified.purchaseState)!==0)throw new Error("Google Play has not completed this purchase");
      if(verified.obfuscatedExternalAccountId!==accountHash)throw new Error("Purchase belongs to a different RiderLink account");
      orderId=verified.orderId||null;
    }
    const admin=createClient(url,service,{auth:{persistSession:false}});
    const{data,error}=await admin.rpc("grant_verified_play_purchase",{p_user:user.id,p_product:product,p_token_hash:tokenHash,p_order_id:orderId,p_expires_at:expiresAt,p_auto_renewing:autoRenewing,p_state:state});
    if(error)throw new Error(error.message);
    await acknowledge(packageName,product,token,googleToken);
    return reply(200,{verified:true,entitlement:data});
  }catch(error){return reply(400,{error:error instanceof Error?error.message:"Purchase verification failed"});}
});

function need(name:string){const value=Deno.env.get(name);if(!value)throw new Error(`${name} is not configured`);return value;}
function reply(status:number,body:unknown){return new Response(JSON.stringify(body),{status,headers});}
async function sha256(value:string){const bytes=new Uint8Array(await crypto.subtle.digest("SHA-256",encoder.encode(value)));return Array.from(bytes,x=>x.toString(16).padStart(2,"0")).join("");}
function b64url(value:string|Uint8Array){const bytes=typeof value==="string"?encoder.encode(value):value;let binary="";for(const b of bytes)binary+=String.fromCharCode(b);return btoa(binary).replace(/=/g,"").replace(/\+/g,"-").replace(/\//g,"_");}
async function googleAccessToken(){const service=JSON.parse(need("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")),now=Math.floor(Date.now()/1000);const header=b64url(JSON.stringify({alg:"RS256",typ:"JWT"})),payload=b64url(JSON.stringify({iss:service.client_email,scope:"https://www.googleapis.com/auth/androidpublisher",aud:"https://oauth2.googleapis.com/token",iat:now,exp:now+3600})),unsigned=`${header}.${payload}`;const pem=String(service.private_key).replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,"");const raw=Uint8Array.from(atob(pem),c=>c.charCodeAt(0));const key=await crypto.subtle.importKey("pkcs8",raw,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]);const signature=new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,encoder.encode(unsigned)));const response=await fetch("https://oauth2.googleapis.com/token",{method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",assertion:`${unsigned}.${b64url(signature)}`})});const json=await response.json();if(!response.ok||!json.access_token)throw new Error("Google Play service account authorization failed");return json.access_token as string;}
async function googleJson(url:string,token:string){const response=await fetch(url,{headers:{authorization:`Bearer ${token}`}}),json=await response.json();if(!response.ok)throw new Error(json?.error?.message||"Google Play could not verify this purchase");return json;}
async function acknowledge(packageName:string,product:string,token:string,access:string){const type=product==="riderlink_plus_monthly"?"subscriptions":"products";const url=`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/${type}/${encodeURIComponent(product)}/tokens/${encodeURIComponent(token)}:acknowledge`;const response=await fetch(url,{method:"POST",headers:{authorization:`Bearer ${access}`,"content-type":"application/json"},body:"{}"});if(!response.ok&&response.status!==409){const body=await response.text();throw new Error(`Purchase granted but Google acknowledgement failed: ${body}`);}}
