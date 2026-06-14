-- Add the 2 demo images to ALL listings
-- Image 1: Modern apartment interior
-- Image 2: Luxury penthouse with pool

-- Delete existing images from listing_images table (if exists)
DELETE FROM properia.listing_images WHERE listing_id IN (SELECT id FROM properia.listings);

-- Insert the 2 demo images for each listing
-- These images come from the 2 showcase properties
INSERT INTO properia.listing_images (id, listing_id, url, position, created_at)
SELECT
  gen_random_uuid(),
  l.id,
  'https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=1200&h=800&fit=crop',
  1,
  NOW()
FROM properia.listings l;

INSERT INTO properia.listing_images (id, listing_id, url, position, created_at)
SELECT
  gen_random_uuid(),
  l.id,
  'https://images.unsplash.com/photo-1600585154340-be6161a56a0c?w=800&h=600&fit=crop',
  2,
  NOW()
FROM properia.listings l;
