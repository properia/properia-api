-- ─────────────────────────────────────────────────────────────────────────────
-- Transferir imóveis para outro anunciante (e, opcionalmente, para um angariador).
--
-- Serve o caso de carregar o inventário antes de a conta do cliente existir:
-- os imóveis ficam num anunciante temporário e passam para o definitivo depois.
--
-- USO:
--   psql "$DATABASE_URL" \
--     -v from_slug="staging-tmp" \
--     -v to_slug="ricardo-martins" \
--     -v owner_email="ricardo@exemplo.pt" \
--     -f scripts/reassign-listings.sql
--
-- Para transferir apenas alguns, troque o filtro do UPDATE por uma lista de
-- public_id (ver comentário abaixo).
-- ─────────────────────────────────────────────────────────────────────────────

\set ON_ERROR_STOP on

BEGIN;

-- `advertiser_id` decide de quem é o imóvel e quem o vê no CRM.
-- `owner_user_id` é o angariador, e é independente: serve para o RBAC de leads
-- (quem angariou o imóvel pode mexer nos leads que ele gera) e para as métricas
-- por consultor. Atribuir o imóvel a uma agência NÃO atribui os leads dele.
UPDATE properia.listings l
SET advertiser_id = (SELECT id FROM properia.advertisers WHERE slug = :'to_slug'),
    owner_user_id = (SELECT id FROM properia.app_users WHERE email = lower(:'owner_email')),
    updated_at    = now()
WHERE l.advertiser_id = (SELECT id FROM properia.advertisers WHERE slug = :'from_slug');
-- Alternativa, para transferir só alguns:
--   WHERE l.public_id IN ('RM-1', 'RM-2', 'RM-3');

-- Os leads e visitas já existentes apontam para o anunciante antigo e não são
-- arrastados pelo UPDATE acima — se houver algum, tem de seguir o imóvel, senão
-- fica órfão num anunciante que ninguém consulta.
UPDATE properia.leads ld
SET advertiser_id = l.advertiser_id
FROM properia.listings l
WHERE ld.listing_id = l.id AND ld.advertiser_id IS DISTINCT FROM l.advertiser_id;

UPDATE properia.visits v
SET advertiser_id = l.advertiser_id
FROM properia.listings l
WHERE v.listing_id = l.id AND v.advertiser_id IS DISTINCT FROM l.advertiser_id;

SELECT a.brand_name AS anunciante,
       count(*) FILTER (WHERE l.status = 'published') AS publicados,
       count(*) AS total,
       count(*) FILTER (WHERE l.owner_user_id IS NOT NULL) AS com_angariador
FROM properia.listings l
JOIN properia.advertisers a ON a.id = l.advertiser_id
GROUP BY a.brand_name
ORDER BY 1;

COMMIT;
