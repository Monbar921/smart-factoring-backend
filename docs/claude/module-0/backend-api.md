# Модуль 0.3. API backend

Базовый путь в backend — `/internal/rest`, снаружи через gateway — `/api/backend` (маршрут уже есть).
JSON в camelCase, деньги — числа в рублях с 2 знаками, доли — в долях единицы, даты — `YYYY-MM-DD`,
моменты — ISO-8601 с зоной. Списки с пагинацией: `?page=0&size=50` → `{items, page, size, total}`.

Ошибки — единый формат, HTTP-код по смыслу:

```json
{ "code": "RUN_ALREADY_RUNNING", "message": "Прогон уже выполняется", "details": { "runId": 12 } }
```

| Код | HTTP | Когда |
|---|---|---|
| `RUN_ALREADY_RUNNING` | 409 | ПР-3 |
| `NOT_FOUND` | 404 | нет прогона/УПД/клиента/предложения |
| `OFFER_NOT_QUEUED` | 409 | решение по предложению не в статусе «в очереди» |
| `VALIDATION_ERROR` | 400 | неверные параметры |
| `IMPORT_INVALID` | 400 | выгрузка не прошла проверки, `details.errors[]` |

Справочник кодов (используются в полях `status`, `reason` и т. п.) — в `data-model.md`; подписи на русском
UI берёт из своего словаря. Причины отказа: `UPD_PAID`, `UPD_OVERDUE`, `ALREADY_DECIDED`, `STOP_FACTOR`, `NO_BUYER_DATA`,
`NO_PAYMENT_HISTORY`, `SCORING_ERROR`, `RISK_LEVEL_D`, `OVERDUE_90_PROBABLE`, `LIMIT_EXHAUSTED`, `DELAY_PROBABLE_LEVEL_C`,
`SMALL_AMOUNT`, `SHORT_TERM`, `INCOME_BELOW_MIN`, `NOT_IN_TOP_N`.

---

## Экран процесса

### `GET /runs/defaults` — значения для формы запуска (ЭП-1)

```json
{
  "dataDate": "2026-10-24",
  "snapshotDate": "2026-10-20",
  "clients": [ { "id": null, "inn": "7710000001", "name": "ООО «Альфа-Трейд»" } ],
  "runningRunId": null
}
```

### `POST /runs` — запуск (ПР-2, ПР-3)

Запрос (оба поля необязательны): `{ "clientInns": ["7710000001"], "calcDate": "2026-10-24" }`.
`clientInns` не задан (Р-29) — все клиенты; `calcDate` не задан — последняя дата данных.
Ответ `202`: `{ "runId": 13 }`. Если прогон уже идёт — `409 RUN_ALREADY_RUNNING`.
Инициатор берётся из JWT (`preferred_username`), тип — `OPERATOR` с экрана процесса, `API` при заголовке
`X-Initiator-Type: API`.

### `GET /runs?page&size` — история прогонов (ЭП-8)

```json
{ "items": [ {
    "id": 13, "status": "DONE", "calcDate": "2026-10-24",
    "startedAt": "2026-10-25T10:00:00+03:00", "finishedAt": "2026-10-25T10:00:41+03:00", "durationMs": 41000,
    "initiator": "operator", "clientCount": 6, "openUpdCount": 28, "offerCount": 12, "eadTotal": 41975000.00
  } ], "page": 0, "size": 50, "total": 1 }
```

### `GET /runs/{id}` — статус, шаги, числа (ЭП-2, ЭП-3, ЭП-6, ЭП-9)

UI опрашивает раз в 2–3 с, пока `status = RUNNING`.

```json
{
  "id": 13, "status": "RUNNING", "calcDate": "2026-10-24", "clientInns": null,
  "initiatorType": "OPERATOR", "initiator": "operator",
  "startedAt": "...", "finishedAt": null, "durationMs": 12000,
  "dataDate": "2026-10-24", "snapshotDate": "2026-10-20",
  "modelVersions": { "pd": "pd-stub-1", "delay": "delay-stub-1", "llm": "stub" },
  "errorMessage": null,
  "steps": [
    { "stepNo": 1, "name": "Загрузка данных", "status": "DONE", "startedAt": "...", "durationMs": 800,
      "metrics": { "clients": 6, "newUpds": 37, "payments": 13, "buyers": 10, "buyersWithoutData": 1, "dataDate": "2026-10-24" } },
    { "stepNo": 2, "status": "DONE", "metrics": { "paymentsAllocated": 12, "paymentsUnallocated": 1, "amountUnallocated": 500000.00, "updsClosed": 9 } },
    { "stepNo": 3, "status": "DONE", "metrics": { "openUpds": 28, "byCategory": { "1": 15, "2": 9, "3": 1, "5": 1, "6": 1, "7": 1, "8": 1 }, "overdueNotifications": 4, "riskSignals": 1 } },
    { "stepNo": 4, "status": "RUNNING", "metrics": null },
    { "stepNo": 5, "status": "PENDING", "metrics": null }
  ],
  "comparison": { "clients": 6, "manualMinutes": 1200, "systemMinutes": 120, "runDurationMs": 41000 }
}
```

Метрики шагов 4 и 5 (ЭП-3):
- шаг 4: `stopFactorBuyers`, `noPaymentHistoryBuyers`, `lowDataBuyers`, `buyersByRiskLevel {A,B,C,D}`, `delayProbableUpds`,
  `scoringErrors`, `pdFromCache`;
- шаг 5: `offers`, `noOffers`, `noOfferByReason {код: число}`, `eadTotal`, `incomeTotal`, `expectedAccepted` (Σ TR, М-7),
  `queuedOffers`, `newOfferNotifications`, `revokedOffers`, `reminders`.

### `GET /runs/{id}/funnel` — воронка (ЭП-4)

Массив ступеней по порядку: `OPEN`, `CATEGORY_1_4`, `HAS_DATA`, `NO_STOP_FACTOR`, `HAS_HISTORY`, `PASSED_RULES`, `TOP_N`, `QUEUED`;
`count`/`amount` — сколько дошло до ступени, `dropped` — отсев на ней по причинам.

```json
[
  { "code": "OPEN", "title": "Открытые УПД", "count": 28, "amount": 87850000.00, "dropped": [] },
  { "code": "CATEGORY_1_4", "count": 24, "amount": 82350000.00, "dropped": [ { "reason": "UPD_OVERDUE", "count": 4, "amount": 4500000.00 } ] },
  { "code": "NO_STOP_FACTOR", "...": "..." },
  { "code": "HAS_DATA" }, { "code": "...": "..." }
]
```

### `GET /runs/{id}/upds` — детализация УПД (ЭП-5)

Фильтры: `clientId`, `buyerId`, `category`, `decision` (`OFFER`/`NO_OFFER`), `reason`, `stage` (код ступени воронки,
на которой УПД отсеян), `riskLevel`, `paymentStatus`; пагинация.

```json
{ "items": [ {
    "updId": 104, "number": "U-1004", "updDate": "2026-10-01",
    "client": { "id": 1, "inn": "7710000001", "name": "..." },
    "buyer": { "id": 1, "inn": "0277120989", "name": "..." },
    "amount": 5000000.00, "outstanding": 5000000.00, "plannedDueDate": "2026-11-30", "daysToDue": 37, "category": 2,
    "pd": 0.005, "riskLevel": "A", "pDelay": 0.10, "pOverdue90": 0.01,
    "decision": "OFFER", "reason": null, "eadFinal": 4500000.00, "incomeFinal": 14795.00
  } ], "page": 0, "size": 50, "total": 28 }
```

### `GET /runs/{id}/upds/{updId}/trace` — путь УПД по шагам (ЭП-5)

```json
{
  "upd": { "...": "как в списке" },
  "steps": [
    { "stepNo": 1, "summary": "Загружен в прогоне 13", "details": { "firstLoadedRunId": 13 } },
    { "stepNo": 2, "summary": "Оплачено 1 000 000 из 6 000 000", "details": { "allocations": [ { "paymentSourceId": "P-0003", "amount": 1000000.00, "rule": "UPD_NUMBER", "paymentDate": "2026-10-15" } ] } },
    { "stepNo": 3, "summary": "Категория 3", "details": { "plannedDueDate": "2026-11-14", "daysToDue": 21 } },
    { "stepNo": 4, "summary": "Покупатель A, PD 0,5%", "details": { "buyerScore": {}, "updScore": {} } },
    { "stepNo": 5, "summary": "Предложение 4 500 000", "details": { "decision": {} } }
  ],
  "log": [ { "loggedAt": "...", "level": "INFO", "stepNo": 4, "eventCode": "NO_PAIR_HISTORY", "message": "..." } ]
}
```

### `GET /runs/{id}/buyers`, `GET /runs/{id}/clients` — детализация (ЭП-5)

Покупатели: ИНН, название, стоп-фактор, есть данные / история, PD, источник, «мало данных», уровень, причины,
дата данных, число и сумма открытых УПД, остаток лимита. Клиенты: ИНН, название, «ядро», ликвидность, число УПД,
предложений, сумма финансирования. Фильтры: `riskLevel`, `stopFactor`, `lowData`, `hasPaymentHistory`.

### `GET /runs/{id}/log?level&stepNo&page&size` — журнал (ЭП-6)

`{ items: [ { id, loggedAt, level, stepNo, eventCode, message, clientId, buyerId, updId, offerId, details } ] }`

---

## Эмулятор CRM (ЭП-7)

### `GET /managers`

`[ { "id": 1, "code": "M1", "fullName": "Демо-менеджер", "queuedOffers": 12, "unreadNotifications": 6 } ]`

### `GET /managers/{id}/queue` — очередь предложений (п. 5.3)

Порядок: по убыванию ранга. Только `QUEUED`.

```json
[ {
  "offerId": 7, "updId": 104, "updNumber": "U-1004", "updDate": "2026-10-01",
  "client": { "id": 1, "name": "..." }, "buyer": { "id": 1, "inn": "...", "name": "..." },
  "ead": 4500000.00, "price": 102082.00, "priceRate": 0.2070, "termDays": 40, "riskLevel": "A", "pd": 0.005,
  "income": 14795.00, "el": 360.00, "tr": 0.105, "rank": 1553.00,
  "flags": { "delayProbable": false, "lowData": false, "reducedToLimit": false, "isNew": true },
  "reasonsText": "…", "messageDraft": "…", "queuedAt": "...", "lastRunId": 13
} ]
```

### `GET /managers/{id}/notifications?page&size`

`{ items: [ { id, runId, type, createdAt, client, buyer, updNumber, offerId, payload } ] }` — `type`: `NEW_OFFER`, `OVERDUE`
(`payload.outstanding`, `payload.overdueDays`), `REMINDER`, `RISK_SIGNAL`.

### `GET /clients/{id}/card` — карточка клиента (п. 5.5)

```json
{
  "client": { "id": 1, "inn": "...", "name": "...", "absLiquidity": 0.35 },
  "runId": 13, "calcDate": "2026-10-24", "dataDate": "2026-10-24", "snapshotDate": "2026-10-20",
  "summary": { "receivablesTotal": 17300000.00, "overdue": 300000.00, "availableForFinancing": 11550000.00, "cashGap": null },
  "offers": [ { "...": "как в очереди; сначала новые в прогоне, затем по рангу" } ],
  "decidedOffers": [ { "offerId": 5, "status": "TRANSFERRED", "decidedAt": "...", "decidedBy": "manager" } ],
  "upds": [ {
    "updId": 105, "number": "U-1005", "outstanding": 4000000.00, "category": 2,
    "pd": 0.005, "riskLevel": "A", "pDelay": 0.10, "pOverdue90": 0.25,
    "reasons": [ { "title": "…", "direction": "DECREASES_RISK", "value": 2.1 } ], "reasonsText": "…",
    "offerId": null, "noOfferReason": "OVERDUE_90_PROBABLE"
  } ]
}
```

`summary`: дебиторка всего — Σ остатков открытых УПД; просрочено — Σ остатков кат. 5–8; доступно к финансированию —
Σ `ead` предложений в очереди; кассовый разрыв — просроченное, если ликвидность клиента ниже порога (Р-15), иначе null.

### `POST /offers/{id}/transfer` — «Передать в факторинг» (п. 6.2, 6.4)

Пустое тело. Ответ `200`: предложение в новом статусе `TRANSFERRED` + `packageId` имитации ВТБ Факторинга.
Не `QUEUED` → `409 OFFER_NOT_QUEUED`.

### `POST /offers/{id}/reject` — «Отклонить» (п. 6.3, 6.4)

Тело необязательно: `{ "comment": "клиент не заинтересован" }`. Ответ `200` — предложение в статусе `REJECTED`.

---

## Служебные

- `POST /demo/import` — импорт выгрузки генератора. Тело `multipart/form-data`: ZIP с папкой выгрузки (поле `file`)
  **или** JSON `{ "path": "/data/demo/fixture-v1" }` — путь на сервере (для стенда и тестов).
  Ответ: `{ batchId, dataDate, snapshotDate, rowCounts: {clients: 8, ...} }`; ошибки — `400 IMPORT_INVALID`.
- `POST /demo/import/efrsb` — импорт `events.csv` (multipart `file` или `{ path }`). Ответ: `{ batchId, inns, cases, events }`.
- `GET /parameters` — параметры Приложения А: `[ { code, value, valueType, section, description } ]`.
