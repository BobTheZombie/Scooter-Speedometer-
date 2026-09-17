-- RiderLink+ v8.8 Safety Circle
-- Run after riderlink.sql and riderlink_membership_v86.sql.
begin;

create table if not exists public.safety_contacts (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  contact_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted')),
  created_at timestamptz not null default now(), accepted_at timestamptz,
  unique(owner_id,contact_id), check(owner_id<>contact_id)
);
create table if not exists public.safety_trips (
  id bigint generated always as identity primary key,
  rider_id uuid not null references auth.users(id) on delete cascade,
  active boolean not null default true,
  checkin_minutes int not null default 30 check(checkin_minutes between 5 and 180),
  checkin_due_at timestamptz not null,
  latitude double precision, longitude double precision, accuracy real, speed_mps real,
  last_location_at timestamptz, started_at timestamptz not null default now(), ended_at timestamptz
);
create unique index if not exists safety_one_live_trip on public.safety_trips(rider_id) where active;
create table if not exists public.safety_events (
  id bigint generated always as identity primary key,
  rider_id uuid not null references auth.users(id) on delete cascade,
  trip_id bigint references public.safety_trips(id) on delete set null,
  event_type text not null check(event_type in ('manual_sos','possible_crash','overdue','battery_low')),
  note text not null default '', latitude double precision, longitude double precision,
  active boolean not null default true, created_at timestamptz not null default now(), resolved_at timestamptz
);

alter table public.safety_contacts enable row level security;
alter table public.safety_trips enable row level security;
alter table public.safety_events enable row level security;
drop policy if exists safety_contacts_participants on public.safety_contacts;
create policy safety_contacts_participants on public.safety_contacts for select to authenticated using(auth.uid() in (owner_id,contact_id));
drop policy if exists safety_trips_owner on public.safety_trips;
create policy safety_trips_owner on public.safety_trips for all to authenticated using(rider_id=auth.uid()) with check(rider_id=auth.uid());
drop policy if exists safety_trips_contacts on public.safety_trips;
create policy safety_trips_contacts on public.safety_trips for select to authenticated using(exists(select 1 from public.safety_contacts c where c.status='accepted' and ((c.owner_id=rider_id and c.contact_id=auth.uid()) or (c.contact_id=rider_id and c.owner_id=auth.uid()))));
drop policy if exists safety_events_participants on public.safety_events;
create policy safety_events_participants on public.safety_events for select to authenticated using(rider_id=auth.uid() or exists(select 1 from public.safety_contacts c where c.status='accepted' and ((c.owner_id=rider_id and c.contact_id=auth.uid()) or (c.contact_id=rider_id and c.owner_id=auth.uid()))));

create or replace function public.add_safety_contact(p_username text) returns bigint language plpgsql security definer set search_path=public as $$
declare target uuid; rid bigint;
begin
  select id into target from profiles where lower(username)=lower(trim(p_username)) limit 1;
  if target is null then raise exception 'RiderLink username not found'; end if;
  if target=auth.uid() then raise exception 'Choose another rider'; end if;
  insert into safety_contacts(owner_id,contact_id) values(auth.uid(),target)
  on conflict(owner_id,contact_id) do update set status='pending',accepted_at=null returning id into rid;
  return rid;
end $$;
create or replace function public.accept_safety_contact(p_relation bigint) returns void language plpgsql security definer set search_path=public as $$
begin update safety_contacts set status='accepted',accepted_at=now() where id=p_relation and contact_id=auth.uid() and status='pending';if not found then raise exception 'Safety Circle invitation not found';end if;end $$;
create or replace function public.remove_safety_contact(p_contact uuid) returns void language sql security definer set search_path=public as $$ delete from safety_contacts where auth.uid() in(owner_id,contact_id) and p_contact in(owner_id,contact_id); $$;

drop function if exists public.my_safety_circle();
create function public.my_safety_circle() returns table(relation_id bigint,contact_id uuid,username text,scooter text,status text,direction text) language sql security definer set search_path=public as $$
  select c.id,case when c.owner_id=auth.uid() then c.contact_id else c.owner_id end,p.username,p.scooter,c.status,case when c.owner_id=auth.uid() then 'outgoing' else 'incoming' end
  from safety_contacts c join profiles p on p.id=case when c.owner_id=auth.uid() then c.contact_id else c.owner_id end
  where auth.uid() in(c.owner_id,c.contact_id) order by c.status,c.created_at desc;
$$;
create or replace function public.start_safety_trip(p_checkin_minutes int default 30) returns bigint language plpgsql security definer set search_path=public as $$
declare tid bigint;begin update safety_trips set active=false,ended_at=now() where rider_id=auth.uid() and active;insert into safety_trips(rider_id,checkin_minutes,checkin_due_at) values(auth.uid(),greatest(5,least(180,p_checkin_minutes)),now()+make_interval(mins=>greatest(5,least(180,p_checkin_minutes)))) returning id into tid;return tid;end $$;
create or replace function public.update_safety_trip(p_lat double precision,p_lon double precision,p_accuracy real default 0,p_speed_mps real default 0) returns void language plpgsql security definer set search_path=public as $$
begin if p_lat not between -90 and 90 or p_lon not between -180 and 180 then raise exception 'Invalid location';end if;update safety_trips set latitude=p_lat,longitude=p_lon,accuracy=p_accuracy,speed_mps=p_speed_mps,last_location_at=now() where rider_id=auth.uid() and active;end $$;
create or replace function public.check_in_safety_trip() returns timestamptz language plpgsql security definer set search_path=public as $$
declare due timestamptz;begin update safety_trips set checkin_due_at=now()+make_interval(mins=>checkin_minutes) where rider_id=auth.uid() and active returning checkin_due_at into due;if due is null then raise exception 'No active safety trip';end if;update safety_events set active=false,resolved_at=now() where rider_id=auth.uid() and active and event_type='overdue';return due;end $$;
create or replace function public.end_safety_trip() returns void language plpgsql security definer set search_path=public as $$ begin update safety_trips set active=false,ended_at=now() where rider_id=auth.uid() and active;update safety_events set active=false,resolved_at=now() where rider_id=auth.uid() and active;end $$;
drop function if exists public.my_safety_status();
create function public.my_safety_status() returns table(trip_id bigint,active boolean,checkin_due_at timestamptz,latitude double precision,longitude double precision,last_location_at timestamptz) language sql security definer set search_path=public as $$ select id,active,checkin_due_at,latitude,longitude,last_location_at from safety_trips where rider_id=auth.uid() and active order by started_at desc limit 1; $$;
create or replace function public.trigger_safety_event(p_type text,p_note text default '',p_lat double precision default null,p_lon double precision default null) returns bigint language plpgsql security definer set search_path=public as $$
declare eid bigint;tid bigint;begin if p_type not in('manual_sos','possible_crash','overdue','battery_low') then raise exception 'Invalid safety event';end if;select id into tid from safety_trips where rider_id=auth.uid() and active order by started_at desc limit 1;if p_type='overdue' then select id into eid from safety_events where rider_id=auth.uid() and trip_id is not distinct from tid and event_type='overdue' and active order by created_at desc limit 1;if eid is not null then return eid;end if;end if;insert into safety_events(rider_id,trip_id,event_type,note,latitude,longitude) values(auth.uid(),tid,p_type,left(coalesce(p_note,''),500),p_lat,p_lon) returning id into eid;return eid;end $$;

grant execute on function public.add_safety_contact(text) to authenticated;
grant execute on function public.accept_safety_contact(bigint) to authenticated;
grant execute on function public.remove_safety_contact(uuid) to authenticated;
grant execute on function public.my_safety_circle() to authenticated;
grant execute on function public.start_safety_trip(int) to authenticated;
grant execute on function public.update_safety_trip(double precision,double precision,real,real) to authenticated;
grant execute on function public.check_in_safety_trip() to authenticated;
grant execute on function public.end_safety_trip() to authenticated;
grant execute on function public.my_safety_status() to authenticated;
grant execute on function public.trigger_safety_event(text,text,double precision,double precision) to authenticated;
commit;
