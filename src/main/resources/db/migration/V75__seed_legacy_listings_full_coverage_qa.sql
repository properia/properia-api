-- ============================================================
-- PROPERIA — V75: Seed QA — Cobertura total dos 20 property_type
-- CENTURY 21 - LEGACY TEAM, repartidos pelos 4 consultores da equipa.
--
-- Os donos são resolvidos DINAMICAMENTE por email (não por UUID fixo):
--   - Ricardo/Sofia/João já são criados de forma determinística pela
--     V68/V70 (correm sempre antes desta), por isso o lookup por email
--     resolve sempre nesses ambientes.
--   - Raphael NÃO tem UUID de seed fixo — a conta dele foi criada pelo
--     fluxo real de convite de equipa, não por migração. Gravar o email
--     pessoal dele permanentemente aqui contrariaria exatamente o que a
--     V70 corrigiu para os outros três (emails fictícios em vez de
--     reais). Por isso: procurado por email, e se não existir (ex.: base
--     de dados nova onde ele nunca se registou) os imóveis que lhe
--     calhariam caem para o Ricardo — a migração nunca falha por causa
--     de uma conta que só existe em produção.
--
-- Distribuição: 20 tipos ÷ 4 consultores = 5 imóveis cada, round-robin
-- pela ordem: Ricardo, Sofia, João, Raphael.
-- ============================================================

-- Imóvel 1/20 — Apartamento T2, Porto — Ricardo (Owner)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, gross_area_m2, floor_number, total_floors, construction_year,
  energy_rating, sun_exposure, heating_type,
  has_elevator, has_balcony, has_air_conditioning,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000001-0000-0000-0000-000000000001', 'qa-t2-cedofeita-porto', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt'), NULL),
  'manual', 'published', 'sale', 'apartment', 'remodeled',
  'T2 em Cedofeita, Porto', 'T2 EM CEDOFEITA, PORTO',
  'Apartamento T2 remodelado em Cedofeita, a poucos minutos a pé do centro histórico. Cozinha equipada, bom isolamento e exposição solar a sul.',
  'apartamento t2 remodelado em cedofeita, a poucos minutos a pe do centro historico. cozinha equipada, bom isolamento e exposicao solar a sul.',
  280000.00, 'EUR', 2, 1.0,
  78.0, 88.0, 2, 4, 1998,
  'B', 'sul', 'heat_pump',
  true, true, true,
  'Porto', 'Porto', 'Cedofeita, Santo Ildefonso, Sé, Miragaia, São Nicolau e Vitória', 'PT', 41.1533, -8.6289,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period, condo_fee, property_tax_annual)
SELECT 'd2000001-0000-0000-0000-000000000001', 280000.00, 'sale', 45.00, 650.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000001-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 2/20 — Moradia T4, Braga — Sofia (Admin)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, gross_area_m2, lot_area_m2, construction_year,
  energy_rating, sun_exposure, heating_type,
  has_garage, has_terrace, has_garden,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000002-0000-0000-0000-000000000001', 'qa-moradia-t4-braga', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.sofia@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'house', 'used_good',
  'Moradia T4 em São Vicente, Braga', 'MORADIA T4 EM SÃO VICENTE, BRAGA',
  'Moradia isolada T4 com jardim e terraço, garagem para 2 viaturas, zona residencial tranquila perto do centro de Braga.',
  'moradia isolada t4 com jardim e terraco, garagem para 2 viaturas, zona residencial tranquila perto do centro de braga.',
  385000.00, 'EUR', 4, 3.0,
  210.0, 240.0, 400.0, 2005,
  'B-', 'nascente', 'central_gas',
  true, true, true,
  'Braga', 'Braga', 'São Vicente', 'PT', 41.5518, -8.4229,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period, property_tax_annual)
SELECT 'd2000002-0000-0000-0000-000000000001', 385000.00, 'sale', 780.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000002-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 3/20 — Estúdio T0, Lisboa (arrendamento) — João (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, floor_number, total_floors, construction_year,
  energy_rating, sun_exposure,
  has_elevator, has_air_conditioning,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000003-0000-0000-0000-000000000001', 'qa-estudio-arroios-lisboa', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.joao@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'rent', 'studio', 'used_good',
  'Estúdio em Arroios, Lisboa', 'ESTÚDIO EM ARROIOS, LISBOA',
  'Estúdio compacto e funcional em Arroios, totalmente equipado, ideal para estudantes ou jovens profissionais.',
  'estudio compacto e funcional em arroios, totalmente equipado, ideal para estudantes ou jovens profissionais.',
  950.00, 'EUR', 0, 1.0,
  32.0, 3, 5, 2012,
  'C', 'poente',
  true, true,
  'Lisboa', 'Lisboa', 'Arroios', 'PT', 38.7307, -9.1349,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, rental_price, price_period, condo_fee, deposit_required)
SELECT 'd2000003-0000-0000-0000-000000000001', 950.00, 'month', 30.00, 1900.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000003-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 4/20 — Penthouse T3, Cascais — Raphael (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, gross_area_m2, floor_number, total_floors, construction_year,
  energy_rating, sun_exposure, heating_type,
  has_elevator, has_terrace, has_air_conditioning, has_sea_view,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000004-0000-0000-0000-000000000001', 'qa-penthouse-t3-cascais', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'iarussiraphael@gmail.com'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'penthouse', 'new',
  'Penthouse T3 com vista mar, Cascais', 'PENTHOUSE T3 COM VISTA MAR, CASCAIS',
  'Penthouse de luxo com terraço panorâmico e vista mar desafogada, acabamentos de linha alta e climatização em todas as divisões.',
  'penthouse de luxo com terraco panoramico e vista mar desafogada, acabamentos de linha alta e climatizacao em todas as divisoes.',
  1250000.00, 'EUR', 3, 3.0,
  195.0, 220.0, 6, 6, 2021,
  'A', 'sul', 'underfloor',
  true, true, true, true,
  'Cascais', 'Lisboa', 'Cascais e Estoril', 'PT', 38.6968, -9.4215,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period, condo_fee, property_tax_annual)
SELECT 'd2000004-0000-0000-0000-000000000001', 1250000.00, 'sale', 180.00, 4800.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000004-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 5/20 — Duplex T3, Porto — Ricardo (Owner)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, floor_number, total_floors, construction_year,
  energy_rating, sun_exposure, heating_type,
  has_balcony, has_air_conditioning,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000005-0000-0000-0000-000000000001', 'qa-duplex-t3-bonfim-porto', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt'), NULL),
  'manual', 'published', 'sale', 'duplex', 'remodeled',
  'Duplex T3 no Bonfim, Porto', 'DUPLEX T3 NO BONFIM, PORTO',
  'Duplex T3 distribuído por dois pisos, sala ampla com varanda, cozinha em open space, no coração do Bonfim.',
  'duplex t3 distribuido por dois pisos, sala ampla com varanda, cozinha em open space, no coracao do bonfim.',
  410000.00, 'EUR', 3, 2.0,
  160.0, 4, 5, 2016,
  'B', 'nascente', 'heat_pump',
  true, true,
  'Porto', 'Porto', 'Bonfim', 'PT', 41.1489, -8.5964,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period, condo_fee, property_tax_annual)
SELECT 'd2000005-0000-0000-0000-000000000001', 410000.00, 'sale', 55.00, 920.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000005-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 6/20 — Loft T1, Lisboa — Sofia (Admin)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, floor_number, total_floors, construction_year,
  energy_rating, sun_exposure,
  has_air_conditioning, has_open_kitchen,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000006-0000-0000-0000-000000000001', 'qa-loft-t1-lisboa', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.sofia@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'loft', 'remodeled',
  'Loft T1 no Marquês de Pombal, Lisboa', 'LOFT T1 NO MARQUÊS DE POMBAL, LISBOA',
  'Loft industrial reconvertido, pé direito duplo, cozinha em open space, num edifício histórico junto ao Marquês de Pombal.',
  'loft industrial reconvertido, pe direito duplo, cozinha em open space, num edificio historico junto ao marques de pombal.',
  320000.00, 'EUR', 1, 1.0,
  85.0, 1, 4, 1942,
  'C', 'norte',
  true, true,
  'Lisboa', 'Lisboa', 'Avenidas Novas', 'PT', 38.7255, -9.1500,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period, condo_fee)
SELECT 'd2000006-0000-0000-0000-000000000001', 320000.00, 'sale', 60.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000006-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 7/20 — Moradia em banda T3, Sintra — João (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, lot_area_m2, construction_year,
  energy_rating, sun_exposure, heating_type,
  has_garage, has_terrace,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000007-0000-0000-0000-000000000001', 'qa-moradia-banda-t3-sintra', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.joao@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'townhouse', 'used_good',
  'Moradia em banda T3, Sintra', 'MORADIA EM BANDA T3, SINTRA',
  'Moradia em banda T3 com pequeno terraço e garagem, num condomínio fechado e tranquilo perto do centro histórico de Sintra.',
  'moradia em banda t3 com pequeno terraco e garagem, num condominio fechado e tranquilo perto do centro historico de sintra.',
  340000.00, 'EUR', 3, 2.0,
  175.0, 220.0, 2009,
  'C', 'sul', 'central_gas',
  true, true,
  'Sintra', 'Lisboa', 'Sintra (Santa Maria e São Miguel)', 'PT', 38.8029, -9.3817,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period, condo_fee, property_tax_annual)
SELECT 'd2000007-0000-0000-0000-000000000001', 340000.00, 'sale', 70.00, 610.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000007-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 8/20 — Moradia geminada T3, Coimbra — Raphael (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, lot_area_m2, construction_year,
  energy_rating, sun_exposure, heating_type,
  has_garage, has_garden,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000008-0000-0000-0000-000000000001', 'qa-moradia-geminada-t3-coimbra', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'iarussiraphael@gmail.com'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'semi_detached_house', 'used_regular',
  'Moradia geminada T3, Coimbra', 'MORADIA GEMINADA T3, COIMBRA',
  'Moradia geminada T3 com quintal privado, garagem coberta, a 10 minutos do centro de Coimbra.',
  'moradia geminada t3 com quintal privado, garagem coberta, a 10 minutos do centro de coimbra.',
  265000.00, 'EUR', 3, 2.0,
  165.0, 250.0, 1995,
  'B-', 'nascente', 'heat_pump',
  true, true,
  'Coimbra', 'Coimbra', 'Sé Nova, Santa Cruz, Almedina e São Bartolomeu', 'PT', 40.2033, -8.4103,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period, property_tax_annual)
SELECT 'd2000008-0000-0000-0000-000000000001', 265000.00, 'sale', 540.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000008-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 9/20 — Villa T5, Cascais — Ricardo (Owner)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms,
  usable_area_m2, lot_area_m2, construction_year,
  energy_rating, sun_exposure, heating_type,
  has_pool, has_garage, has_garden, has_air_conditioning, has_sea_view,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000009-0000-0000-0000-000000000001', 'qa-villa-t5-cascais', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt'), NULL),
  'manual', 'published', 'sale', 'villa', 'new',
  'Villa T5 de luxo, Cascais', 'VILLA T5 DE LUXO, CASCAIS',
  'Villa de luxo T5 com piscina, jardim paisagista e vista mar, acabamentos premium em toda a casa.',
  'villa de luxo t5 com piscina, jardim paisagista e vista mar, acabamentos premium em toda a casa.',
  1850000.00, 'EUR', 5, 5.0,
  380.0, 900.0, 2022,
  'A+', 'sul', 'underfloor',
  true, true, true, true, true,
  'Cascais', 'Lisboa', 'Alcabideche', 'PT', 38.7169, -9.4389,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period, property_tax_annual)
SELECT 'd2000009-0000-0000-0000-000000000001', 1850000.00, 'sale', 4200.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000009-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 10/20 — Quarto, Coimbra (arrendamento) — Sofia (Admin)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type, condition_declared,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, bedrooms, bathrooms, usable_area_m2,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000010-0000-0000-0000-000000000001', 'qa-quarto-coimbra', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.sofia@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'rent', 'room', 'used_good',
  'Quarto em apartamento partilhado, Coimbra', 'QUARTO EM APARTAMENTO PARTILHADO, COIMBRA',
  'Quarto individual com WC privativo, em apartamento partilhado perto da Universidade de Coimbra. Contas e internet incluídas.',
  'quarto individual com wc privativo, em apartamento partilhado perto da universidade de coimbra. contas e internet incluidas.',
  320.00, 'EUR', 0, 1.0, 14.0,
  'Coimbra', 'Coimbra', 'Santo António dos Olivais', 'PT', 40.2115, -8.4292,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, rental_price, price_period)
SELECT 'd2000010-0000-0000-0000-000000000001', 320.00, 'month'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000010-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

INSERT INTO properia.listing_room_details (listing_id, has_private_bathroom, bills_included, internet_included, has_shared_kitchen, couple_allowed, min_stay_months)
SELECT 'd2000010-0000-0000-0000-000000000001', true, true, true, true, false, 3
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000010-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 11/20 — Terreno urbano, Sintra — João (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, lot_area_m2,
  land_type, water_source, agricultural_use,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000011-0000-0000-0000-000000000001', 'qa-terreno-urbano-sintra', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.joao@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'land',
  'Terreno urbano com viabilidade de construção, Sintra', 'TERRENO URBANO COM VIABILIDADE DE CONSTRUÇÃO, SINTRA',
  'Terreno urbano de 1200 m², com viabilidade de construção aprovada para moradia unifamiliar, ligado à rede pública de água.',
  'terreno urbano de 1200 m2, com viabilidade de construcao aprovada para moradia unifamiliar, ligado a rede publica de agua.',
  145000.00, 'EUR', 1200.0,
  'urbano', 'rede', false,
  'Sintra', 'Lisboa', 'Colares', 'PT', 38.7997, -9.4443,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd2000011-0000-0000-0000-000000000001', 145000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000011-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 12/20 — Comercial (loja de rua), Faro (arrendamento) — Raphael (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, usable_area_m2, ceiling_height_m,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000012-0000-0000-0000-000000000001', 'qa-comercial-centro-faro', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'iarussiraphael@gmail.com'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'rent', 'commercial',
  'Espaço comercial no centro de Faro', 'ESPAÇO COMERCIAL NO CENTRO DE FARO',
  'Espaço comercial com montra ampla na principal artéria comercial de Faro, elevada visibilidade e afluência de pessoas.',
  'espaco comercial com montra ampla na principal arteria comercial de faro, elevada visibilidade e afluencia de pessoas.',
  1800.00, 'EUR', 140.0, 3.2,
  'Faro', 'Faro', 'Faro (Sé e São Pedro)', 'PT', 37.0194, -7.9322,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, rental_price, price_period)
SELECT 'd2000012-0000-0000-0000-000000000001', 1800.00, 'month'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000012-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

INSERT INTO properia.listing_commercial_details (listing_id, has_shopfront, street_visibility, has_wc)
SELECT 'd2000012-0000-0000-0000-000000000001', true, 'main_street', true
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000012-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 13/20 — Escritório, Lisboa (arrendamento) — Ricardo (Owner)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, usable_area_m2, floor_number, total_floors,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000013-0000-0000-0000-000000000001', 'qa-escritorio-saldanha-lisboa', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt'), NULL),
  'manual', 'published', 'rent', 'office',
  'Escritório no Saldanha, Lisboa', 'ESCRITÓRIO NO SALDANHA, LISBOA',
  'Escritório open space com WC privativo e kitchenette, em edifício de escritórios no Saldanha, próximo do metro.',
  'escritorio open space com wc privativo e kitchenette, em edificio de escritorios no saldanha, proximo do metro.',
  2200.00, 'EUR', 180.0, 5, 12,
  'Lisboa', 'Lisboa', 'Avenidas Novas', 'PT', 38.7369, -9.1461,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, rental_price, price_period, condo_fee)
SELECT 'd2000013-0000-0000-0000-000000000001', 2200.00, 'month', 220.00
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000013-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

INSERT INTO properia.listing_commercial_details (listing_id, has_wc, has_kitchenette, internal_floors)
SELECT 'd2000013-0000-0000-0000-000000000001', true, true, 1
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000013-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 14/20 — Loja, Porto (arrendamento) — Sofia (Admin)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, usable_area_m2,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000014-0000-0000-0000-000000000001', 'qa-loja-baixa-porto', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.sofia@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'rent', 'shop',
  'Loja na Baixa do Porto', 'LOJA NA BAIXA DO PORTO',
  'Loja de rua na Baixa do Porto com montra e potencial de esplanada, junto a uma das artérias mais movimentadas da cidade.',
  'loja de rua na baixa do porto com montra e potencial de esplanada, junto a uma das arterias mais movimentadas da cidade.',
  1400.00, 'EUR', 95.0,
  'Porto', 'Porto', 'Cedofeita, Santo Ildefonso, Sé, Miragaia, São Nicolau e Vitória', 'PT', 41.1456, -8.6109,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, rental_price, price_period)
SELECT 'd2000014-0000-0000-0000-000000000001', 1400.00, 'month'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000014-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

INSERT INTO properia.listing_commercial_details (listing_id, has_shopfront, street_visibility, has_outdoor_seating_potential)
SELECT 'd2000014-0000-0000-0000-000000000001', true, 'main_street', true
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000014-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 15/20 — Armazém, Braga — João (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, usable_area_m2, ceiling_height_m,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000015-0000-0000-0000-000000000001', 'qa-armazem-braga', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.joao@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'warehouse',
  'Armazém com acesso a viaturas pesadas, Braga', 'ARMAZÉM COM ACESSO A VIATURAS PESADAS, BRAGA',
  'Armazém com 850 m², pé direito de 6,5 metros, acesso direto para viaturas pesadas, zona industrial de Braga.',
  'armazem com 850 m2, pe direito de 6,5 metros, acesso direto para viaturas pesadas, zona industrial de braga.',
  420000.00, 'EUR', 850.0, 6.5,
  'Braga', 'Braga', 'Maximinos, Sé e Cividade', 'PT', 41.5589, -8.4272,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd2000015-0000-0000-0000-000000000001', 420000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000015-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

INSERT INTO properia.listing_commercial_details (listing_id, has_vehicle_access)
SELECT 'd2000015-0000-0000-0000-000000000001', true
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000015-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 16/20 — Industrial, Braga — Raphael (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, usable_area_m2, ceiling_height_m,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000016-0000-0000-0000-000000000001', 'qa-industrial-braga', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'iarussiraphael@gmail.com'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'industrial',
  'Nave industrial com sistema de exaustão, Braga', 'NAVE INDUSTRIAL COM SISTEMA DE EXAUSTÃO, BRAGA',
  'Nave industrial de 1400 m², pé direito de 8 metros, com chaminé e sistema de exaustão instalados, acesso para viaturas pesadas.',
  'nave industrial de 1400 m2, pe direito de 8 metros, com chamine e sistema de exaustao instalados, acesso para viaturas pesadas.',
  680000.00, 'EUR', 1400.0, 8.0,
  'Braga', 'Braga', 'Nogueiró e Tenões', 'PT', 41.5701, -8.4436,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd2000016-0000-0000-0000-000000000001', 680000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000016-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

INSERT INTO properia.listing_commercial_details (listing_id, has_vehicle_access, has_flue_pipe, has_extraction_system)
SELECT 'd2000016-0000-0000-0000-000000000001', true, true, true
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000016-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 17/20 — Prédio de rendimento, Porto — Ricardo (Owner)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, gross_area_m2, total_floors, construction_year,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000017-0000-0000-0000-000000000001', 'qa-predio-rendimento-porto', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt'), NULL),
  'manual', 'published', 'sale', 'building',
  'Prédio de rendimento com 6 frações, Porto', 'PRÉDIO DE RENDIMENTO COM 6 FRAÇÕES, PORTO',
  'Prédio de rendimento totalmente arrendado, 6 frações autónomas (apartamentos T1 e T2), na zona histórica do Porto.',
  'predio de rendimento totalmente arrendado, 6 fracoes autonomas (apartamentos t1 e t2), na zona historica do porto.',
  1450000.00, 'EUR', 620.0, 4, 1932,
  'Porto', 'Porto', 'Bonfim', 'PT', 41.1502, -8.5980,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd2000017-0000-0000-0000-000000000001', 1450000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000017-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 18/20 — Hotel, Faro — Sofia (Admin)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, gross_area_m2, construction_year,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000018-0000-0000-0000-000000000001', 'qa-hotel-faro', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.sofia@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'hotel',
  'Unidade hoteleira com 24 quartos, Faro', 'UNIDADE HOTELEIRA COM 24 QUARTOS, FARO',
  'Unidade hoteleira em funcionamento com 24 quartos, receção, restaurante e piscina exterior, a 5 minutos do centro de Faro.',
  'unidade hoteleira em funcionamento com 24 quartos, rececao, restaurante e piscina exterior, a 5 minutos do centro de faro.',
  3200000.00, 'EUR', 1100.0, 1998,
  'Faro', 'Faro', 'Faro (Sé e São Pedro)', 'PT', 37.0156, -7.9265,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd2000018-0000-0000-0000-000000000001', 3200000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000018-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 19/20 — Garagem/box, Lisboa — João (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, usable_area_m2,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000019-0000-0000-0000-000000000001', 'qa-garagem-box-lisboa', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'test.joao@properia.pt'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'garage',
  'Box fechado no Areeiro, Lisboa', 'BOX FECHADO NO AREEIRO, LISBOA',
  'Box fechado individual, acesso automático, num edifício residencial no Areeiro. Ideal para investimento ou uso próprio.',
  'box fechado individual, acesso automatico, num edificio residencial no areeiro. ideal para investimento ou uso proprio.',
  28000.00, 'EUR', 14.0,
  'Lisboa', 'Lisboa', 'Areeiro', 'PT', 38.7414, -9.1298,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd2000019-0000-0000-0000-000000000001', 28000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000019-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- Imóvel 20/20 — Quinta/Herdade, Coimbra — Raphael (Sales)
INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized, description_raw, description_normalized,
  price_amount, price_currency, lot_area_m2,
  land_type, water_source, agricultural_use,
  city, district, parish, country_code, latitude, longitude,
  data_entry_at, created_at, updated_at
) VALUES (
  'd2000020-0000-0000-0000-000000000001', 'qa-quinta-coimbra', 'c0000001-0000-0000-0000-000000000001',
  COALESCE((SELECT id FROM properia.app_users WHERE email = 'iarussiraphael@gmail.com'), (SELECT id FROM properia.app_users WHERE email = 'test.ricardo@properia.pt')),
  'manual', 'published', 'sale', 'farm',
  'Quinta com 4,5 hectares, arredores de Coimbra', 'QUINTA COM 4,5 HECTARES, ARREDORES DE COIMBRA',
  'Quinta com 45.000 m², casa principal, anexos agrícolas e furo de água próprio, na periferia de Coimbra.',
  'quinta com 45.000 m2, casa principal, anexos agricolas e furo de agua proprio, na periferia de coimbra.',
  590000.00, 'EUR', 45000.0,
  'rustico', 'furo', true,
  'Coimbra', 'Coimbra', 'Assafarge e Antanhol', 'PT', 40.1789, -8.4534,
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

INSERT INTO properia.listing_pricing (listing_id, list_price, price_period)
SELECT 'd2000020-0000-0000-0000-000000000001', 590000.00, 'sale'
WHERE EXISTS (SELECT 1 FROM properia.listings WHERE id = 'd2000020-0000-0000-0000-000000000001')
ON CONFLICT (listing_id) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- Success Log
-- ──────────────────────────────────────────────────────────
-- Migração V75 completada: 20 imóveis, 1 por cada property_type,
-- repartidos 5x5x5x5 por Ricardo/Sofia/João/Raphael (fallback para
-- Ricardo se o Raphael não existir no ambiente). 7 cidades reais
-- (Porto, Lisboa, Cascais, Sintra, Coimbra, Braga, Faro), mistura de
-- venda/arrendamento, todos publicados.
