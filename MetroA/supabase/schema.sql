-- Database condiviso delle segnalazioni di MetroA.
-- Da eseguire in Supabase: progetto → SQL Editor → incolla tutto → Run.
-- Si può rieseguire: aggiorna un database già creato senza perdere le segnalazioni.

create table if not exists public.reports (
    id         uuid primary key,                          -- generato dall'app: reinviare non crea doppioni
    seq        bigint generated always as identity unique, -- ordine di arrivo, per scaricare solo le novità
    time_ms    bigint   not null,                         -- quando è stata fatta la segnalazione (ms epoch)
    station    smallint not null check (station between 0 and 26),
    direction  text     not null check (direction in ('TO_BATTISTINI', 'TO_ANAGNINA')),
    offset_s   integer  not null check (offset_s between -900 and 900), -- + in ritardo, - in anticipo
    source     text     not null,
    device_id  uuid     not null,                         -- anonimo, solo per limitare lo spam; non leggibile
    created_at timestamptz not null default now()
);

-- Viaggi "sono sul treno": tutte le stazioni passate in un viaggio hanno lo stesso trip_id
alter table public.reports add column if not exists trip_id uuid;

-- ARRIVAL: "treno arrivato adesso"; MANUAL: scelta a mano; CONFIRM/DENY: conferma o smentita con un tocco;
-- TRIP: stazione passata durante un viaggio (solo stazione e ora; il percorso GPS, se scelto, va in trip_points)
alter table public.reports drop constraint if exists reports_source_check;
alter table public.reports add constraint reports_source_check
    check (source in ('ARRIVAL', 'MANUAL', 'CONFIRM', 'DENY', 'TRIP'));

create index if not exists reports_time_ms on public.reports (time_ms);
create index if not exists reports_device on public.reports (device_id, created_at);
create index if not exists reports_trip on public.reports (trip_id, time_ms) where trip_id is not null;

-- Controlli lato server: ora plausibile; per telefono al massimo una segnalazione ogni 30 secondi
-- sulla stessa stazione e direzione, e 60 all'ora in tutto (un viaggio da capolinea a capolinea ne fa 26).
create or replace function public.reports_check() returns trigger
language plpgsql security definer set search_path = public as $$
begin
    if abs(new.time_ms - extract(epoch from now()) * 1000) > 24 * 3600 * 1000 then
        raise exception 'time_ms fuori intervallo';
    end if;
    if exists (
        select 1 from public.reports
        where device_id = new.device_id and station = new.station and direction = new.direction
          and created_at > now() - interval '30 seconds'
    ) then
        raise exception 'segnalazione doppia';
    end if;
    if (select count(*) from public.reports
        where device_id = new.device_id and created_at > now() - interval '1 hour') >= 60 then
        raise exception 'troppe segnalazioni';
    end if;
    return new;
end $$;

drop trigger if exists reports_check on public.reports;
create trigger reports_check before insert on public.reports
    for each row execute function public.reports_check();

-- Permessi: chiunque con l'app può leggere e aggiungere; nessuno può modificare o cancellare.
alter table public.reports enable row level security;

drop policy if exists "leggere" on public.reports;
create policy "leggere" on public.reports for select to anon using (true);

drop policy if exists "aggiungere" on public.reports;
create policy "aggiungere" on public.reports for insert to anon with check (true);

revoke all on public.reports from anon;
grant insert (id, time_ms, station, direction, offset_s, source, device_id, trip_id) on public.reports to anon;
-- device_id resta escluso dalla lettura
grant select (id, seq, time_ms, station, direction, offset_s, source, created_at, trip_id) on public.reports to anon;

-- Tempo reale di ogni tratto di ogni viaggio: da una stazione registrata alla successiva dello stesso viaggio.
-- Stazioni numerate da 0 (Battistini) a 26 (Anagnina).
create or replace view public.trip_segments with (security_invoker = true) as
select trip_id, direction, from_station, to_station, seconds, time_ms
from (
    select trip_id, direction,
           lag(station) over w as from_station, station as to_station,
           (time_ms - lag(time_ms) over w) / 1000 as seconds, time_ms
    from public.reports
    where trip_id is not null
    window w as (partition by trip_id order by time_ms)
) s
where from_station is not null;

-- Media per tratta negli ultimi 60 giorni (solo tratte fra stazioni vicine, senza valori assurdi)
create or replace view public.segment_times with (security_invoker = true) as
select direction, from_station, to_station,
       round(avg(seconds))::int as avg_seconds, count(*) as trips
from public.trip_segments
where abs(to_station - from_station) = 1
  and seconds between 20 and 600
  and time_ms > (extract(epoch from now()) * 1000)::bigint - 60::bigint * 24 * 3600 * 1000
group by direction, from_station, to_station;

grant select on public.trip_segments, public.segment_times to anon;

-- Percorso GPS dei viaggi, solo per chi sceglie di condividerlo (spento di base nell'app).
-- Privato: l'app può aggiungere punti ma nessuno può leggerli, tranne il gestore dalla dashboard.
create table if not exists public.trip_points (
    id         uuid primary key,
    trip_id    uuid             not null,
    time_ms    bigint           not null,
    lat        double precision not null check (lat between 41.6 and 42.2),   -- area di Roma
    lon        double precision not null check (lon between 12.2 and 12.8),
    accuracy_m real             not null check (accuracy_m between 0 and 2000),
    device_id  uuid             not null,
    created_at timestamptz      not null default now()
);

create index if not exists trip_points_trip on public.trip_points (trip_id, time_ms);
create index if not exists trip_points_device on public.trip_points (device_id, created_at);

-- Ora plausibile e al massimo 500 punti all'ora per telefono (uno ogni 10 s sono 360)
create or replace function public.trip_points_check() returns trigger
language plpgsql security definer set search_path = public as $$
begin
    if abs(new.time_ms - extract(epoch from now()) * 1000) > 24 * 3600 * 1000 then
        raise exception 'time_ms fuori intervallo';
    end if;
    if (select count(*) from public.trip_points
        where device_id = new.device_id and created_at > now() - interval '1 hour') >= 500 then
        raise exception 'troppi punti';
    end if;
    return new;
end $$;

drop trigger if exists trip_points_check on public.trip_points;
create trigger trip_points_check before insert on public.trip_points
    for each row execute function public.trip_points_check();

alter table public.trip_points enable row level security;
drop policy if exists "aggiungere" on public.trip_points;
create policy "aggiungere" on public.trip_points for insert to anon with check (true);
revoke all on public.trip_points from anon;
grant insert (id, trip_id, time_ms, lat, lon, accuracy_m, device_id) on public.trip_points to anon;

-- =====================================================================================
-- App sviluppatore (MetroA Dev): accesso con account Supabase (email e password).
-- Solo chi è nella tabella developers può leggere tutto, cancellare e scrivere i numeri dei treni.
-- Per aggiungere uno sviluppatore: Authentication → Users → Add user, poi
--   insert into public.developers (user_id) select id from auth.users where email = 'nome@esempio.it';
-- =====================================================================================

create table if not exists public.developers (
    user_id  uuid primary key references auth.users (id) on delete cascade,
    added_at timestamptz not null default now()
);
alter table public.developers enable row level security;
revoke all on public.developers from anon, authenticated;
grant select on public.developers to authenticated;
drop policy if exists "se stesso" on public.developers;
create policy "se stesso" on public.developers for select to authenticated using (user_id = auth.uid());

-- Vero se chi fa la richiesta è uno sviluppatore
create or replace function public.is_developer() returns boolean
language sql stable security definer set search_path = public as $$
    select exists (select 1 from public.developers where user_id = auth.uid())
$$;
revoke all on function public.is_developer() from public;
grant execute on function public.is_developer() to authenticated;

-- Segnalazioni: gli sviluppatori leggono tutto (anche device_id, per lo spam) e possono cancellare
grant select, delete on public.reports to authenticated;
drop policy if exists "sviluppatori leggono" on public.reports;
create policy "sviluppatori leggono" on public.reports for select to authenticated using (public.is_developer());
drop policy if exists "sviluppatori cancellano" on public.reports;
create policy "sviluppatori cancellano" on public.reports for delete to authenticated using (public.is_developer());
grant select on public.trip_segments, public.segment_times to authenticated;

-- Percorsi GPS: leggibili e cancellabili solo dagli sviluppatori
grant select, delete on public.trip_points to authenticated;
drop policy if exists "sviluppatori leggono" on public.trip_points;
create policy "sviluppatori leggono" on public.trip_points for select to authenticated using (public.is_developer());
drop policy if exists "sviluppatori cancellano" on public.trip_points;
create policy "sviluppatori cancellano" on public.trip_points for delete to authenticated using (public.is_developer());

-- Numeri dei convogli (matricola scritta sul treno) associati alla corsa programmata. Solo sviluppatori.
create table if not exists public.train_numbers (
    id              uuid primary key default gen_random_uuid(),
    time_ms         bigint   not null,                         -- quando è stato visto
    station         smallint not null check (station between 0 and 26),
    direction       text     not null check (direction in ('TO_BATTISTINI', 'TO_ANAGNINA')),
    train_number    text     not null check (train_number ~ '^[A-Za-z0-9-]{1,12}$'),
    scheduled_trip  text,                                      -- id della corsa nel GTFS, se riconosciuta
    scheduled_label text check (char_length(scheduled_label) <= 80), -- es. "treno delle 10:55 da Battistini"
    note            text check (char_length(note) <= 200),
    created_by      uuid     not null default auth.uid(),
    created_at      timestamptz not null default now()
);
create index if not exists train_numbers_time on public.train_numbers (time_ms);
create index if not exists train_numbers_number on public.train_numbers (train_number);

alter table public.train_numbers enable row level security;
revoke all on public.train_numbers from anon, authenticated;
grant select, insert, update, delete on public.train_numbers to authenticated;
drop policy if exists "sviluppatori" on public.train_numbers;
create policy "sviluppatori" on public.train_numbers for all to authenticated
    using (public.is_developer()) with check (public.is_developer());
