DO $$
BEGIN
  CREATE TYPE "properia"."street_visibility" AS ENUM (
    'main_street',
    'secondary_street',
    'corner',
    'low_visibility'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS "properia"."listing_commercial_details" (
  "listing_id" uuid PRIMARY KEY REFERENCES "properia"."listings"("id") ON DELETE CASCADE,
  "has_shopfront" boolean NOT NULL DEFAULT false,
  "street_visibility" "properia"."street_visibility",
  "internal_floors" integer,
  "has_vehicle_access" boolean NOT NULL DEFAULT false,
  "permitted_use" text,
  "has_flue_pipe" boolean NOT NULL DEFAULT false,
  "has_extraction_system" boolean NOT NULL DEFAULT false,
  "has_wc" boolean NOT NULL DEFAULT false,
  "has_kitchenette" boolean NOT NULL DEFAULT false,
  "has_outdoor_seating_potential" boolean NOT NULL DEFAULT false,
  "created_at" timestamptz NOT NULL DEFAULT now(),
  "updated_at" timestamptz NOT NULL DEFAULT now()
);
