-- RiderLink+ v9.3 platform migration. Run after ride_nights_v90 and play_billing_v92.
-- Idempotent: safe to run again.
create table if not exists public.rescue_helper_profiles(
 user_id uuid primary key references auth.users(id) on delete cascade,
 tools boolean not null default false,fuel boolean not null default false,
 battery boolean not null default false,mechanical boolean not null default false,
 trailer boolean not null default false,max_distance_km integer not null default 25 check(max_distance_km between 2 and 80),
 available boolean not null default false,updated_at timestamptz not null default now()
);
alter table public.rescue_helper_profiles enable row level security;
revoke all on public.rescue_helper_profiles from public,anon,authenticated;

alter table public.profiles add column if not exists engine_class text not null default '50cc'
 check(engine_class in('50cc','125cc','150cc','250cc'));
alter table public.rider_events add column if not exists minimum_engine_class text not null default '50cc'
 check(minimum_engine_class in('50cc','125cc','150cc','250cc'));

create or replace function public.set_rescue_helper_profile(p_tools boolean,p_fuel boolean,p_battery boolean,p_mechanical boolean,p_trailer boolean,p_distance integer,p_available boolean)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if auth.uid() is null then raise exception 'Sign in first';end if;
 insert into rescue_helper_profiles(user_id,tools,fuel,battery,mechanical,trailer,max_distance_km,available,updated_at)
 values(auth.uid(),coalesce(p_tools,false),coalesce(p_fuel,false),coalesce(p_battery,false),coalesce(p_mechanical,false),coalesce(p_trailer,false),least(80,greatest(2,coalesce(p_distance,25))),coalesce(p_available,false),now())
 on conflict(user_id) do update set tools=excluded.tools,fuel=excluded.fuel,battery=excluded.battery,mechanical=excluded.mechanical,trailer=excluded.trailer,max_distance_km=excluded.max_distance_km,available=excluded.available,updated_at=now();
end $$;

create or replace function public.my_rescue_helper_profile()
returns table(tools boolean,fuel boolean,battery boolean,mechanical boolean,trailer boolean,max_distance_km integer,available boolean)
language sql stable security definer set search_path=public,pg_temp as $$
 select coalesce(h.tools,false),coalesce(h.fuel,false),coalesce(h.battery,false),coalesce(h.mechanical,false),coalesce(h.trailer,false),coalesce(h.max_distance_km,25),coalesce(h.available,false)
 from(select auth.uid() id)x left join rescue_helper_profiles h on h.user_id=x.id;
$$;

create or replace function public.my_rider_reputation(p_user uuid default null)
returns jsonb language plpgsql stable security definer set search_path=public,pg_temp as $$
declare target uuid=coalesce(p_user,auth.uid());attended integer;hosted integer;helps integer;reports integer;score integer;badges jsonb='[]'::jsonb;
begin
 if auth.uid() is null then raise exception 'Sign in first';end if;
 select count(*) into attended from ride_night_checkins where rider_id=target;
 select count(*) into hosted from rider_events where host_id=target and ends_at<now() and not cancelled;
 select count(*) into helps from rescue_offers where helper_id=target and status='completed';
 select count(*) into reports from road_intel_reports where reporter_id=target and confirmations>=2;
 score=least(999,attended*5+hosted*8+helps*12+reports*3);
 if attended>=5 then badges=badges||'"Ride Regular"'::jsonb;end if;
 if hosted>=3 then badges=badges||'"Reliable Host"'::jsonb;end if;
 if helps>=1 then badges=badges||'"Mechanical Helper"'::jsonb;end if;
 if reports>=5 then badges=badges||'"Road Scout"'::jsonb;end if;
 return(select jsonb_build_object('user_id',p.id,'username',p.username,'engine_class',p.engine_class,'rides_attended',attended,'rides_hosted',hosted,'rescue_helps',helps,'road_reports',reports,'reputation_score',score,'badges',badges) from profiles p where p.id=target);
end $$;

revoke all on function public.set_rescue_helper_profile(boolean,boolean,boolean,boolean,boolean,integer,boolean) from public,anon,authenticated;
revoke all on function public.my_rescue_helper_profile() from public,anon,authenticated;
grant execute on function public.set_rescue_helper_profile(boolean,boolean,boolean,boolean,boolean,integer,boolean) to authenticated;
grant execute on function public.my_rescue_helper_profile() to authenticated;
