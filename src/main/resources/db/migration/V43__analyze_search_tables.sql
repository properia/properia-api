-- Actualizar estatísticas das tabelas usadas na query de pesquisa.
-- Sem ANALYZE, o planner PostgreSQL estimava 587 biliões de rows nos JOINs,
-- activando JIT com 75 funções LLVM — o que causava 12s de latência no Render free tier.
ANALYZE properia.listings;
ANALYZE properia.listing_pricing;
ANALYZE properia.listing_features;
ANALYZE properia.listing_commercial;
ANALYZE properia.listing_location;
ANALYZE properia.listing_zone_scores;
ANALYZE properia.listing_media;
ANALYZE properia.listing_detail_views;
ANALYZE properia.listing_price_history;
