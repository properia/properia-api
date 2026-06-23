-- Áreas de terraço/jardim mapeadas no entity Listing mas em falta na BD local.
ALTER TABLE properia.listings
  ADD COLUMN IF NOT EXISTS terrace_area_m2 numeric,
  ADD COLUMN IF NOT EXISTS garden_area_m2  numeric;
