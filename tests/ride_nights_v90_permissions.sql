\set ON_ERROR_STOP on
-- Run after the existing event permission test in the same disposable database.
insert into auth.users(id) values('00000000-0000-0000-0000-000000000005') on conflict do nothing;
insert into public.profiles(id,username) values('00000000-0000-0000-0000-000000000005','Helper') on conflict do nothing;

set role authenticated;
do $$
declare event_id bigint;route_a bigint;route_b bigint;request_id bigint;report_id bigint;pass text;old_pass text;snapshot jsonb;rep jsonb;center jsonb;meet bigint;
begin
 perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000004',false);
 event_id=save_rider_event(null,0,'Friday Ride Night','Test ride','Coloma, MI','Coloma Park',now()+interval '2 hours',now()+interval '6 hours','public');
 perform invite_rider_to_event(event_id,'Guest');
 perform invite_rider_to_event(event_id,'Helper');
 route_a=propose_ride_route(event_id,'Lake loop','Paw Paw Lake',20);
 perform vote_ride_route(event_id,route_a);
 perform lock_ride_route(event_id,route_a);
 perform post_ride_night_chat(event_id,'Kickstands up at 8!',true);
 meet=set_emergency_meet_point(event_id,'Safe regroup','Coloma Park north lot');
 assert meet is not null;
 snapshot=ride_night_snapshot(event_id);
 assert jsonb_array_length(snapshot->'routes')=1 and (snapshot->'routes'->0->>'locked')::boolean;

 perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',false);
 perform respond_rider_event(event_id,'going');
 route_b=propose_ride_route(event_id,'Backroads','Watervliet, MI',15);
 perform vote_ride_route(event_id,route_b);
 perform post_ride_night_chat(event_id,'I vote backroads',false);
 perform test_event_denied(format('select post_ride_night_chat(%s,%L,true)',event_id,'fake host'),'Only the host');
 perform test_event_denied(format('select lock_ride_route(%s,%s)',event_id,route_b),'Only the host');
 perform check_in_ride_night(event_id,null);
 pass=my_ride_pass(false);
 assert length(pass)=10 and pass=my_ride_pass(false),'Ride Pass must be stable until rotation';
 old_pass=pass;pass=my_ride_pass(true);assert old_pass<>pass,'Ride Pass rotation must invalidate the old pass';
 rep=my_rider_reputation(null);
 assert (rep->>'rides_attended')::integer=1;

 perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',false);
 perform test_event_denied(format('select propose_ride_route(%s,%L,%L,null)',event_id,'Sneak','Nowhere'),'RSVP Going');
 perform test_event_denied(format('select check_in_ride_night(%s,null)',event_id),'RSVP Going');
 assert jsonb_array_length(discover_ride_nights('Coloma',0))>=1;
 perform test_event_denied(format('select check_in_ride_night(%s,%L)',event_id,pass),'Only the host');

 perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000004',false);
 perform check_in_ride_night(event_id,pass);
 request_id=request_rider_rescue('mechanical','Belt problem',42.1,-86.3,'Coloma');
 perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000005',false);
 perform respond_rider_event(event_id,'going');
 perform offer_rider_rescue(request_id,'I have tools and a spare belt');
 assert (select count(*) from nearby_rescue_requests(42.1,-86.3,40) where id=request_id)=1;
 perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000004',false);
 perform update_rescue_offer(request_id,'00000000-0000-0000-0000-000000000005','completed');
 center=my_rescue_center();assert not (center->'requests'->0->>'active')::boolean;
 perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000005',false);
 rep=my_rider_reputation(null);assert (rep->>'rescue_helps')::integer=1;

 report_id=report_road_intel('gravel',2,'Loose gravel',42.1,-86.3);
 perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',false);
 perform vote_road_intel(report_id,true);
 assert (select confirmations from nearby_road_intel(42.1,-86.3,15) where id=report_id)=2;

 perform test_event_denied('select * from ride_passes','permission denied');
 perform test_event_denied('select * from rescue_requests','permission denied');
 perform test_event_denied('select * from road_intel_reports','permission denied');
end $$;
reset role;
set role anon;
select test_event_denied('select discover_ride_nights()','permission denied');
reset role;
select 'Ride Nights v9.0 security and lifecycle tests passed' result;
