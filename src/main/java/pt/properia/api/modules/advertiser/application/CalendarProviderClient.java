package pt.properia.api.modules.advertiser.application;

import java.util.List;

/**
 * Contrato comum a qualquer provider de calendário usado na sincronização por CONSULTOR
 * (UserCalendarSyncService). Implementações atuais: GoogleCalendarService, MicrosoftCalendarService.
 *
 * Nota: isto NÃO cobre o fluxo de sala de Google Meet da AGÊNCIA (GoogleCalendarService.
 * createMeetEvent, chamado a partir de VisitController.tryCreateGoogleMeet) — esse continua
 * um caminho à parte, específico do Google, e não muda com este contrato.
 */
public interface CalendarProviderClient {

    record TokenResult(String accessToken, String refreshToken, long expiresIn) {}

    /**
     * Resultado de um refresh. `refreshToken` vem sempre preenchido: para providers que não
     * rodam o refresh token (Google) é o mesmo que entrou; para os que rodam (Microsoft pode
     * emitir um novo a cada refresh) é o valor atualizado a persistir.
     */
    record RefreshResult(String accessToken, String refreshToken) {}

    record CalendarEventResult(String eventId) {}

    record BusyInterval(String start, String end) {}

    /** Troca o código de autorização (callback OAuth) por access + refresh token. */
    TokenResult exchangeAuthCode(String code, String redirectUri) throws Exception;

    /** Email da conta ligada, para mostrar na UI. Null (não lança) se a chamada falhar. */
    String fetchAccountEmail(String accessToken);

    /** Troca o refresh token por um novo access token (e possivelmente um novo refresh token). */
    RefreshResult refresh(String refreshToken) throws Exception;

    /** Cria um evento simples (sem sala de conferência própria) na agenda do consultor. */
    CalendarEventResult insertEvent(
        String accessToken, String summary, String location, String description,
        String startIso, String endIso, String timezone) throws Exception;

    /** Atualiza um evento já existente. Idempotente para evento já removido do lado do provider. */
    void updateEvent(
        String accessToken, String eventId, String summary, String location, String description,
        String startIso, String endIso, String timezone) throws Exception;

    /** Remove o evento. Idempotente — evento já inexistente não é erro. */
    void deleteEvent(String accessToken, String eventId) throws Exception;

    /**
     * Intervalos ocupados da agenda do consultor entre timeMin/timeMax.
     * @param accountEmail necessário para o Microsoft Graph (getSchedule pede a identidade do
     *                      calendário a consultar); o Google ignora-o (consulta sempre "primary").
     */
    List<BusyInterval> queryFreeBusy(String accessToken, String accountEmail, String timeMinIso, String timeMaxIso) throws Exception;

    /** Cifra um token para guardar em BD. */
    String encrypt(String plaintext);

    /** Decifra um token guardado em BD. */
    String decrypt(String ciphertext);

    /** true se este provider tem client-id/secret configurados neste ambiente. */
    boolean isConfigured();
}
