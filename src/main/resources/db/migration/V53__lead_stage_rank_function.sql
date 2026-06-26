-- O enum properia.lead_stage foi criado com ordem física ilógica
-- ('new','qualified','contacted',...) — 'qualified' antes de 'contacted'.
-- Recriar o tipo seria de alto risco (muitas colunas/defaults dependentes) e nada
-- depende hoje da ordem física do enum. Em vez disso, esta função é a ÚNICA fonte
-- canónica de ordenação do funil, usada em comparações e guardas de avanço de estágio
-- (avançar só "para a frente", nunca regredir). Usar sempre lead_stage_rank() em vez
-- de comparar/ordenar diretamente o enum.
CREATE OR REPLACE FUNCTION properia.lead_stage_rank(stage text)
RETURNS int
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE stage
    WHEN 'new'             THEN 0
    WHEN 'contacted'       THEN 1
    WHEN 'qualified'       THEN 2
    WHEN 'visit_scheduled' THEN 3
    WHEN 'proposal'        THEN 4
    WHEN 'won'             THEN 5
    WHEN 'lost'            THEN 5
    ELSE -1
  END
$$;
