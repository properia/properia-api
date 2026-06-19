ALTER TABLE properia.listings
  ADD COLUMN IF NOT EXISTS fibra_otica       boolean,
  ADD COLUMN IF NOT EXISTS gas_canalizado    boolean,
  ADD COLUMN IF NOT EXISTS tv_cabo           boolean,
  ADD COLUMN IF NOT EXISTS fossa_septica     boolean;
