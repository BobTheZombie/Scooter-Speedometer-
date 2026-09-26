# Google Play release setup

## Products

Create these exact product IDs in Play Console. Prices shown in the app are read from Google Play and localized automatically.

| Product ID | Type | Price | Availability |
|---|---|---:|---|
| `rider_pro_founder_lifetime` | One-time, non-consumable | $9.99 | Founding 1,000 only |
| `rider_pro_lifetime` | One-time, non-consumable | $4.99 | Enable after founder sellout |
| `riderlink_plus_monthly` | Auto-renewing subscription | $6.99/month | Enable after founder sellout |

For `riderlink_plus_monthly`, create a monthly auto-renewing base plan. Do not enable multi-quantity for either one-time product.

## Supabase deployment

1. Run `supabase/riderlink_play_billing_v92.sql` after the earlier RiderLink migrations.
2. Link the Supabase CLI and deploy `verify-play-purchase`.
3. Add Edge Function secrets:
   - `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: the complete service-account JSON with Android Publisher API access.
   - `PLAY_PACKAGE_NAME=com.stankhouse.scooterspeedometer`
   - Standard Supabase `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` secrets.
4. In Google Play Console, grant the service account permission to view orders/subscriptions and manage orders so purchases can be verified and acknowledged.
5. Configure Real-time Developer Notifications before production so renewals, cancellations, grace periods, refunds, and revocations are reconciled even when the app is closed.

The service-role key and Google credentials must never be stored in the APK or committed to GitHub.

## Release artifacts

Upload the signed `Scooter-Speedometer-v9.2.0-play.aab` to an internal Play test track. The GitHub APK remains a separate flavor with the built-in updater. The Play flavor removes `REQUEST_INSTALL_PACKAGES` and directs updates through Google Play.

## Required Play Console work

- Complete the App content, Data safety, privacy-policy URL, ads, content-rating, target-audience, and account-deletion declarations.
- Declare precise/background location use for live rides, groups, SOS, Safety Circle, navigation, and crash check-ins. Prominent in-app disclosure and consent must precede background collection.
- Review notification-listener, accessibility-service, microphone, overlay, and package-install behavior against Play policy. The accessibility service is limited to user-enabled DoorDash offer capture and must not perform autonomous actions.
- Supply reviewer instructions and a test RiderLink account for gated social features.
- Run internal testing first. New personal Play developer accounts may also require a closed test before production eligibility.
- Enroll the app in Play App Signing while retaining the permanent RiderLink upload key.

## Founder transition

The backend is the source of truth for the 1,000 verified founder purchases. When `founder_remaining` reaches zero, deactivate `rider_pro_founder_lifetime` in Play Console and activate `rider_pro_lifetime` plus `riderlink_plus_monthly`. The app switches its offer screen from the founder bundle to the separate products using the server counter.
