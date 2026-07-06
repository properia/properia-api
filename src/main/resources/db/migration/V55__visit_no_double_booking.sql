-- #4 Double-booking de visitas: impede, ao nível da BD, duas visitas CONFIRMADAS do mesmo
-- anunciante em horários sobrepostos. O check-then-act em código (hasConflict) tem TOCTOU;
-- esta constraint de exclusão é a rede de segurança definitiva.
--
-- Âmbito: só status = 'confirmed'. Pedidos ('requested') e lista de espera ('waitlist')
-- PODEM sobrepor-se de propósito, por isso ficam de fora.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Resolver sobreposições de visitas confirmadas pré-existentes (senão o ADD CONSTRAINT falha):
-- mantém, em cada grupo sobreposto, a de id menor (determinístico) e expira as restantes.
WITH ranked AS (
    SELECT id, advertiser_id,
           tstzrange(starts_at, COALESCE(ends_at, starts_at + interval '60 minutes'), '[)') AS rng
    FROM properia.visits
    WHERE status = 'confirmed'
),
to_demote AS (
    SELECT DISTINCT b.id AS demote_id
    FROM ranked a
    JOIN ranked b
      ON a.advertiser_id = b.advertiser_id
     AND a.id < b.id
     AND a.rng && b.rng
)
UPDATE properia.visits v
SET status = 'expired'::properia.visit_status,
    status_reason = 'auto_expired_overlap_dedup',
    updated_at = now()
FROM to_demote d
WHERE v.id = d.demote_id;

ALTER TABLE properia.visits
    ADD CONSTRAINT visits_no_overlap_confirmed
    EXCLUDE USING gist (
        advertiser_id WITH =,
        tstzrange(starts_at, COALESCE(ends_at, starts_at + interval '60 minutes'), '[)') WITH &&
    ) WHERE (status = 'confirmed');
