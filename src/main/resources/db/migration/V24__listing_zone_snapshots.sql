do $$
begin
  create type properia.zone_snapshot_provider as enum ('gemini');
exception
  when duplicate_object then null;
end $$;

do $$
begin
  create type properia.zone_processing_status as enum (
    'not_processed',
    'processing',
    'processed',
    'error',
    'stale'
  );
exception
  when duplicate_object then null;
end $$;

create table if not exists properia.listing_zone_snapshots (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references properia.listings(id) on delete cascade,
  provider properia.zone_snapshot_provider not null default 'gemini',
  provider_version integer not null default 1,
  radius_m integer not null default 500,
  taxonomy_version integer not null default 1,
  contract_version integer not null default 1,
  location_fingerprint text not null,
  location_snapshot jsonb not null default '{}'::jsonb,
  status properia.zone_processing_status not null default 'not_processed',
  payload jsonb not null default '{}'::jsonb,
  summary_short text,
  summary_long text,
  error_code text,
  error_message text,
  retry_count integer not null default 0,
  last_attempt_at timestamptz,
  processed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists listing_zone_snapshots_listing_fingerprint_contract_unique
  on properia.listing_zone_snapshots(listing_id, location_fingerprint, contract_version);

create index if not exists idx_listing_zone_snapshots_listing_updated
  on properia.listing_zone_snapshots(listing_id, updated_at);

create index if not exists idx_listing_zone_snapshots_status_attempt
  on properia.listing_zone_snapshots(status, last_attempt_at);
