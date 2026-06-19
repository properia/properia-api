ALTER TABLE properia.listings
  ADD COLUMN IF NOT EXISTS wc_servico          int,
  ADD COLUMN IF NOT EXISTS tipo_caixilharia    text,
  ADD COLUMN IF NOT EXISTS localizacao_edificio text,
  ADD COLUMN IF NOT EXISTS seguro_condominio_incluido boolean;
