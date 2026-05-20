create table if not exists properia.area_poi_snapshots (
  id uuid primary key default gen_random_uuid(),
  area_key text not null unique,
  geohash text,
  center_latitude double precision not null,
  center_longitude double precision not null,
  radius_m integer not null default 300,
  source text not null default 'overpass',
  snapshot_version integer not null default 1,
  categories_hash text not null,
  status text not null default 'pending',
  attempts integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  refresh_started_at timestamptz,
  processed_at timestamptz,
  expires_at timestamptz,
  error_message text,
  schools_count integer not null default 0,
  health_count integer not null default 0,
  transport_count integer not null default 0,
  supermarket_count integer not null default 0,
  restaurant_count integer not null default 0,
  cafe_count integer not null default 0,
  park_count integer not null default 0,
  pharmacy_count integer not null default 0,
  bank_count integer not null default 0,
  gym_count integer not null default 0,
  beach_count integer not null default 0,
  culture_count integer not null default 0,
  safety_count integer not null default 0,
  nearest_school_m integer,
  nearest_health_m integer,
  nearest_transport_m integer,
  nearest_supermarket_m integer,
  nearest_restaurant_m integer,
  nearest_cafe_m integer,
  nearest_park_m integer,
  nearest_pharmacy_m integer,
  nearest_bank_m integer,
  nearest_gym_m integer,
  categories_summary jsonb not null default '[]'::jsonb,
  nearest_pois jsonb not null default '{}'::jsonb,
  poi_density jsonb not null default '{}'::jsonb,
  raw_overpass jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_area_poi_snapshots_status_next_attempt
  on properia.area_poi_snapshots(status, next_attempt_at);

create index if not exists idx_area_poi_snapshots_geohash
  on properia.area_poi_snapshots(geohash);

alter table properia.job_executions
  add column if not exists next_attempt_at timestamptz not null default now();

create index if not exists idx_job_executions_status_next_attempt
  on properia.job_executions(status, next_attempt_at, created_at);
