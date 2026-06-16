-- Composite index for the most common search pattern:
-- WHERE status = 'published' ORDER BY published_at DESC
-- Covers the default listing page load and eliminates a filesort.
CREATE INDEX IF NOT EXISTS "idx_listings_status_published_at"
    ON properia.listings (status, published_at DESC NULLS LAST);

-- Covering index for COUNT(*) of detail views per listing (search lateral join).
-- The existing unique index on (listing_id, session_key) works but is wider than needed.
CREATE INDEX IF NOT EXISTS "idx_listing_detail_views_listing_id"
    ON properia.listing_detail_views (listing_id);
