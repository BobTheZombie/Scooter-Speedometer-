# RiderLink social sign-in setup

The Android client supports Google and Facebook through Supabase Auth using the
`riderlink://auth-callback` deep link and PKCE. Provider secrets are never stored
in the APK.

## Supabase

1. Open **Authentication → URL Configuration**.
2. Add `riderlink://auth-callback` to **Redirect URLs**.
3. Open **Authentication → Sign In / Providers**.
4. Enable Google and Facebook and paste the credentials created below.

## Google

Create a Web OAuth client in Google Cloud. Add this Supabase callback URL as an
authorized redirect URI:

`https://jwjiujxdcybsjmxqjojl.supabase.co/auth/v1/callback`

Paste the Google client ID and secret into the Supabase Google provider.

## Facebook

Create a Consumer app in Meta for Developers, add Facebook Login, and add this
valid OAuth redirect URI:

`https://jwjiujxdcybsjmxqjojl.supabase.co/auth/v1/callback`

Paste the Facebook app ID and secret into the Supabase Facebook provider. The
app must be switched to Live before people outside the developer/tester list can
sign in.

Email/password RiderLink accounts remain available as a fallback.
