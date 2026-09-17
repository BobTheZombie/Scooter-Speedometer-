-- RiderLink+ Events. Run after riderlink.sql and riderlink_membership_v86.sql.
-- Public means visible to signed-in RiderLink riders. Invitations never require Plus.
begin;
create table if not exists public.rider_events (
  id bigint generated always as identity primary key,
  host_id uuid not null references public.profiles(id) on delete cascade,
  title text not null check (char_length(trim(title)) between 3 and 80),
  description text not null default '' check (char_length(description) <= 3000),
  city text not null check (char_length(trim(city)) between 2 and 100),
  meeting_place text not null check (char_length(trim(meeting_place)) between 3 and 240),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  visibility text not null check (visibility in ('public','invite_only')),
  cancelled boolean not null default false,
  revision integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (ends_at > starts_at and ends_at <= starts_at + interval '7 days')
);
create table if not exists public.rider_event_riders (
  event_id bigint not null references public.rider_events(id) on delete cascade,
  rider_id uuid not null references public.profiles(id) on delete cascade,
  invited boolean not null default false,
  response text not null default 'pending' check (response in ('pending','going','interested','declined')),
  updated_at timestamptz not null default now(),
  primary key (event_id,rider_id)
);
create index if not exists rider_events_board on public.rider_events(starts_at,id) where visibility='public' and not cancelled;
create index if not exists rider_events_host on public.rider_events(host_id,starts_at);
create index if not exists rider_events_inbox on public.rider_event_riders(rider_id,event_id);
alter table public.rider_events enable row level security;
alter table public.rider_event_riders enable row level security;
-- Access only through the checked RPCs; no direct table or sequence writes.
revoke all on public.rider_events,public.rider_event_riders from public,anon,authenticated;
revoke all on sequence public.rider_events_id_seq from public,anon,authenticated;

create or replace function public.save_rider_event(
  p_id bigint,p_revision integer,p_title text,p_description text,p_city text,
  p_meeting_place text,p_starts_at timestamptz,p_ends_at timestamptz,p_visibility text
) returns bigint language plpgsql security definer set search_path=public,pg_temp as $$
declare result_id bigint; existing public.rider_events%rowtype;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  if p_id is null then
    if not exists(select 1 from riderlink_entitlements where user_id=auth.uid() and plus_access and (expires_at is null or expires_at>now()))
       and not exists(select 1 from riderlink_admins where user_id=auth.uid()) then
      raise exception 'Hosting events requires RiderLink+';
    end if;
    -- Serialize creates per host so concurrent requests cannot bypass the limit.
    perform 1 from profiles where id=auth.uid() for update;
    if (select count(*) from rider_events where host_id=auth.uid() and ends_at>now() and not cancelled)>=20 then
      raise exception 'You can host up to 20 upcoming events';
    end if;
  else
    select * into existing from rider_events where id=p_id and host_id=auth.uid() for update;
    if not found then raise exception 'Event unavailable or you are not the host'; end if;
    if existing.cancelled or existing.ends_at<=now() then raise exception 'This event is closed'; end if;
    if p_revision is distinct from existing.revision then raise exception 'Event changed. Refresh before editing'; end if;
    if p_visibility is distinct from existing.visibility then raise exception 'Visibility cannot be changed after posting'; end if;
  end if;
  if p_title is null or char_length(trim(p_title)) not between 3 and 80
    or p_description is null or char_length(p_description)>3000
    or p_city is null or char_length(trim(p_city)) not between 2 and 100
    or p_meeting_place is null or char_length(trim(p_meeting_place)) not between 3 and 240 then
    raise exception 'Check the event title, description, city and meeting place';
  end if;
  if p_visibility is null or p_visibility not in ('public','invite_only') then raise exception 'Invalid visibility'; end if;
  if p_starts_at is null or p_ends_at is null or p_starts_at<=now() or p_starts_at>now()+interval '1 year'
    or p_ends_at<=p_starts_at or p_ends_at>p_starts_at+interval '7 days' then
    raise exception 'Choose a future start within one year and an end within seven days of the start';
  end if;
  if p_id is null then
    insert into rider_events(host_id,title,description,city,meeting_place,starts_at,ends_at,visibility)
    values(auth.uid(),trim(p_title),trim(p_description),trim(p_city),trim(p_meeting_place),p_starts_at,p_ends_at,p_visibility)
    returning id into result_id;
  else
    update rider_events set title=trim(p_title),description=trim(p_description),city=trim(p_city),meeting_place=trim(p_meeting_place),
      starts_at=p_starts_at,ends_at=p_ends_at,revision=revision+1,updated_at=now() where id=p_id returning id into result_id;
  end if;
  return result_id;
end $$;

create or replace function public.list_rider_events(p_mode text default 'public',p_city text default '',p_offset integer default 0)
returns jsonb language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  if p_mode is null or p_mode not in ('public','invited','mine') or p_offset is null or p_offset<0 then raise exception 'Invalid board request'; end if;
  return coalesce((select jsonb_agg(to_jsonb(board) order by starts_at,id) from (
    select e.*,p.username as host_name,coalesce(m.response,'none') as my_response,coalesce(m.invited,false) as invited,
      (select count(*) from rider_event_riders r where r.event_id=e.id and r.response='going') as going_count,
      (select count(*) from rider_event_riders r where r.event_id=e.id and r.response='interested') as interested_count
    from rider_events e join profiles p on p.id=e.host_id
    left join rider_event_riders m on m.event_id=e.id and m.rider_id=auth.uid()
    where (e.visibility='public' or e.host_id=auth.uid() or m.invited)
      and case p_mode
        when 'public' then e.visibility='public' and not e.cancelled and e.ends_at>now()
          and strpos(lower(e.city),lower(coalesce(trim(p_city),'')))>0
        when 'invited' then m.invited and e.ends_at>now()-interval '7 days'
        when 'mine' then (e.host_id=auth.uid() or m.response in ('going','interested')) and e.ends_at>now()-interval '7 days'
      end
    order by e.starts_at,e.id limit 30 offset p_offset
  ) board),'[]'::jsonb);
end $$;

create or replace function public.invite_rider_to_event(p_event bigint,p_username text)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
declare target uuid; matches integer;
begin
  perform 1 from rider_events where id=p_event and host_id=auth.uid() and not cancelled and ends_at>now() for update;
  if not found then raise exception 'Only the host can invite riders to an open event'; end if;
  select count(*) into matches from profiles where lower(username)=lower(trim(p_username));
  if matches<>1 then raise exception 'Enter a unique, exact RiderLink username'; end if;
  select id into target from profiles where lower(username)=lower(trim(p_username));
  if target=auth.uid() then raise exception 'You are already the host'; end if;
  if exists(select 1 from rider_event_riders where event_id=p_event and rider_id=target and invited) then return; end if;
  if (select count(*) from rider_event_riders where event_id=p_event and invited)>=200 then raise exception 'Invitation limit reached'; end if;
  insert into rider_event_riders(event_id,rider_id,invited) values(p_event,target,true)
    on conflict(event_id,rider_id) do update set invited=true,updated_at=now();
end $$;

create or replace function public.respond_rider_event(p_event bigint,p_response text)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
declare e public.rider_events%rowtype;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  if p_response is null or p_response not in ('going','interested','declined') then raise exception 'Invalid RSVP'; end if;
  select * into e from rider_events where id=p_event for update;
  if not found then raise exception 'Event unavailable'; end if;
  if e.visibility<>'public' and e.host_id<>auth.uid() and not exists(
    select 1 from rider_event_riders where event_id=p_event and rider_id=auth.uid() and invited
  ) then raise exception 'Event unavailable'; end if;
  if e.cancelled or e.ends_at<=now() then raise exception 'This event is closed'; end if;
  if e.host_id=auth.uid() then raise exception 'You are already the host'; end if;
  insert into rider_event_riders(event_id,rider_id,response) values(p_event,auth.uid(),p_response)
    on conflict(event_id,rider_id) do update set response=excluded.response,updated_at=now();
end $$;

create or replace function public.cancel_rider_event(p_event bigint)
returns void language plpgsql security definer set search_path=public,pg_temp as $$
begin
  update rider_events set cancelled=true,revision=revision+1,updated_at=now() where id=p_event and host_id=auth.uid();
  if not found then raise exception 'Only the host can cancel this event'; end if;
end $$;

create or replace function public.rider_event_guest_list(p_event bigint)
returns table(username text,response text,invited boolean)
language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
  if not exists(select 1 from rider_events where id=p_event and host_id=auth.uid()) then raise exception 'Only the host can view the guest list'; end if;
  return query select p.username,r.response,r.invited from rider_event_riders r join profiles p on p.id=r.rider_id
    where r.event_id=p_event order by r.response,p.username;
end $$;

revoke all on function public.save_rider_event(bigint,integer,text,text,text,text,timestamptz,timestamptz,text) from public,anon;
revoke all on function public.list_rider_events(text,text,integer) from public,anon;
revoke all on function public.invite_rider_to_event(bigint,text) from public,anon;
revoke all on function public.respond_rider_event(bigint,text) from public,anon;
revoke all on function public.cancel_rider_event(bigint) from public,anon;
revoke all on function public.rider_event_guest_list(bigint) from public,anon;
grant execute on function public.save_rider_event(bigint,integer,text,text,text,text,timestamptz,timestamptz,text) to authenticated;
grant execute on function public.list_rider_events(text,text,integer) to authenticated;
grant execute on function public.invite_rider_to_event(bigint,text) to authenticated;
grant execute on function public.respond_rider_event(bigint,text) to authenticated;
grant execute on function public.cancel_rider_event(bigint) to authenticated;
grant execute on function public.rider_event_guest_list(bigint) to authenticated;
commit;
