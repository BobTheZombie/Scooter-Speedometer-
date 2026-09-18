# RiderLink v9.0 Ride Nights

Ride Nights turns a scheduled RiderLink Event into a complete before/during/after ride flow.

## Features

- **Smart Event Discovery** ranks public events by city match, friend-hosted rides, attendance and how soon they start. It never exposes invite-only events.
- **Ride Night Hub** gives accessible event members chat, host announcements, route proposals, one-vote-per-rider voting, host route locking, check-in, meetup navigation, Convoy launch and an emergency regroup point.
- **Rider Reputation** is earned from verified check-ins, completed hosted events, completed roadside help and confirmed road reports. Riders cannot rate or downvote each other.
- **Mechanical Rescue Network** publishes an exact, opt-in location for up to two hours. Nearby riders can offer tools, fuel, mechanical assistance, a battery or a trailer. Only the requester can accept, decline or mark help completed. It is not emergency dispatch.
- **Ride Pass** is a rotating personal QR code plus a 10-character fallback. A Ride Night host can check a rider in with the code. Rotation immediately invalidates the old pass.
- **Road Condition Intelligence** handles rough pavement, gravel, flooding, construction, dangerous intersections, high-speed roads, closures and debris. Reports expire based on type, can be confirmed/cleared by other riders, and feed spoken warnings into active OpenNAV+ guidance.
- **Emergency Meet-Point** is host-controlled and broadcasts a pinned announcement with one-tap navigation.
- **Event Chat** is access-controlled to riders who can see the event, rate limited, and supports host-only announcements.

## Database rollout

Run `supabase/riderlink_ride_nights_v90.sql` after these existing migrations:

1. `riderlink.sql`
2. `riderlink_membership_v86.sql`
3. `riderlink_convoy_v87.sql`
4. `riderlink_events.sql`
5. `riderlink_ride_nights_v90.sql`

The migration is transactional and repeatable. Tables have RLS enabled and no direct client grants. Authenticated RPC functions enforce event visibility, participation, host ownership, rate limits and identity binding.

## Privacy and safety

- A public event is visible only to signed-in RiderLink users. Invite-only events remain limited to the host and invited/participating riders.
- Rescue requests share exact coordinates for at most two hours and close when help is completed or a new request replaces them.
- Ride Pass codes contain no account data; the server resolves them. Rotate a code that was exposed.
- Rider Reputation has no user-authored reviews or star ratings.
- Road reports are community information, not a guarantee that a road is safe.
- Rescue Network and Emergency Meet-Points do not call 911 or guarantee a response.

## Verification

- Actual baseline, membership, Events and v9.0 migrations pass in a disposable PostgreSQL-compatible database, including repeat migration application.
- Security/lifecycle tests cover public/private discovery, route voting, host-only actions, chat, check-ins, rotating passes, reputation, rescue permissions, road confirmations and direct-table denial.
- Android debug compilation and APK packaging pass with Java 17, Gradle 8.9 and Android SDK 35.
- Production release version: `9.0.0`, versionCode `100`, signed with the existing permanent v8 identity.
