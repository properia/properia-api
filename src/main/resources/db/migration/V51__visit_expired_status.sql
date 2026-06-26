-- Estado neutro para visitas cujo horário passou sem confirmação/cancelamento/outcome.
-- Não assume que a visita aconteceu (completed) nem que falhou (no_show) — fica
-- explicitamente marcada como "precisa de revisão" até o agente registar o desfecho real.
ALTER TYPE "properia"."visit_status" ADD VALUE IF NOT EXISTS 'expired';
