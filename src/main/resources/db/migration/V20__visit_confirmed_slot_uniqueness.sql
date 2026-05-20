CREATE UNIQUE INDEX IF NOT EXISTS idx_visits_confirmed_listing_slot_unique
  ON "properia"."visits" ("listing_id", "starts_at")
  WHERE "status" = 'confirmed';
