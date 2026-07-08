-- [JURÍDICO] Reescreve os rótulos de zona AVALIATIVOS já guardados para uma forma
-- FACTUAL e verificável. Os antigos ("Zona muito bem servida" / "bem servida" / "com
-- serviços básicos") eram juízos de valor gerados pelo Properia (não pelo anunciante)
-- sobre dados OpenStreetMap não verificados — risco de prática comercial enganosa
-- (DL 57/2008). A partir de agora buildZoneLabel() emite a forma factual; esta migração
-- corrige as linhas já existentes para não continuarem a mostrar o juízo de valor até
-- serem reprocessadas.
--
-- A contagem (nº de tipos de serviço com POIs) é derivada do snapshot processado mais
-- recente de cada imóvel (payload->'categories', onde totalCount > 0).

WITH latest_snapshot AS (
    SELECT DISTINCT ON (sn.listing_id)
           sn.listing_id,
           (SELECT count(*)
              FROM jsonb_array_elements(COALESCE(sn.payload -> 'categories', '[]'::jsonb)) AS e
             WHERE COALESCE((e ->> 'totalCount')::int, 0) > 0) AS categories_with_pois
      FROM properia.listing_zone_snapshots sn
     WHERE sn.status = 'processed'
     ORDER BY sn.listing_id, sn.processed_at DESC NULLS LAST
)
UPDATE properia.listing_zone_scores s
   SET zone_label_primary = CASE
           WHEN ls.categories_with_pois <= 0 THEN 'Zona residencial'
           WHEN ls.categories_with_pois = 1 THEN '1 tipo de serviço a menos de 500 m'
           ELSE ls.categories_with_pois || ' tipos de serviço a menos de 500 m'
       END,
       updated_at = now()
  FROM latest_snapshot ls
 WHERE s.listing_id = ls.listing_id
   AND s.zone_label_primary IN (
       'Zona muito bem servida',
       'Zona bem servida',
       'Zona com serviços básicos'
   );
