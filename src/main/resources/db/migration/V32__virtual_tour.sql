-- ─── Migration V10: Virtual Tour (Shotstack) ──────────────────────────────────
-- Adds virtual tour generation tracking to listing_commercial.
-- virtual_tour_url already exists; we add status, render_id and timestamp.

ALTER TABLE properia.listing_commercial
  ADD COLUMN IF NOT EXISTS virtual_tour_status  TEXT         DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS virtual_tour_render_id TEXT       DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS virtual_tour_generated_at TIMESTAMPTZ DEFAULT NULL;
