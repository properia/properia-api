CREATE TABLE IF NOT EXISTS "properia"."advertiser_response_stats" (
  "advertiser_id"                   uuid PRIMARY KEY REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE,
  "chat_response_median_minutes"    integer,
  "visit_approval_median_minutes"   integer,
  "chat_sample_size"                integer NOT NULL DEFAULT 0,
  "visit_sample_size"               integer NOT NULL DEFAULT 0,
  "computed_at"                     timestamp with time zone NOT NULL DEFAULT now()
);
