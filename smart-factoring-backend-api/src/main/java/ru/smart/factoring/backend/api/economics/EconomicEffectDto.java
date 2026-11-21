package ru.smart.factoring.backend.api.economics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Экономический эффект по прогону (дорожная карта, п. 1.2; Р-76): предложения прогона, пересчитанные при выбранных
 * ставке фондирования и базовом TR, годовой эффект и окупаемость в трёх масштабах.
 *
 * @param runId       прогон
 * @param calcDate    дата расчёта прогона
 * @param clients     клиентов в прогоне
 * @param run         ставки прогона (снимок параметров): с ними {@link #base} совпадает с итогами шага 5
 * @param scenario    выбранные ставки
 * @param base        предложения при ставках прогона
 * @param result      предложения при выбранных ставках
 * @param year        год при выбранных ставках: оборачиваемость и годовые суммы
 * @param time        время менеджеров
 * @param scales      демо, «ядро», сегмент
 * @param assumptions допущения расчёта
 */
public record EconomicEffectDto(long runId, LocalDate calcDate, int clients, Rates run, Rates scenario, Offers base,
                                Offers result, Year year, Time time, List<Scale> scales, Assumptions assumptions) {

    /**
     * @param fundingRate ставка фондирования, годовых (доля)
     * @param trBase      базовый TR - вероятность, что клиент согласится, без повышающих признаков (доля)
     */
    public record Rates(BigDecimal fundingRate, BigDecimal trBase) {
    }

    /**
     * Предложения прогона. «Портфель» - если согласятся все, «ожидаемое» - взвешено по TR каждого предложения.
     *
     * @param offers          предложений
     * @param belowMinIncome  предложений, у которых доход стал ниже минимального (при ставках прогона - 0): в суммы не входят
     * @param portfolioEad    сумма финансирования, если согласятся все
     * @param portfolioIncome доход банка, если согласятся все
     * @param expectedDeals   ожидаемое число сделок: Σ TR
     * @param expectedEad     ожидаемая сумма финансирования: Σ TR × EAD
     * @param revenue         ожидаемый процентный доход (цена для клиента): Σ TR × цена
     * @param funding         ожидаемые расходы на фондирование
     * @param expectedLoss    ожидаемые потери (EL)
     * @param opex            ожидаемые операционные расходы
     * @param income          ожидаемый доход банка: процентный доход − фондирование − EL − опер. расходы
     * @param avgTermDays     средний срок финансирования, взвешенный по EAD
     */
    public record Offers(int offers, int belowMinIncome, BigDecimal portfolioEad, BigDecimal portfolioIncome,
                         BigDecimal expectedDeals, BigDecimal expectedEad, BigDecimal revenue, BigDecimal funding,
                         BigDecimal expectedLoss, BigDecimal opex, BigDecimal income, BigDecimal avgTermDays) {
    }

    /**
     * @param turnover    сколько раз за год обновляется дебиторка: 365 / средний срок
     * @param expectedEad ожидаемая сумма финансирования за год
     * @param income      ожидаемый доход банка за год
     */
    public record Year(BigDecimal turnover, BigDecimal expectedEad, BigDecimal income) {
    }

    /**
     * @param savedHoursPerYear часов менеджеров в год: клиенты × (ручная проработка − с системой) × проработок в год
     * @param savedFte          ставок менеджеров
     * @param savedRub          стоимость этого времени, ₽ в год (в окупаемость не входит)
     */
    public record Time(BigDecimal savedHoursPerYear, BigDecimal savedFte, BigDecimal savedRub) {
    }

    /**
     * @param code           DEMO, CORE, SEGMENT
     * @param title          название
     * @param clients        клиентов
     * @param expectedEad    ожидаемая сумма финансирования за год
     * @param income         ожидаемый доход банка за год
     * @param runCost        сопровождение за год: постоянная часть + на клиента
     * @param effect         доход − сопровождение, ₽ в год
     * @param savedFte       высвобождаемых ставок менеджеров
     * @param paybackMonths  срок окупаемости внедрения, мес.; null - не окупается
     */
    public record Scale(String code, String title, int clients, BigDecimal expectedEad, BigDecimal income, BigDecimal runCost,
                        BigDecimal effect, BigDecimal savedFte, BigDecimal paybackMonths) {
    }

    /**
     * Допущения (smart-factoring.economics): обоснование - docs/economics.md.
     */
    public record Assumptions(int coreClients, int segmentClients, BigDecimal implementationCost, BigDecimal fixedAnnualCost,
                              BigDecimal annualCostPerClient, int reviewsPerYear, BigDecimal managerHourCost,
                              int fteHoursPerYear, int manualMinutesPerClient, int systemMinutesPerClient) {
    }
}
