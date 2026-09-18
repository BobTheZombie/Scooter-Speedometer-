-- RiderLink v9.0 Ride Nights. Run after riderlink_events.sql and the v8.7/v8.6 migrations.
-- Safe to re-run. All client access is through authenticated, checked RPC functions.
begin;

create table if not exists public.ride_night_chat (
  id bigint generated always as identity primary key,
  event_id bigint not null references public.rider_events(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  body text not null check(char_length(trim(body)) between 1 and 500),
  announcement boolean not null default false,
  created_at timestamptz not null default now()
);
create index if not exists ride_night_chat_event_time on public.ride_night_chat(event_id,created_at desc);

create table if not exists public.ride_route_options (
  id bigint generated always as identity primary key,
  event_id bigint not null references public.rider_events(id) on delete cascade,
  proposed_by uuid not null references public.profiles(id) on delete cascade,
  label text not null check(char_length(trim(label)) between 3 and 80),
  destination text not null check(char_length(trim(destination)) between 3 and 240),
  distance_miles numeric(7,1) check(distance_miles is null or distance_miles between 0 and 1000),
  locked boolean not null default false,
  created_at timestamptz not null default now()
);
create unique index if not exists one_locked_route_per_event on public.ride_route_options(event_id) where locked;

create table if not exists public.ride_route_votes (
  option_id bigint not null references public.ride_route_options(id) on delete cascade,
  rider_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(option_id,rider_id)
);

create table if not exists public.ride_night_checkins (
  event_id bigint not null references public.rider_events(id) on delete cascade,
  rider_id uuid not null references public.profiles(id) on delete cascade,
  checked_in_at timestamptz not null default now(),
  primary key(event_id,rider_id)
);

create table if not exists public.ride_passes (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  pass_code text not null unique check(pass_code ~ '^[A-Z0-9]{10}$'),
  rotated_at timestamptz not null default now()
);

create table if not exists public.rescue_requests (
  id bigint generated always as identity primary key,
  requester_id uuid not null references public.profiles(id) on delete cascade,
  need text not null check(need in ('tools','fuel','mechanical','trailer','battery','other')),
  note text not null default '' check(char_length(note)<=300),
  latitude double precision not null check(latitude between -90 and 90),
  longitude double precision not null check(longitude between -180 and 180),
  city text not null default '' check(char_length(city)<=100),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now()+interval '2 hours',
  closed_at timestamptz
);
create index if not exists rescue_active_time on public.rescue_requests(active,expires_at);

create table if not exists public.rescue_offers (
  request_id bigint not null references public.rescue_requests(id) on delete cascade,
  helper_id uuid not null references public.profiles(id) on delete cascade,
  message text not null check(char_length(trim(message)) between 1 and 240),
  status text not null default 'offered' check(status in ('offered','accepted','declined','completed')),
  created_at timestamptz not null default now(),
  primary key(request_id,helper_id)
);

create table if not exists public.road_intel_reports (
  id bigint generated always as identity primary key,
  reporter_id uuid not null references public.profiles(id) on delete cascade,
  report_type text not null check(report_type in ('rough','gravel','flooding','construction','dangerous_intersection','high_speed','closure','debris')),
  severity smallint not null check(severity between 1 and 3),
  note text not null default '' check(char_length(note)<=240),
  latitude double precision not null check(latitude between -90 and 90),
  longitude double precision not null check(longitude between -180 and 180),
  confirmations integer not null default 1 check(confirmations>=0),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index if not exists road_intel_active_geo on public.road_intel_reports(active,expires_at,latitude,longitude);

create table if not exists public.road_intel_votes (
  report_id bigint not null references public.road_intel_reports(id) on delete cascade,
  rider_id uuid not null references public.profiles(id) on delete cascade,
  accurate boolean not null,
  created_at timestamptz not null default now(),
  primary key(report_id,rider_id)
);

create table if not exists public.emergency_meet_points (
  id bigint generated always as identity primary key,
  event_id bigint references public.rider_events(id) on delete cascade,
  convoy_id bigint references public.group_rides(id) on delete cascade,
  created_by uuid not null references public.profiles(id) on delete cascade,
  label text not null check(char_length(trim(label)) between 3 and 100),
  address text not null check(char_length(trim(address)) between 3 and 240),
  latitude double precision check(latitude between -90 and 90),
  longitude double precision check(longitude between -180 and 180),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now()+interval '4 hours',
  check(event_id is not null or convoy_id is not null)
);

alter table public.ride_night_chat enable row level security;
alter table public.ride_route_options enable row level security;
alter table public.ride_route_votes enable row level security;
alter table public.ride_night_checkins enable row level security;
alter table public.ride_passes enable row level security;
alter table public.rescue_requests enable row level security;
alter table public.rescue_offers enable row level security;
alter table public.road_intel_reports enable row level security;
alter table public.road_intel_votes enable row level security;
alter table public.emergency_meet_points enable row level security;

revoke all on public.ride_night_chat,public.ride_route_options,public.ride_route_votes,
  public.ride_night_checkins,public.ride_passes,public.rescue_requests,public.rescue_offers,
  public.road_intel_reports,public.road_intel_votes,public.emergency_meet_points from public,anon,authenticated;

create or replace function public.can_access_ride_night(p_event bigint)
returns boolean language sql stable security definer set search_path=public,pg_temp as $$
 select auth.uid() is not null and exists(
   select 1 from rider_events e left join rider_event_riders r on r.event_id=e.id and r.rider_id=auth.uid()
   where e.id=p_event and (e.visibility='public' or e.host_id=auth.uid() or r.invited or r.response in('going','interested'))
 );
$$;

create or replace function public.is_ride_night_participant(p_event bigint)
returns boolean language sql stable security definer set search_path=public,pg_temp as $$
 select auth.uid() is not null and exists(
   select 1 from rider_events e left join rider_event_riders r on r.event_id=e.id and r.rider_id=auth.uid()
   where e.id=p_event and not e.cancelled and e.ends_at>now()-interval '12 hours'
     and (e.host_id=auth.uid() or r.response='going')
 );
$$;

create or replace function public.discover_ride_nights(p_city text default '',p_offset integer default 0)
returns jsonb language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 if p_offset is null or p_offset<0 then raise exception 'Invalid offset'; end if;
 return coalesce((select jsonb_agg(to_jsonb(x) order by score desc,starts_at) from(
   select e.id,e.host_id,e.title,e.description,e.city,e.meeting_place,e.starts_at,e.ends_at,e.visibility,e.revision,e.cancelled,e.updated_at,
     p.username host_name,coalesce(me.response,'none') my_response,
     (select count(*) from rider_event_riders r where r.event_id=e.id and r.response='going') going_count,
     (case when lower(e.city)=lower(trim(coalesce(p_city,''))) and trim(coalesce(p_city,''))<>'' then 80
       when strpos(lower(e.city),lower(trim(coalesce(p_city,''))))>0 and trim(coalesce(p_city,''))<>'' then 45 else 0 end
      + case when exists(select 1 from friendships f where f.status='accepted' and
        ((f.requester_id=auth.uid() and f.addressee_id=e.host_id) or (f.addressee_id=auth.uid() and f.requester_id=e.host_id))) then 35 else 0 end
      + least(30,(select count(*)::integer from rider_event_riders r where r.event_id=e.id and r.response='going')*3)
      + greatest(0,20-ceil(extract(epoch from(e.starts_at-now()))/86400)::integer)) as score
   from rider_events e join profiles p on p.id=e.host_id
   left join rider_event_riders me on me.event_id=e.id and me.rider_id=auth.uid()
   where e.visibility='public' and not e.cancelled and e.ends_at>now()
   order by score desc,e.starts_at limit 30 offset p_offset
 )x),'[]'::jsonb);
end $$;

create or replace function public.ride_night_snapshot(p_event bigint)
returns jsonb language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
 if not can_access_ride_night(p_event) then raise exception 'Ride Night unavailable'; end if;
 return jsonb_build_object(
  'event',(select to_jsonb(e) || jsonb_build_object('host_name',p.username) from rider_events e join profiles p on p.id=e.host_id where e.id=p_event),
  'routes',coalesce((select jsonb_agg(to_jsonb(x) order by locked desc,votes desc,id) from(
    select o.id,o.label,o.destination,o.distance_miles,o.locked,p.username proposed_by,
      count(v.rider_id) votes,bool_or(v.rider_id=auth.uid()) my_vote
    from ride_route_options o join profiles p on p.id=o.proposed_by left join ride_route_votes v on v.option_id=o.id
    where o.event_id=p_event group by o.id,p.username)x),'[]'::jsonb),
  'checkins',(select count(*) from ride_night_checkins where event_id=p_event),
  'meet_point',(select to_jsonb(m) from emergency_meet_points m where m.event_id=p_event and m.active and m.expires_at>now() order by m.created_at desc limit 1)
 );
end $$;

create or replace function public.post_ride_night_chat(p_event bigint,p_body text,p_announcement boolean default false)
returns bigint language plpgsql security definer set search_path=public,pg_temp as $$
declare eid bigint;host uuid;
begin
 if not can_access_ride_night(p_event) then raise exception 'Ride Night unavailable'; end if;
 select host_id into host from rider_events where id=p_event;
 if coalesce(p_announcement,false) and host<>auth.uid() then raise exception 'Only the host can post announcements'; end if;
 if p_body is null or char_length(trim(p_body)) not between 1 and 500 then raise exception 'Message must be 1-500 characters'; end if;
 if (select count(*) from ride_night_chat where sender_id=auth.uid() and created_at>now()-interval '1 minute')>=12 then raise exception 'Slow down before posting again'; end if;
 insert into ride_night_chat(event_id,sender_id,body,announcement) values(p_event,auth.uid(),trim(p_body),coalesce(p_announcement,false)) returning id into eid;return eid;
end $$;

create or replace function public.ride_night_messages(p_event bigint,p_after bigint default 0)
returns table(id bigint,username text,body text,announcement boolean,created_at timestamptz)
language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
 if not can_access_ride_night(p_event) then raise exception 'Ride Night unavailable'; end if;
 return query select c.id,p.username,c.body,c.announcement,c.created_at from ride_night_chat c join profiles p on p.id=c.sender_id
  where c.event_id=p_event and c.id>coalesce(p_after,0) order by c.id limit 100;
end $$;

create or replace function public.propose_ride_route(p_event bigint,p_label text,p_destination text,p_miles numeric default null)
returns bigint language plpgsql security definer set search_path=public,pg_temp as $$
declare oid bigint;
begin
 if not is_ride_night_participant(p_event) then raise exception 'RSVP Going before proposing a route'; end if;
 if p_label is null or char_length(trim(p_label)) not between 3 and 80 or p_destination is null or char_length(trim(p_destination)) not between 3 and 240 then raise exception 'Add a route name and destination'; end if;
 if (select count(*) from ride_route_options where event_id=p_event)>=10 then raise exception 'This Ride Night already has 10 route options'; end if;
 insert into ride_route_options(event_id,proposed_by,label,destination,distance_miles) values(p_event,auth.uid(),trim(p_label),trim(p_destination),p_miles) returning id into oid;return oid;
end $$;

create or replace function public.vote_ride_route(p_event bigint,p_option bigint)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if not is_ride_night_participant(p_event) then raise exception 'RSVP Going before voting'; end if;
 if not exists(select 1 from ride_route_options where id=p_option and event_id=p_event) then raise exception 'Route unavailable'; end if;
 delete from ride_route_votes v using ride_route_options o where v.option_id=o.id and o.event_id=p_event and v.rider_id=auth.uid();
 insert into ride_route_votes(option_id,rider_id) values(p_option,auth.uid());
end $$;

create or replace function public.lock_ride_route(p_event bigint,p_option bigint)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if not exists(select 1 from rider_events where id=p_event and host_id=auth.uid() and not cancelled) then raise exception 'Only the host can lock the route'; end if;
 if not exists(select 1 from ride_route_options where id=p_option and event_id=p_event) then raise exception 'Route unavailable'; end if;
 update ride_route_options set locked=false where event_id=p_event;
 update ride_route_options set locked=true where id=p_option;
end $$;

create or replace function public.check_in_ride_night(p_event bigint,p_pass text default null)
returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$
declare rider uuid;host uuid;
begin
 select host_id into host from rider_events where id=p_event and not cancelled and starts_at<now()+interval '12 hours' and ends_at>now()-interval '12 hours';
 if host is null then raise exception 'Check-in is not open'; end if;
 if p_pass is null or trim(p_pass)='' then
   if not is_ride_night_participant(p_event) then raise exception 'RSVP Going before checking in'; end if;rider=auth.uid();
 else
   if host<>auth.uid() then raise exception 'Only the host can scan another rider pass'; end if;
   select user_id into rider from ride_passes where pass_code=upper(trim(p_pass));
   if rider is null then raise exception 'Ride Pass not found'; end if;
   insert into rider_event_riders(event_id,rider_id,response,invited) values(p_event,rider,'going',true)
     on conflict(event_id,rider_id) do update set response='going',updated_at=now();
 end if;
 insert into ride_night_checkins(event_id,rider_id) values(p_event,rider) on conflict do nothing;
 return jsonb_build_object('rider_id',rider,'checked_in',true);
end $$;

create or replace function public.my_ride_pass(p_rotate boolean default false)
returns text language plpgsql security definer set search_path=public,pg_temp as $$
declare code text;
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 if coalesce(p_rotate,false) then delete from ride_passes where user_id=auth.uid(); end if;
 select pass_code into code from ride_passes where user_id=auth.uid();
 if code is null then
   loop
    code=upper(substr(encode(extensions.gen_random_bytes(8),'hex'),1,10));
    begin insert into ride_passes(user_id,pass_code) values(auth.uid(),code);exit;exception when unique_violation then end;
   end loop;
 end if;
 return code;
end $$;

create or replace function public.my_rider_reputation(p_user uuid default null)
returns jsonb language plpgsql stable security definer set search_path=public,pg_temp as $$
declare target uuid=coalesce(p_user,auth.uid());
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 return (select jsonb_build_object('user_id',p.id,'username',p.username,
   'rides_attended',(select count(*) from ride_night_checkins c where c.rider_id=p.id),
   'rides_hosted',(select count(*) from rider_events e where e.host_id=p.id and e.ends_at<now() and not e.cancelled),
   'rescue_helps',(select count(*) from rescue_offers o where o.helper_id=p.id and o.status='completed'),
   'road_reports',(select count(*) from road_intel_reports r where r.reporter_id=p.id and r.confirmations>=2),
   'reputation_score',least(999,
     (select count(*)*5 from ride_night_checkins c where c.rider_id=p.id)+
     (select count(*)*8 from rider_events e where e.host_id=p.id and e.ends_at<now() and not e.cancelled)+
     (select count(*)*12 from rescue_offers o where o.helper_id=p.id and o.status='completed')+
     (select count(*)*3 from road_intel_reports r where r.reporter_id=p.id and r.confirmations>=2)))
   from profiles p where p.id=target);
end $$;

create or replace function public.request_rider_rescue(p_need text,p_note text,p_lat double precision,p_lon double precision,p_city text default '')
returns bigint language plpgsql security definer set search_path=public,pg_temp as $$
declare rid bigint;
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 if p_need is null or p_need not in('tools','fuel','mechanical','trailer','battery','other') then raise exception 'Invalid rescue need'; end if;
 if p_lat is null or p_lat not between -90 and 90 or p_lon is null or p_lon not between -180 and 180 then raise exception 'Valid location required'; end if;
 update rescue_requests set active=false,closed_at=now() where requester_id=auth.uid() and active;
 insert into rescue_requests(requester_id,need,note,latitude,longitude,city) values(auth.uid(),p_need,left(coalesce(p_note,''),300),p_lat,p_lon,left(coalesce(p_city,''),100)) returning id into rid;return rid;
end $$;

create or replace function public.nearby_rescue_requests(p_lat double precision,p_lon double precision,p_radius_km double precision default 40)
returns table(id bigint,username text,need text,note text,latitude double precision,longitude double precision,distance_km double precision,created_at timestamptz)
language sql stable security definer set search_path=public,pg_temp as $$
 select r.id,p.username,r.need,r.note,r.latitude,r.longitude,
  6371*acos(least(1,greatest(-1,cos(radians(p_lat))*cos(radians(r.latitude))*cos(radians(r.longitude)-radians(p_lon))+sin(radians(p_lat))*sin(radians(r.latitude))))),r.created_at
 from rescue_requests r join profiles p on p.id=r.requester_id where auth.uid() is not null and r.active and r.expires_at>now() and r.requester_id<>auth.uid()
 and 6371*acos(least(1,greatest(-1,cos(radians(p_lat))*cos(radians(r.latitude))*cos(radians(r.longitude)-radians(p_lon))+sin(radians(p_lat))*sin(radians(r.latitude)))))<=least(greatest(p_radius_km,1),80)
 order by 7 limit 50;
$$;

create or replace function public.offer_rider_rescue(p_request bigint,p_message text)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if not exists(select 1 from rescue_requests where id=p_request and active and expires_at>now() and requester_id<>auth.uid()) then raise exception 'Rescue request unavailable'; end if;
 if p_message is null or char_length(trim(p_message)) not between 1 and 240 then raise exception 'Add a short offer message'; end if;
 insert into rescue_offers(request_id,helper_id,message) values(p_request,auth.uid(),trim(p_message))
 on conflict(request_id,helper_id) do update set message=excluded.message,status='offered',created_at=now();
end $$;

create or replace function public.my_rescue_center()
returns jsonb language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 return jsonb_build_object(
  'requests',coalesce((select jsonb_agg(to_jsonb(x) order by created_at desc) from(select * from rescue_requests where requester_id=auth.uid() order by created_at desc limit 10)x),'[]'::jsonb),
  'offers',coalesce((select jsonb_agg(to_jsonb(x) order by created_at) from(
   select o.request_id,o.helper_id,p.username,o.message,o.status,o.created_at from rescue_offers o join rescue_requests r on r.id=o.request_id join profiles p on p.id=o.helper_id where r.requester_id=auth.uid()
  )x),'[]'::jsonb));
end $$;

create or replace function public.update_rescue_offer(p_request bigint,p_helper uuid,p_status text)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if p_status is null or p_status not in('accepted','declined','completed') then raise exception 'Invalid rescue status'; end if;
 if not exists(select 1 from rescue_requests where id=p_request and requester_id=auth.uid()) then raise exception 'Only the requester can update helpers'; end if;
 update rescue_offers set status=p_status where request_id=p_request and helper_id=p_helper;
 if not found then raise exception 'Offer unavailable'; end if;
 if p_status='completed' then update rescue_requests set active=false,closed_at=now() where id=p_request; end if;
end $$;

create or replace function public.report_road_intel(p_type text,p_severity integer,p_note text,p_lat double precision,p_lon double precision)
returns bigint language plpgsql security definer set search_path=public,pg_temp as $$
declare rid bigint;life interval;
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 if p_type is null or p_type not in('rough','gravel','flooding','construction','dangerous_intersection','high_speed','closure','debris') then raise exception 'Invalid road report'; end if;
 if p_severity is null or p_severity not between 1 and 3 then raise exception 'Severity must be 1-3'; end if;
 if p_lat is null or p_lat not between -90 and 90 or p_lon is null or p_lon not between -180 and 180 then raise exception 'Valid location required'; end if;
 if (select count(*) from road_intel_reports where reporter_id=auth.uid() and created_at>now()-interval '1 hour')>=20 then raise exception 'Road report limit reached'; end if;
 life=case when p_type in('flooding','debris','closure') then interval '12 hours' when p_type='construction' then interval '7 days' else interval '30 days' end;
 insert into road_intel_reports(reporter_id,report_type,severity,note,latitude,longitude,expires_at) values(auth.uid(),p_type,p_severity,left(coalesce(p_note,''),240),p_lat,p_lon,now()+life) returning id into rid;return rid;
end $$;

create or replace function public.nearby_road_intel(p_lat double precision,p_lon double precision,p_radius_km double precision default 15)
returns table(id bigint,report_type text,severity smallint,note text,latitude double precision,longitude double precision,confirmations integer,distance_km double precision,created_at timestamptz)
language sql stable security definer set search_path=public,pg_temp as $$
 select r.id,r.report_type,r.severity,r.note,r.latitude,r.longitude,r.confirmations,
  6371*acos(least(1,greatest(-1,cos(radians(p_lat))*cos(radians(r.latitude))*cos(radians(r.longitude)-radians(p_lon))+sin(radians(p_lat))*sin(radians(r.latitude))))),r.created_at
 from road_intel_reports r where auth.uid() is not null and r.active and r.expires_at>now()
 and 6371*acos(least(1,greatest(-1,cos(radians(p_lat))*cos(radians(r.latitude))*cos(radians(r.longitude)-radians(p_lon))+sin(radians(p_lat))*sin(radians(r.latitude)))))<=least(greatest(p_radius_km,1),50)
 order by severity desc,8 limit 100;
$$;

create or replace function public.vote_road_intel(p_report bigint,p_accurate boolean)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 if not exists(select 1 from road_intel_reports where id=p_report and active and expires_at>now() and reporter_id<>auth.uid()) then raise exception 'Report unavailable'; end if;
 insert into road_intel_votes(report_id,rider_id,accurate) values(p_report,auth.uid(),p_accurate)
 on conflict(report_id,rider_id) do update set accurate=excluded.accurate,created_at=now();
 update road_intel_reports r set confirmations=greatest(0,1+(select count(*) filter(where accurate)-count(*) filter(where not accurate) from road_intel_votes where report_id=p_report)) where id=p_report;
 update road_intel_reports set active=false where id=p_report and confirmations<=0;
end $$;

create or replace function public.set_emergency_meet_point(p_event bigint,p_label text,p_address text,p_lat double precision default null,p_lon double precision default null)
returns bigint language plpgsql security definer set search_path=public,pg_temp as $$
declare mid bigint;
begin
 if not exists(select 1 from rider_events where id=p_event and host_id=auth.uid() and not cancelled and ends_at>now()-interval '12 hours') then raise exception 'Only the host can set the emergency meet-point'; end if;
 if p_label is null or char_length(trim(p_label)) not between 3 and 100 or p_address is null or char_length(trim(p_address)) not between 3 and 240 then raise exception 'Add a meet-point name and address'; end if;
 update emergency_meet_points set active=false where event_id=p_event and active;
 insert into emergency_meet_points(event_id,created_by,label,address,latitude,longitude) values(p_event,auth.uid(),trim(p_label),trim(p_address),p_lat,p_lon) returning id into mid;
 perform post_ride_night_chat(p_event,'EMERGENCY MEET-POINT: '||trim(p_label)||' — '||trim(p_address),true);
 return mid;
end $$;

do $$ declare f record; begin
 for f in select p.oid::regprocedure signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and p.proname in('can_access_ride_night','is_ride_night_participant','discover_ride_nights','ride_night_snapshot','post_ride_night_chat','ride_night_messages','propose_ride_route','vote_ride_route','lock_ride_route','check_in_ride_night','my_ride_pass','my_rider_reputation','request_rider_rescue','nearby_rescue_requests','offer_rider_rescue','my_rescue_center','update_rescue_offer','report_road_intel','nearby_road_intel','vote_road_intel','set_emergency_meet_point')
 loop execute format('revoke all on function %s from public,anon',f.signature);execute format('grant execute on function %s to authenticated',f.signature);end loop;
end $$;
commit;
