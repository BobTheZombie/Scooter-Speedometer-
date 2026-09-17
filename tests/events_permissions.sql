\set ON_ERROR_STOP on
insert into auth.users(id) values
 ('00000000-0000-0000-0000-000000000001'),
 ('00000000-0000-0000-0000-000000000002'),
 ('00000000-0000-0000-0000-000000000003'),
 ('00000000-0000-0000-0000-000000000004');
insert into public.profiles(id,username) values
 ('00000000-0000-0000-0000-000000000001','Host'),
 ('00000000-0000-0000-0000-000000000002','Guest'),
 ('00000000-0000-0000-0000-000000000003','Stranger'),
 ('00000000-0000-0000-0000-000000000004','Developer');
insert into public.riderlink_entitlements(user_id,tier,plus_access)
 values ('00000000-0000-0000-0000-000000000001','founder_lifetime',true);
insert into public.riderlink_admins(user_id) values('00000000-0000-0000-0000-000000000004');

create function public.test_event_denied(statement text, expected text) returns void language plpgsql as $$
begin
  begin
    execute statement;
  exception when others then
    if position(expected in sqlerrm)>0 then return; end if;
    raise exception 'Unexpected error: %; expected %',sqlerrm,expected;
  end;
  raise exception 'Expected failure: %',statement;
end $$;
grant execute on function public.test_event_denied(text,text) to authenticated,anon;

set role authenticated;
-- Hostile temporary names must not shadow the real tables inside definer RPCs.
create temporary table rider_events(id bigint);
create temporary table profiles(id uuid,username text);
do $$
declare private_id bigint;public_id bigint;developer_id bigint;result jsonb;n integer;
begin
  perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',false);
  perform test_event_denied($q$select save_rider_event(null,0,'Free event','','Coloma','Main Street',now()+interval '1 day',now()+interval '2 days','public')$q$,'requires RiderLink+');
  perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',false);
  private_id=save_rider_event(null,0,'Private ride','Private details','Watervliet, MI','Private meeting address',now()+interval '1 day',now()+interval '2 days','invite_only');
  public_id=save_rider_event(null,0,'Public meetup','All riders welcome','Coloma, MI','Public park, Main Street',now()+interval '3 days',now()+interval '4 days','public');
  perform test_event_denied($q$select save_rider_event(null,0,'Bad date','','Coloma','Main Street',now()-interval '1 day',now()+interval '2 days','public')$q$,'future start');
  perform test_event_denied($q$select save_rider_event(null,0,'Bad visibility','','Coloma','Main Street',now()+interval '1 day',now()+interval '2 days',null)$q$,'Invalid visibility');
  perform test_event_denied($q$select save_rider_event(null,0,'Bad title',null,'Coloma','Main Street',now()+interval '1 day',now()+interval '2 days','public')$q$,'Check the event');
  assert jsonb_array_length(list_rider_events('mine'))=2,'Host must see both events';
  assert jsonb_array_length(list_rider_events('public','coloma'))=1,'City search should be case insensitive';
  assert jsonb_array_length(list_rider_events('public','%'))=0,'Search must treat wildcard literally';
  assert jsonb_array_length(list_rider_events('public','',30))=0,'Offset must work';
  perform invite_rider_to_event(private_id,'gUeSt');
  perform test_event_denied(format('select invite_rider_to_event(%s,%L)',private_id,'Missing'),'unique, exact');
  perform test_event_denied(format('select invite_rider_to_event(%s,%L)',private_id,'Host'),'already the host');

  perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',false);
  result=list_rider_events('public');
  assert jsonb_array_length(result)=1 and (result->0->>'id')::bigint=public_id,'Private event leaked on public board';
  assert jsonb_array_length(list_rider_events('invited'))=0,'Private invitation leaked';
  assert jsonb_array_length(list_rider_events('mine'))=0,'Private event leaked into my events';
  perform test_event_denied(format('select respond_rider_event(%s,%L)',private_id,'going'),'Event unavailable');
  perform test_event_denied(format('select invite_rider_to_event(%s,%L)',public_id,'Guest'),'Only the host');
  perform test_event_denied(format('select cancel_rider_event(%s)',private_id),'Only the host');
  perform test_event_denied(format('select * from rider_event_guest_list(%s)',public_id),'Only the host');
  perform test_event_denied(format($q$select save_rider_event(%s,1,'Hijacked','','Coloma','Main Street',now()+interval '1 day',now()+interval '2 days','public')$q$,public_id),'not the host');
  perform test_event_denied('select * from public.rider_events','permission denied');
  perform test_event_denied('select * from public.rider_event_riders','permission denied');
  perform test_event_denied(format($q$insert into rider_event_riders(event_id,rider_id,invited) values(%s,auth.uid(),true)$q$,private_id),'permission denied');
  perform respond_rider_event(public_id,'going');
  perform respond_rider_event(public_id,'going');
  result=list_rider_events('mine');
  assert jsonb_array_length(result)=1 and (result->0->>'going_count')::integer=1,'Duplicate RSVP must not add attendees';

  perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',false);
  result=list_rider_events('invited');
  assert jsonb_array_length(result)=1 and result->0->>'my_response'='pending','Invitation not delivered';
  perform respond_rider_event(private_id,'going');
  perform test_event_denied(format('select respond_rider_event(%s,NULL)',private_id),'Invalid RSVP');
  perform respond_rider_event(private_id,'declined');
  assert list_rider_events('invited')->0->>'my_response'='declined','Decline must remain visible';

  perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',false);
  perform invite_rider_to_event(private_id,'Guest');
  select count(*) into n from rider_event_guest_list(private_id) where response='declined';
  assert n=1,'Repeat invitation must not reset a decline';
  perform invite_rider_to_event(public_id,'Guest');
  perform save_rider_event(private_id,1,'Updated private ride','','Watervliet','New meeting address',now()+interval '1 day',now()+interval '2 days','invite_only');
  perform test_event_denied(format($q$select save_rider_event(%s,1,'Stale edit','','Watervliet','Main Street',now()+interval '1 day',now()+interval '2 days','invite_only')$q$,private_id),'Event changed');
  perform test_event_denied(format($q$select save_rider_event(%s,2,'Visibility edit','','Watervliet','Main Street',now()+interval '1 day',now()+interval '2 days','public')$q$,private_id),'Visibility cannot');
  perform cancel_rider_event(private_id);
  perform cancel_rider_event(public_id);
  assert jsonb_array_length(list_rider_events('public'))=0,'Cancelled events should leave public board';
  perform test_event_denied(format('select invite_rider_to_event(%s,%L)',private_id,'Stranger'),'Only the host');
  perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',false);
  result=list_rider_events('invited');
  assert jsonb_array_length(result)=2 and (result->0->>'cancelled')::boolean,'Invited riders must see cancellations';
  perform test_event_denied(format('select respond_rider_event(%s,%L)',private_id,'going'),'closed');
  perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',false);
  assert (list_rider_events('mine')->0->>'cancelled')::boolean,'Public RSVP riders must see cancellations';

  perform set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000004',false);
  developer_id=save_rider_event(null,0,'Developer event','','Coloma','Main Street',now()+interval '1 day',now()+interval '2 days','public');
  assert developer_id is not null,'Developer access must work';
  perform set_config('request.jwt.claim.sub','',false);
  perform test_event_denied('select list_rider_events()','Sign in first');
end $$;
reset role;
-- An expired entitlement cannot create, but the host can still maintain/cancel existing events.
update riderlink_entitlements set expires_at=now()-interval '1 day' where user_id='00000000-0000-0000-0000-000000000001';
set role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',false);
select test_event_denied($q$select save_rider_event(null,0,'Expired member','','Coloma','Main Street',now()+interval '1 day',now()+interval '2 days','public')$q$,'requires RiderLink+');
reset role;
set role anon;
select test_event_denied('select list_rider_events()','permission denied');
reset role;
select 'Event permissions and lifecycle tests passed' as result;
