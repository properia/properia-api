ALTER TABLE "properia"."listings" ADD COLUMN IF NOT EXISTS "pool_type" text;;
ALTER TABLE "properia"."listings" ADD COLUMN IF NOT EXISTS "has_barbecue" boolean DEFAULT false NOT NULL;;
ALTER TABLE "properia"."listings" ADD COLUMN IF NOT EXISTS "has_laundry_area" boolean DEFAULT false NOT NULL;;
ALTER TABLE IF EXISTS "properia"."listing_commercial_details" ADD COLUMN IF NOT EXISTS "has_wc" boolean DEFAULT false NOT NULL;;
ALTER TABLE IF EXISTS "properia"."listing_commercial_details" ADD COLUMN IF NOT EXISTS "has_kitchenette" boolean DEFAULT false NOT NULL;;
ALTER TABLE IF EXISTS "properia"."listing_commercial_details" ADD COLUMN IF NOT EXISTS "has_outdoor_seating_potential" boolean DEFAULT false NOT NULL;
