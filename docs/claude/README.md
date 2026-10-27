# SMF-33 — артефакты Claude

| Файл | Что внутри |
|---|---|
| [01-requirements-summary.md](01-requirements-summary.md) | Конспект BRD 2.1 и C4 0.2, маппинг контейнеров на репозитории, чек-лист правил и параметров |
| [02-decisions.md](02-decisions.md) | Архитектурные решения (предложенные и принятые) |
| [03-implementation-plan.md](03-implementation-plan.md) | План по модулям, порядок, критерии готовности |
| [04-open-questions.md](04-open-questions.md) | Открытые вопросы к постановщику |
| [06-progress.md](06-progress.md) | Ход работ по модулям |
| [07-report.md](07-report.md) | **Итоговый отчёт: что сделано, что проверено, что нужно решить** |
| [08-stand-test.md](08-stand-test.md) | Сквозная проверка стенда test: шаги и ожидаемые результаты |
| [09-llm-contract.md](09-llm-contract.md) | Контракт с LLM для коллеги: что уходит в промпт GigaChat и какой ответ ждём |
| [deploy-notes.md](deploy-notes.md) | Что добавить в smart-factoring-deploy: сервис ml, переменные |
| [tools/gen_perf_dataset.py](tools/gen_perf_dataset.py) | Генератор набора объёма демо для проверки времени прогона |
| [05-module-0-plan.md](05-module-0-plan.md) | Детальный план модуля 0: модель данных, Liquibase, контракты |
| [module-0/data-model.md](module-0/data-model.md) | Модель данных: основные таблицы и `demo_*`, ER-диаграмма |
| [module-0/backend-api.md](module-0/backend-api.md) | Контракт API backend для UI и эмулятора CRM |
| [module-0/ml-service-openapi.yaml](module-0/ml-service-openapi.yaml) | Контракт сервиса моделей (OpenAPI) — для коллеги и заглушки |
| [module-0/demo-dataset-format.md](module-0/demo-dataset-format.md) | Формат выгрузки генератора и `events.csv` — для коллеги и импорта |
| [module-0/reference-sim/](module-0/reference-sim/) | Эталонные скрипты блоков 2–3 и 5 на Python: ожидаемые результаты фикстуры |
| [../../app/src/test/resources/demo/fixture-v1/CASES.md](../../app/src/test/resources/demo/fixture-v1/CASES.md) | Фикстура: контрольные случаи К-1…К-18 и ожидаемые результаты |

Ветка во всех репозиториях: `feature/SMF-33`.
