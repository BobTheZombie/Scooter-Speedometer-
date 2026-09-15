-- RiderLink 8.3 OAuth profile bootstrap.
-- Run once in Supabase Dashboard > SQL Editor.
-- This does not enable Google/Facebook; those providers are enabled in
-- Authentication > Sign In / Providers.

create or replace function public.riderlink_new_user_profile()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  base_name text;
  safe_name text;
begin
  base_name := coalesce(
    nullif(new.raw_user_meta_data ->> 'user_name', ''),
    nullif(new.raw_user_meta_data ->> 'preferred_username', ''),
    nullif(new.raw_user_meta_data ->> 'full_name', ''),
    nullif(split_part(coalesce(new.email, ''), '@', 1), ''),
    'Rider'
  );

  safe_name := regexp_replace(base_name, '[^A-Za-z0-9_]', '', 'g');
  if char_length(safe_name) < 3 then
    safe_name := 'Rider';
  end if;
  safe_name := left(safe_name, 22) || '_' || left(replace(new.id::text, '-', ''), 6);

  insert into public.profiles (id, username, scooter, privacy)
  values (new.id, safe_name, 'Scooter', 'invisible')
  on conflict (id) do nothing;

  return new;
end;
$$;

drop trigger if exists riderlink_create_profile_after_signup on auth.users;
create trigger riderlink_create_profile_after_signup
  after insert on auth.users
  for each row execute function public.riderlink_new_user_profile();

-- Backfill a profile for any existing Auth user that does not have one.
insert into public.profiles (id, username, scooter, privacy)
select
  u.id,
  'Rider_' || left(replace(u.id::text, '-', ''), 6),
  'Scooter',
  'invisible'
from auth.users u
left join public.profiles p on p.id = u.id
where p.id is null
on conflict (id) do nothing;

revoke all on function public.riderlink_new_user_profile() from public;
