-- ============================================================
-- PROPERIA — V77: Seed de demonstração — Condições Especiais
--
-- 5 imóveis com preços deliberadamente "atrativos" para demonstrar o problema
-- que a V76 resolve: sem classificação, um T4 em Cascais a 180.000 € parece a
-- melhor oportunidade do site e domina qualquer ordenação por preço ou €/m².
--
-- NOTA: ownership_type / usage_restriction / special_condition_summary são
-- escritos aqui explicitamente. Em imóveis criados pela aplicação isto é
-- automático (SpecialConditionClassifier lê a descrição), mas um INSERT direto
-- não passa por esse código — e as descrições abaixo são exatamente as que o
-- classificador reconhece, pelo que uma edição futura via UI reclassifica para
-- os mesmos valores. is_special_condition não é escrita: é coluna GERADA.
-- ============================================================

-- 1/5 — Moradia T4 em Cascais: nua propriedade COM usufrutuária a residir.
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, lot_area_m2, construction_year, energy_rating, sun_exposure,
  has_garage, has_garden,
  ownership_type, usage_restriction, special_condition_summary,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd3000001-0000-0000-0000-000000000001', 'demo-nua-propriedade-cascais',
  'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt'), NULL),
  'manual', 'published', 'sale', 'house', 'used_good',
  'Moradia T4 em Cascais — Nua Propriedade', 'MORADIA T4 EM CASCAIS — NUA PROPRIEDADE',
  'Excelente moradia T4 em Cascais. Oportunidade de investimento em Nua Propriedade com usufrutuária de 74 anos.',
  'excelente moradia t4 em cascais. oportunidade de investimento em nua propriedade com usufrutuaria de 74 anos.',
  180000.00, 'EUR', 4, 3.0,
  220.0, 480.0, 1998, 'C', 'sul',
  true, true,
  'NUDE_OWNERSHIP', 'TENANT_VITALICIO',
  'Venda exclusiva do direito de propriedade. O morador atual mantém direito de habitação vitalício.',
  'Cascais', 'Lisboa', 'Cascais e Estoril', 'PT', 38.6979, -9.4215,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd3000001-0000-0000-0000-000000000001', 180000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd3000001-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- 2/5 — T2 no Estoril: venda de 50% da quota parte.
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, floor_number, total_floors, construction_year, energy_rating, sun_exposure,
  has_elevator, has_balcony,
  ownership_type, usage_restriction, special_condition_summary,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd3000002-0000-0000-0000-000000000001', 'demo-quota-parte-estoril',
  'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.sofia@properia.pt'),
           (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'apartment', 'used_good',
  'T2 no Estoril — Venda de Quota Parte', 'T2 NO ESTORIL — VENDA DE QUOTA PARTE',
  'Venda de 50% da quota parte de um fantástico T2 a 5 minutos da praia. Imóvel em compropriedade.',
  'venda de 50% da quota parte de um fantastico t2 a 5 minutos da praia. imovel em compropriedade.',
  120000.00, 'EUR', 2, 1.0,
  85.0, 2, 4, 2004, 'C', 'poente',
  true, true,
  'PARTIAL_SHARE', 'NONE',
  'Aquisição referente apenas a uma fração/quota parte da propriedade total.',
  'Cascais', 'Lisboa', 'Cascais e Estoril', 'PT', 38.7057, -9.3977,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd3000002-0000-0000-0000-000000000001', 120000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd3000002-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- 3/5 — Studio em resort de Albufeira: exploração turística, 14 dias/ano.
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, construction_year, energy_rating, sun_exposure,
  has_pool,
  ownership_type, usage_restriction, special_condition_summary,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd3000003-0000-0000-0000-000000000001', 'demo-exploracao-turistica-albufeira',
  'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.joao@properia.pt'),
           (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'studio', 'used_good',
  'Studio T0 em Resort — Albufeira', 'STUDIO T0 EM RESORT — ALBUFEIRA',
  'Inserido em resort de 4 estrelas com exploração turística ativa. Utilização do proprietário limitada a 14 dias por ano.',
  'inserido em resort de 4 estrelas com exploracao turistica ativa. utilizacao do proprietario limitada a 14 dias por ano.',
  95000.00, 'EUR', 0, 1.0,
  38.0, 2008, 'B', 'sul',
  true,
  'FULL', 'TOURISTIC_EXPLORATION',
  'Imóvel sob contrato de exploração hoteleira com limite anual de utilização pelo proprietário.',
  'Albufeira', 'Faro', 'Albufeira e Olhos de Água', 'PT', 37.0891, -8.2503,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd3000003-0000-0000-0000-000000000001', 95000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd3000003-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- 4/5 — T3 nas Avenidas Novas: nua propriedade com usufruto reservado.
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, floor_number, total_floors, construction_year, energy_rating, sun_exposure,
  has_elevator,
  ownership_type, usage_restriction, special_condition_summary,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd3000004-0000-0000-0000-000000000001', 'demo-nua-propriedade-avenidas-novas',
  'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'iarussiraphael@gmail.com'),
           (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'apartment', 'used_good',
  'T3 nas Avenidas Novas — Nua Propriedade', 'T3 NAS AVENIDAS NOVAS — NUA PROPRIEDADE',
  'Venda de Nua Propriedade de apartamento T3 nas Avenidas Novas. Usufruto vitalício reservado aos atuais proprietários.',
  'venda de nua propriedade de apartamento t3 nas avenidas novas. usufruto vitalicio reservado aos atuais proprietarios.',
  210000.00, 'EUR', 3, 2.0,
  128.0, 3, 6, 1975, 'D', 'nascente',
  true,
  'NUDE_OWNERSHIP', 'TENANT_VITALICIO',
  'Venda exclusiva do direito de propriedade. O morador atual mantém direito de habitação vitalício.',
  'Lisboa', 'Lisboa', 'Avenidas Novas', 'PT', 38.7369, -9.1462,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd3000004-0000-0000-0000-000000000001', 210000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd3000004-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- 5/5 — T1 em Vilamoura: aparthotel com exploração hoteleira cedida.
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, construction_year, energy_rating, sun_exposure,
  has_pool, has_elevator,
  ownership_type, usage_restriction, special_condition_summary,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd3000005-0000-0000-0000-000000000001', 'demo-aparthotel-vilamoura',
  'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.sofia@properia.pt'),
           (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'apartment', 'used_good',
  'T1 em Vilamoura — Aparthotel', 'T1 EM VILAMOURA — APARTHOTEL',
  'T1 em aparthotel com rendimento garantido de 5% ao ano. Cede-se a exploração hoteleira total.',
  't1 em aparthotel com rendimento garantido de 5% ao ano. cede-se a exploracao hoteleira total.',
  115000.00, 'EUR', 1, 1.0,
  52.0, 2011, 'B', 'sul',
  true, true,
  'FULL', 'TOURISTIC_EXPLORATION',
  'Imóvel sob contrato de exploração hoteleira com limite anual de utilização pelo proprietário.',
  'Loulé', 'Faro', 'Quarteira', 'PT', 37.0808, -8.1168,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd3000005-0000-0000-0000-000000000001', 115000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd3000005-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- V77 completada: 5 imóveis com condições especiais, escondidos da pesquisa
-- por omissão e visíveis com includeSpecialConditions=true.
--   Nua propriedade:        Cascais 180k€ · Avenidas Novas 210k€
--   Quota parte:            Estoril 120k€
--   Exploração turística:   Albufeira 95k€ · Vilamoura 115k€
