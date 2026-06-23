-- Colunas mapeadas no entity Listing mas em falta na BD: aquecimento/arrefecimento/AQS + exclusividade.
ALTER TABLE properia.listings
  ADD COLUMN IF NOT EXISTS heating_type        text,
  ADD COLUMN IF NOT EXISTS cooling_type        text,
  ADD COLUMN IF NOT EXISTS water_heating_type  text,
  ADD COLUMN IF NOT EXISTS exclusive_listing   boolean NOT NULL DEFAULT false;
