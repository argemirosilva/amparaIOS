package tech.orizon.ampara.audio.phrase;

import android.util.Log;
import java.util.LinkedList;

/**
 * Rastreador de repetição por comando para validar regras de segurança.
 * 
 * Cada comando operacional tem seu próprio tracker com buffer circular
 * de matches recentes. Suporta validação de repetição e cooldown.
 * Conforme SPEC v3, seção 10.2.
 */
public class CommandRepetitionTracker {
    private static final String TAG = "CmdRepTracker";

    /**
     * Evento de match registrado no histórico
     */
    public static class MatchEvent {
        public final long timestamp;
        public final double confidence;
        public final double dtwDistance;

        public MatchEvent(long timestamp, double confidence, double dtwDistance) {
            this.timestamp = timestamp;
            this.confidence = confidence;
            this.dtwDistance = dtwDistance;
        }
    }

    private final String commandId;
    private final LinkedList<MatchEvent> history;
    private final int maxEntries;
    private final int maxAgeMs;
    private long lastActionTimestamp;

    /**
     * Cria tracker para um comando específico
     *
     * @param commandId  ex: "panic_start", "start_recording"
     * @param maxEntries limite do buffer circular
     * @param maxAgeMs   tempo máximo de retenção de entradas (ms)
     */
    public CommandRepetitionTracker(String commandId, int maxEntries, int maxAgeMs) {
        this.commandId = commandId;
        this.history = new LinkedList<>();
        this.maxEntries = maxEntries;
        this.maxAgeMs = maxAgeMs;
        this.lastActionTimestamp = 0;
    }

    /**
     * Registra um match no histórico
     */
    public void recordMatch(double confidence, double dtwDistance) {
        long now = System.currentTimeMillis();
        history.addLast(new MatchEvent(now, confidence, dtwDistance));

        // Limpar por tamanho (FIFO)
        while (history.size() > maxEntries) {
            history.removeFirst();
        }

        // Limpar entradas expiradas
        purgeExpired(now);

        Log.d(TAG, String.format("[RECORD] %s | conf=%.2f | histórico=%d entradas",
                commandId, confidence, history.size()));
    }

    /**
     * Verifica se há N repetições dentro de uma janela de tempo
     *
     * @param requiredCount número mínimo de matches
     * @param windowMs      janela de tempo em ms
     * @return true se a regra de repetição foi satisfeita
     */
    public boolean hasRepetitions(int requiredCount, int windowMs) {
        long now = System.currentTimeMillis();
        purgeExpired(now);

        int count = 0;
        for (MatchEvent event : history) {
            if (now - event.timestamp <= windowMs) {
                count++;
            }
        }

        boolean result = count >= requiredCount;
        if (result) {
            Log.d(TAG, String.format("[REPETITION] %s | %d/%d matches em %dms",
                    commandId, count, requiredCount, windowMs));
        }

        return result;
    }

    /**
     * Verifica se o cooldown expirou (pode executar nova ação)
     *
     * @param cooldownMs período de cooldown em ms
     * @return true se pode executar
     */
    public boolean isCooldownExpired(int cooldownMs) {
        if (lastActionTimestamp == 0)
            return true;
        return System.currentTimeMillis() - lastActionTimestamp >= cooldownMs;
    }

    /**
     * Marca que uma ação foi executada (inicia cooldown)
     */
    public void markActionExecuted() {
        lastActionTimestamp = System.currentTimeMillis();
        Log.d(TAG, String.format("[ACTION] %s | cooldown iniciado", commandId));
    }

    /**
     * Reseta o tracker (limpa histórico e cooldown)
     */
    public void reset() {
        history.clear();
        lastActionTimestamp = 0;
        Log.d(TAG, String.format("[RESET] %s", commandId));
    }

    /**
     * Remove entradas mais velhas que maxAgeMs
     */
    private void purgeExpired(long now) {
        while (!history.isEmpty() && (now - history.getFirst().timestamp) > maxAgeMs) {
            history.removeFirst();
        }
    }

    public String getCommandId() {
        return commandId;
    }

    public int getHistorySize() {
        return history.size();
    }

    public long getLastActionTimestamp() {
        return lastActionTimestamp;
    }
}
