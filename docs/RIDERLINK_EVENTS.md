# RiderLink+ Events

Open **RiderLink → Social → Events**. The **Local board** shows upcoming public events and supports a town/city substring filter. A blank filter shows all towns. **Invitations** contains private and public invitations, including declined invitations. **My events** contains hosted events and events marked Going or Interested. Lists load 30 events at a time. Invitations and My events retain ended events for seven days.

Plus members and existing developer accounts can host events. Browsing and responding are free for signed-in riders. Hosts choose a title, description, town/city, meeting address, start/end time, and public or invite-only visibility. The default is invite-only. Times are selected and displayed in the device timezone and stored as UTC instants.

Hosts can edit future events, view RSVPs, invite friends or an exact RiderLink username, and cancel events. Public posts also support invitations. Invitations never automatically RSVP a rider. Repeated invitations preserve the rider's response. Invitation acceptance/decline appears in the Events inbox; this release does not send push notifications. Riders must refresh to see invitations, edits and cancellations.

Public posts, including the meeting address, are visible to all signed-in RiderLink riders. Private posts are visible only to their host and invited riders. Only the host sees the named guest list; visible event cards show RSVP totals. Visibility is fixed after posting to prevent an edit from exposing a private guest list or unexpectedly hiding an existing public event. Cancel and create a new event to change visibility. There is a limit of 20 upcoming hosted events and 200 invitations per event.

## Backend rollout

Run `supabase/riderlink_events.sql` in the existing Supabase project after `riderlink.sql` and `riderlink_membership_v86.sql`. The new migration is transactional and repeatable. It adds only event tables and functions. It does not change memberships, authentication configuration, existing keys or the signing identity.

The event tables have RLS enabled and no direct client grants. Checked security-definer RPCs enforce ownership, visibility, membership, RSVP identity, dates and optimistic revisions. Definer functions pin their search path so temporary table names cannot shadow application tables. Authentication is required even for public board access. A lapsed host can still manage an existing event.

## Build and signing

This change uses the existing Java 17 / Gradle 8.9 / Android SDK 35 build configuration and application ID. The permanent encrypted v8 signing key and existing release workflow remain intact. The feature CI builds the separate `.v8test` debug application; it is not a production upgrade. The v8.9.0 production release uses versionCode 94 and the existing `V8_SIGNING_PASSWORD` signing setup. No replacement signing key is generated.

## Validation

`RiderLink Events checks` builds the Android debug APK and runs the real baseline, membership and events migrations in a disposable PostgreSQL 16 database. Permission tests cover anonymous denial, free/expired-host denial, developer hosting, private discovery and guessed-ID denial, direct table denial, ownership, public/free RSVPs, repeated invites/RSVPs, decline persistence, city filters, edits/revisions and cancellation visibility. The events migration is applied twice to check repeatability.

Device checks before release: create one public and one private event; invite a second account; accept/decline from that account; try discovery from an uninvited account; edit and cancel from the host; check dates in two device timezones; verify long descriptions and narrow-screen scrolling. No live Supabase changes or real invitations are sent by the automated tests.

Local validation: the actual baseline, membership and event migrations (including a repeat application) and `tests/events_permissions.sql` passed using PGlite with pgcrypto. Tests also cover hostile temporary table names. The GitHub PostgreSQL 16 job remains to be run after branch upload.

Android validation: `gradle --no-daemon :app:assembleDebug` passed locally with Java 17, Gradle 8.9 and Android SDK 35 (31 tasks). This verifies compilation and APK packaging, not on-device behavior. Production signing and live Supabase rollout have not been performed.
