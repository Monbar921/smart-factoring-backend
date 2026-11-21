package ru.smart.factoring.backend.api.monitoring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Мониторинг моделей и LLM по запуску (Р-87): версии, распределение оценок, сдвиг (PSI), доля ответов LLM, отклонённых
 * проверкой фактов.
 *
 * @param runId     запуск
 * @param models    версии моделей и LLM запуска: pd, delay, llm
 * @param pd        модель PD покупателя
 * @param delay     модель задержки оплаты по УПД
 * @param llm       тексты карточек
 * @param history   последние успешные запуски - для тренда, сначала новые
 */
public record MonitoringDto(long runId,
                            String status,
                            LocalDate calcDate,
                            OffsetDateTime startedAt,
                            Map<String, String> models,
                            Pd pd,
                            Delay delay,
                            Llm llm,
                            List<HistoryPoint> history) {

    /**
     * @param buyers        покупателей с оценкой в запуске
     * @param bySource      MODEL - PD модели, BASE - базовый PD «мало данных»
     * @param stopFactors   покупателей со стоп-фактором (PD не нужен)
     * @param fromCache     PD из кэша (п. 4.2)
     * @param levels        распределение уровней риска A-D всех оценённых покупателей
     * @param modelLevels   распределение уровней по PD модели (без базового PD) - по границам эталона, если он есть
     * @param psiReference  сдвиг PD модели относительно выборки разработки; null - у модели нет эталона
     * @param psiPrevious   сдвиг уровней относительно прошлого успешного запуска на той же версии модели
     * @param reference     эталон модели: выборка, размер, границы, доли
     */
    public record Pd(int buyers, Map<String, Integer> bySource, int stopFactors, int fromCache, BigDecimal meanPd,
                     List<Bucket> levels, List<Bucket> modelLevels, Psi psiReference, Psi psiPrevious, Reference reference) {
    }

    /**
     * @param upds        УПД, по которым запрашивалась модель
     * @param byStatus    SCORED, NO_PAYMENT_HISTORY, ERROR
     * @param buckets     распределение вероятности задержки больше порога
     * @param psiPrevious сдвиг относительно прошлого успешного запуска на той же версии модели
     */
    public record Delay(int upds, Map<String, Integer> byStatus, BigDecimal meanPDelay, Integer thresholdDays, List<Bucket> buckets,
                        Psi psiPrevious) {
    }

    /**
     * @param provider               провайдер текстов: stub, gigachat:модель:версия, onprem:…
     * @param cards                  карточек предложений в запуске
     * @param bySource               LLM, CACHE, TEMPLATE, FAILED
     * @param answers                ответов LLM в запуске (с повторами)
     * @param rejected               из них отклонено проверкой фактов или искажённой меткой
     * @param rejectedShare          rejected / answers; null - ответов не было
     * @param templateAfterFactCheck карточек, ушедших в шаблон после двух отклонённых ответов
     */
    public record Llm(String provider, int cards, Map<String, Integer> bySource, int answers, int rejected, BigDecimal rejectedShare,
                      int templateAfterFactCheck) {
    }

    /**
     * Корзина распределения: подпись, число, доля.
     */
    public record Bucket(String label, int count, BigDecimal share) {
    }

    /**
     * Индекс стабильности популяции.
     *
     * @param value     PSI
     * @param status    STABLE (< 0,1), WATCH (0,1–0,25), SHIFT (≥ 0,25)
     * @param baseline  с чем сравнивали: эталон модели или запуск № N
     * @param n         наблюдений в текущем запуске; меньше 100 - PSI ориентировочный
     */
    public record Psi(BigDecimal value, String status, String baseline, Long baselineRunId, int n) {
    }

    public record Reference(String sample, Long size, List<BigDecimal> cuts, Map<String, BigDecimal> shares) {
    }

    /**
     * Точка тренда: запуск, PSI PD к эталону и к прошлому запуску, доля отклонённых ответов LLM.
     */
    public record HistoryPoint(long runId, LocalDate calcDate, OffsetDateTime startedAt, String pdVersion, int buyers,
                               BigDecimal psiReference, BigDecimal psiPrevious, BigDecimal llmRejectedShare) {
    }
}
