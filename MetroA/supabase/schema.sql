-- Database condiviso delle segnalazioni di MetroA.
-- Da eseguire una volta in Supabase: progetto → SQL Editor → incolla tutto → Run.

create table if not exists public.reports (
    id         uuid primary key,                          -- generato dall'app: reinviare non crea doppioni
    seq        bigint generated always as identity unique, -- ordine di arrivo, per scaricare solo le novità
    time_ms    bigint   not null,                         -- quando è stata fatta la segnalazione (ms epoch)
    station    smallint not null check (station between 0 and 26),
    direction  text     not null check (direction in ('TO_BATTISTINI', 'TO_ANAGNINA')),
    offset_s   integer  not null check (offset_s between -900 and 900), -- + in ritardo, - in anticipo
    source     text     not null check (source in ('ARRIVAL', 'MANUAL')),
    device_id  uuid     not null,                         -- anonimo, solo per limitare lo spam; non leggibile
    created_at timestamptz not null default now()
);

create index if not exists reports_time_ms on public.reports (time_ms);
create index if not exists reports_device on public.reports (device_id, created_at);

-- Controlli lato server: ora plausibile; per telefono al massimo una segnalazione ogni 30 secondi
-- sulla stessa stazione e direzione, e 30 all'ora in tutto.
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
        where device_id = new.device_id and created_at > now() - interval '1 hour') >= 30 then
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
grant insert (id, time_ms, station, direction, offset_s, source, device_id) on public.reports to anon;
-- device_id resta escluso dalla lettura
grant select (id, seq, time_ms, station, direction, offset_s, source, created_at) on public.reports to anon;
