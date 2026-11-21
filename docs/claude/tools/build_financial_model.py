"""Финансовая модель Smart Factoring (дорожная карта, п. 1.2; Р-76).

Строит xlsx по выгрузке предложений запуска (EconomicEffectManualTest, -Deconomics.out=<папка>):
    python build_financial_model.py <папка с offers.csv и economics.json> <выходной .xlsx>

Расчёт тот же, что GET /runs/{id}/economics: построчно п. 5.1 по каждому предложению, TR, оборачиваемость, масштабы,
сопровождение, окупаемость; лист «Сверка» сравнивает с ответом API.
"""
import csv
import json
import sys
from pathlib import Path

from openpyxl import Workbook
from openpyxl.comments import Comment
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

FONT = 'Arial'
BLUE = Font(name=FONT, color='0000FF')
BLACK = Font(name=FONT)
GREEN = Font(name=FONT, color='008000')
BOLD = Font(name=FONT, bold=True)
TITLE = Font(name=FONT, bold=True, size=14)
HEAD = Font(name=FONT, bold=True, color='FFFFFF')
HEAD_FILL = PatternFill('solid', start_color='1F4E79')
KEY_FILL = PatternFill('solid', start_color='FFFF00')
SECTION_FILL = PatternFill('solid', start_color='DDEBF7')
THIN = Side(style='thin', color='BFBFBF')
RUB = '#,##0;(#,##0);-'
RUB2 = '#,##0.00;(#,##0.00);-'
PCT = '0.0%;(0.0%);-'
PCT4 = '0.0000%'
NUM1 = '#,##0.0;(#,##0.0);-'
NUM = '#,##0;(#,##0);-'

A = "'Допущения'!"  # ссылка на лист допущений


def style_header(ws, row, titles, widths=None):
    for col, title in enumerate(titles, 1):
        cell = ws.cell(row=row, column=col, value=title)
        cell.font = HEAD
        cell.fill = HEAD_FILL
        cell.alignment = Alignment(wrap_text=True, vertical='center')
    if widths:
        for col, width in enumerate(widths, 1):
            ws.column_dimensions[get_column_letter(col)].width = width


def section(ws, row, title, cols=4):
    for col in range(1, cols + 1):
        ws.cell(row=row, column=col).fill = SECTION_FILL
    ws.cell(row=row, column=1, value=title).font = BOLD


def build(source: Path, target: Path):
    offers = list(csv.DictReader(open(source / 'offers.csv', encoding='utf-8'), delimiter=';'))
    api = json.load(open(source / 'economics.json', encoding='utf-8'))
    wb = Workbook()

    # ------------------------------------------------------------------ Описание
    ws = wb.active
    ws.title = 'Описание'
    ws.column_dimensions['A'].width = 120
    lines = [
        ('Smart Factoring — финансовая модель', TITLE),
        ('Команда Big Data Fish, хакатон ВТБ «Финтех без границ», спринт 2. Метод — docs/economics.md в smart-factoring-backend.', BLACK),
        ('', BLACK),
        ('Что внутри', BOLD),
        ('Допущения — все входные числа. Синие — вводимые значения; жёлтая заливка — рычаги сценария (ставка фондирования, TR) и ключевые допущения.', BLACK),
        (f'Предложения — {len(offers)} предложений запуска № {api["runId"]} от {api["calcDate"]} на данных демо (партии ML-команды, '
         f'{api["clients"]} клиентов, модели pd-1.0 и delay-1.0): по каждому та же формула п. 5.1 BRD, что в системе.', BLACK),
        ('Экономика — цепочка «предложения → TR → доход банка → за год → масштаб → сопровождение → окупаемость»; '
         'те же числа показывает экран «Экономический эффект».', BLACK),
        ('Денежный поток — 3 года с поэтапным запуском: пилот, «ядро» частично, «ядро» полностью.', BLACK),
        ('Сверка — Excel против ответа системы GET /runs/{id}/economics при ставках запуска: расхождение должно быть 0.', BLACK),
        ('', BLACK),
        ('Как пользоваться', BOLD),
        ('Меняйте жёлтые ячейки на листе «Допущения»: ставку фондирования и базовый TR (как ползунки на экране), затраты, этапы запуска.', BLACK),
        ('Пересобрать на новом запуске: выгрузка EconomicEffectManualTest → python docs/claude/tools/build_financial_model.py <папка> <xlsx>.', BLACK),
        ('', BLACK),
        ('Ограничения', BOLD),
        ('Клиенты, УПД и платежи демо — синтетика генератора; покупатели и их отчётность — реальные (RFSD, ЕГРЮЛ, ЕФРСБ). '
         'Средние на клиента — порядок величины, на пилоте модель пересчитывается на данных банка.', BLACK),
        ('TR 7% и ×1,5 за признак — правило BRD, не измерение; затраты — оценки команды. Время менеджеров в окупаемость не входит.', BLACK),
    ]
    for row, (text, font) in enumerate(lines, 1):
        cell = ws.cell(row=row, column=1, value=text)
        cell.font = font
        cell.alignment = Alignment(wrap_text=True)

    # ------------------------------------------------------------------ Допущения
    ws = wb.create_sheet('Допущения')
    style_header(ws, 1, ['Показатель', 'Значение', 'Ед.', 'Источник / обоснование'], [52, 16, 10, 90])
    refs = {}
    row = 2

    def put(key, title, value, fmt, unit, source_text, key_cell=False):
        nonlocal row
        ws.cell(row=row, column=1, value=title).font = BLACK
        cell = ws.cell(row=row, column=2, value=value)
        cell.font = BLUE if not (isinstance(value, str) and value.startswith('=')) else BLACK
        cell.number_format = fmt
        if key_cell:
            cell.fill = KEY_FILL
        ws.cell(row=row, column=3, value=unit).font = BLACK
        ws.cell(row=row, column=4, value=source_text).font = BLACK
        refs[key] = f'{A}$B${row}'
        row += 1

    section(ws, row, 'Рычаги сценария'); row += 1
    put('funding', 'Ставка фондирования (сценарий)', api['run']['fundingRate'], PCT, 'годовых', 'Как ползунок на экране; в запуске — 16% (Приложение А BRD, демо)', True)
    put('tr', 'Базовый TR — шанс согласия клиента (сценарий)', api['run']['trBase'], PCT, '', 'Как ползунок на экране; в запуске — 7% (п. 5.3 BRD, допущение)', True)
    section(ws, row, 'Ставки запуска (п. 5.1, Приложение А BRD)'); row += 1
    put('funding_run', 'Ставка фондирования в запуске', api['run']['fundingRate'], PCT, 'годовых', 'Снимок параметров запуска')
    put('tr_run', 'Базовый TR в запуске', api['run']['trBase'], PCT, '', 'Снимок параметров запуска')
    put('opex', 'Операционные расходы', 0.015, PCT, 'годовых', 'Приложение А BRD (демо)')
    put('margin', 'Маржа', 0.03, PCT, 'годовых', 'Приложение А BRD (демо)')
    put('market', 'Рыночная цена — потолок цены для клиента', 0.24, PCT, 'годовых', 'Приложение А BRD (демо)')
    put('basis', 'База дней', 365, NUM, 'дн.', 'п. 5.1 BRD: простые проценты, база 365')
    put('min_income', 'Минимальный доход банка по предложению', 3000, RUB, '₽', 'Приложение А BRD (демо)')
    section(ws, row, 'Масштаб'); row += 1
    put('clients_run', 'Клиентов в запуске', api['clients'], NUM, 'клиентов', 'Данные демо: 5 партий генератора ML-команды')
    put('core', '«Ядро»: деньги застряли у покупателей', api['assumptions']['coreClients'], NUM, 'компаний',
        'Расчёт команды по RFSD (ГИР БО ФНС), 19.10.2026: выручка 0,8–2 млрд ₽, оборачиваемость дебиторки > 90 дн. и абс. ликвидность < 0,2 (BRD «Проблема клиента»)', True)
    put('segment', 'Весь сегмент', api['assumptions']['segmentClients'], NUM, 'компаний',
        'RFSD: компании с выручкой 0,8–2 млрд ₽ и полной отчётностью за 2024–2025 годы, Россия (BRD «Проблема клиента»)', True)
    section(ws, row, 'Время менеджера (Ц-2, Приложение А BRD)'); row += 1
    put('manual', 'Ручная проработка клиента', 200, NUM, 'мин', 'Оценка команды по шагам, BRD «Проблема менеджера»; уточнить у менеджеров ВТБ')
    put('system', 'Время менеджера с системой', 20, NUM, 'мин', 'Допущение BRD (Ц-2)')
    put('reviews', 'Проработок клиента в год', 4, NUM, 'раз', 'Допущение: ежеквартальный пересмотр дебиторки', True)
    put('hour_cost', 'Час менеджера с начислениями', 1500, RUB, '₽', 'Допущение команды')
    put('fte_hours', 'Часов на ставку в год', 1970, NUM, 'ч', 'Норма рабочего времени при 40-часовой неделе')

    section(ws, row, 'Внедрение (разово): ставки × месяцы × стоимость ставки'); row += 1
    style_header(ws, row, ['Роль', 'Ставок', 'Месяцев', 'Стоимость ставки в месяц, ₽'])
    ws.column_dimensions['C'].width = 10
    row += 1
    first_role = row
    roles = [('Руководитель проекта', 1), ('Backend-разработчик', 2), ('Frontend-разработчик', 1), ('ML-инженер', 1),
             ('Аналитик', 1), ('Специалист по ИБ', 0.5)]
    for title, fte in roles:
        ws.cell(row=row, column=1, value=title).font = BLACK
        for col, value, fmt in ((2, fte, NUM1), (3, 6, NUM), (4, 400000, RUB)):
            cell = ws.cell(row=row, column=col, value=value)
            cell.font = BLUE
            cell.number_format = fmt
        row += 1
    last_role = row - 1
    ws.cell(row=last_role, column=4).comment = Comment('Стоимость ставки с начислениями — допущение команды', 'Big Data Fish')
    put('team_cost', 'Команда внедрения', f'=SUMPRODUCT(B{first_role}:B{last_role},C{first_role}:C{last_role},D{first_role}:D{last_role})',
        RUB, '₽', 'Интеграции с ЭДО, выписками, CRM и ВТБ Факторингом, ИБ, пилот')
    put('gpu', 'Сервер с GPU для LLM в контуре', 3000000, RUB, '₽', 'Допущение: один сервер под открытую модель 7–8 млрд параметров (LLM_MODE=onprem)')
    put('implementation', 'Внедрение, итого', f'={refs["team_cost"].split("!")[1]}+{refs["gpu"].split("!")[1]}', RUB, '₽', 'Команда + сервер', True)

    section(ws, row, 'Сопровождение (в год)'); row += 1
    put('support_fte', 'Ставок сопровождения', 2, NUM1, 'ставок', 'Допущение: разработчик и аналитик/ML')
    put('support_rate', 'Стоимость ставки в месяц', 400000, RUB, '₽', 'Как во внедрении')
    put('infra', 'Инфраструктура', 1200000, RUB, '₽/год', 'Допущение: серверы приложения, БД, резервирование')
    put('fixed_cost', 'Сопровождение, постоянная часть',
        f'={refs["support_fte"].split("!")[1]}*12*{refs["support_rate"].split("!")[1]}+{refs["infra"].split("!")[1]}', RUB, '₽/год', 'Ставки × 12 × стоимость + инфраструктура', True)
    put('per_client', 'Сопровождение на клиента', 600, RUB, '₽/год', 'Допущение: вычисления, хранение, тексты LLM')

    section(ws, row, 'Этапы запуска (лист «Денежный поток»)'); row += 1
    put('y1_clients', 'Год 1: клиентов пилота', 100, NUM, 'клиентов', 'Пилот на 100 клиентах (НФР-2.1)', True)
    put('y1_months', 'Год 1: месяцев работы пилота', 6, NUM, 'мес.', 'Первые 6 месяцев — внедрение', True)
    put('y2_share', 'Год 2: доля «ядра»', 0.3, PCT, '', 'Поэтапное подключение регионов', True)
    put('y3_share', 'Год 3: доля «ядра»', 1.0, PCT, '', 'Всё «ядро»', True)
    ws.freeze_panes = 'A2'

    r = {k: v for k, v in refs.items()}

    # ------------------------------------------------------------------ Предложения
    wp = wb.create_sheet('Предложения')
    titles = ['EAD, ₽', 'EL, ₽', 'Срок, дн.', 'PD на срок', 'LGD', 'TR запуска', 'Уровень', 'Цена в запуске, ₽', 'Доход в запуске, ₽',
              'Фондирование, ₽', 'Опер. расходы, ₽', 'Маржа, ₽', 'Потолок цены, ₽', 'Цена, ₽', 'Доход банка, ₽',
              'Доход при ставке запуска, ₽', 'Учитывается', 'TR сценария', 'Ожид. EAD, ₽', 'Ожид. цена, ₽', 'Ожид. фондирование, ₽',
              'Ожид. EL, ₽', 'Ожид. опер. расходы, ₽', 'Ожид. доход, ₽', 'EAD × срок']
    style_header(wp, 1, titles, [15, 11, 9, 10, 8, 10, 8, 14, 13, 13, 12, 12, 13, 13, 13, 14, 11, 10, 15, 13, 14, 11, 13, 13, 16])
    wp.freeze_panes = 'A2'
    first = 2
    for i, offer in enumerate(offers):
        n = first + i
        values = [float(offer['ead']), float(offer['el']), int(offer['term_days']), float(offer['pd_term']), float(offer['lgd']),
                  float(offer['tr']), offer['risk_level'], float(offer['price']), float(offer['income'])]
        for col, value in enumerate(values, 1):
            cell = wp.cell(row=n, column=col, value=value)
            cell.font = BLUE
        yf = f'C{n}/{r["basis"]}'
        formulas = [
            f'=ROUND(A{n}*{r["funding"]}*{yf},2)',                                   # J фондирование
            f'=ROUND(A{n}*{r["opex"]}*{yf},2)',                                      # K опер
            f'=ROUND(A{n}*{r["margin"]}*{yf},2)',                                    # L маржа
            f'=ROUND(A{n}*{r["market"]}*{yf},2)',                                    # M потолок
            f'=MIN(J{n}+B{n}+K{n}+L{n},M{n})',                                       # N цена
            f'=N{n}-J{n}-B{n}-K{n}',                                                 # O доход
            f'=MIN(ROUND(A{n}*{r["funding_run"]}*{yf},2)+B{n}+K{n}+L{n},M{n})-ROUND(A{n}*{r["funding_run"]}*{yf},2)-B{n}-K{n}',  # P
            f'=IF(AND(O{n}<{r["min_income"]},P{n}>={r["min_income"]}),0,1)',        # Q учитывается
            f'=Q{n}*MIN(1,F{n}*{r["tr"]}/{r["tr_run"]})',                            # R TR сценария
            f'=R{n}*A{n}', f'=R{n}*N{n}', f'=R{n}*J{n}', f'=R{n}*B{n}', f'=R{n}*K{n}', f'=R{n}*O{n}',  # S..X
            f'=Q{n}*A{n}*C{n}',                                                      # Y
        ]
        for col, formula in enumerate(formulas, 10):
            wp.cell(row=n, column=col, value=formula).font = BLACK
    last = first + len(offers) - 1
    for col in range(1, 26):
        letter = get_column_letter(col)
        fmt = {3: NUM, 4: PCT4, 5: PCT, 6: PCT, 7: '@', 17: NUM, 18: PCT}.get(col, RUB2)
        for n in range(first, last + 1):
            wp.cell(row=n, column=col).number_format = fmt
    for col in (1, 2, 8, 9):
        wp.cell(row=1, column=col).comment = Comment(
            f'Выгрузка запуска № {api["runId"]} (upd_decision: ead_final, el_final, price_final, income_final)', 'Big Data Fish')
    rng = lambda c: f"'Предложения'!${c}${first}:${c}${last}"  # noqa: E731

    # ------------------------------------------------------------------ Экономика
    we = wb.create_sheet('Экономика')
    style_header(we, 1, ['Показатель', 'Значение', 'Ед.', 'Как считается'], [52, 20, 12, 80])
    e = {}
    row = 2

    def calc(key, title, formula, fmt, unit, how, bold=False):
        nonlocal row
        we.cell(row=row, column=1, value=title).font = BOLD if bold else BLACK
        cell = we.cell(row=row, column=2, value=formula)
        cell.font = BOLD if bold else BLACK
        cell.number_format = fmt
        we.cell(row=row, column=3, value=unit).font = BLACK
        we.cell(row=row, column=4, value=how).font = BLACK
        e[key] = f'$B${row}'
        row += 1

    section(we, row, 'Предложения запуска при ставках сценария'); row += 1
    calc('offers', 'Предложений', f'=SUM({rng("Q")})', NUM, 'шт.', 'Предложения блока 5; выпадают те, где доход стал ниже минимального из-за ставки')
    calc('below', 'Выпало: доход ниже минимального', f'=COUNT({rng("A")})-{e["offers"]}', NUM, 'шт.', 'Только из-за новой ставки фондирования')
    calc('p_ead', 'Портфель: сумма финансирования, если согласятся все', f'=SUMPRODUCT({rng("Q")},{rng("A")})', RUB, '₽', 'Σ EAD')
    calc('p_income', 'Портфель: доход банка, если согласятся все', f'=SUMPRODUCT({rng("Q")},{rng("O")})', RUB, '₽', 'Σ доход')
    calc('deals', 'Ожидаемых сделок', f'=SUM({rng("R")})', NUM1, 'шт.', 'Σ TR')
    calc('x_ead', 'Ожидаемая сумма финансирования', f'=SUM({rng("S")})', RUB, '₽', 'Σ TR × EAD')
    section(we, row, 'Доход банка по запуску (ожидаемый, с учётом TR)'); row += 1
    calc('revenue', 'Процентный доход — цена для клиентов', f'=SUM({rng("T")})', RUB, '₽', 'Σ TR × цена; цена = min(фондирование + EL + опер. расходы + маржа, потолок)')
    calc('funding_sum', '− Фондирование', f'=SUM({rng("U")})', RUB, '₽', 'EAD × ставка × срок / 365')
    calc('el_sum', '− Ожидаемые потери (EL)', f'=SUM({rng("V")})', RUB, '₽', 'PD × срок / 365 × LGD × EAD — из запуска')
    calc('opex_sum', '− Операционные расходы', f'=SUM({rng("W")})', RUB, '₽', 'EAD × 1,5% × срок / 365')
    calc('income', 'Доход банка (итог)', f'=SUM({rng("X")})', RUB, '₽', 'Процентный доход − фондирование − EL − опер. расходы', True)
    section(we, row, 'За год'); row += 1
    calc('avg_term', 'Средний срок финансирования', f'=IF({e["p_ead"]}=0,0,SUM({rng("Y")})/{e["p_ead"]})', NUM1, 'дн.', 'Взвешенный по EAD')
    calc('turnover', 'Оборачиваемость дебиторки', f'=IF({e["avg_term"]}=0,0,{r["basis"]}/ROUND({e["avg_term"]},1))', '0.00', 'раз в год',
         '365 / средний срок: за год дебиторка обновляется, анализ находит новые УПД')
    calc('y_ead', 'Профинансировано за год', f'={e["x_ead"]}*{e["turnover"]}', RUB, '₽', 'Ожидаемая сумма × оборачиваемость')
    calc('y_income', 'Доход банка за год', f'={e["income"]}*{e["turnover"]}', RUB, '₽', 'Доход по запуску × оборачиваемость', True)
    calc('per_client', 'Доход банка за год на клиента', f'=IF({r["clients_run"]}=0,0,{e["y_income"]}/{r["clients_run"]})', RUB, '₽',
         'Основа масштабирования')
    section(we, row, 'Время менеджеров (в окупаемость не входит)'); row += 1
    calc('hours', 'Высвобождается часов в год (клиенты запуска)',
         f'={r["clients_run"]}*({r["manual"]}-{r["system"]})*{r["reviews"]}/60', NUM, 'ч', 'Клиенты × (200 − 20) мин × проработок в год')
    calc('fte', 'Ставок менеджеров', f'={e["hours"]}/{r["fte_hours"]}', NUM1, 'ставок', 'Часы / норма часов на ставку')
    calc('hours_rub', 'Стоимость этого времени', f'={e["hours"]}*{r["hour_cost"]}', RUB, '₽/год', 'Часы × час менеджера')

    row += 1
    section(we, row, 'Масштаб и окупаемость внедрения', 8); row += 1
    scale_head = row
    style_header(we, row, ['Масштаб', 'Клиентов', 'Финансирование в год, ₽', 'Доход банка в год, ₽', 'Сопровождение в год, ₽',
                           'Эффект в год, ₽', 'Окупаемость, мес.', 'Ставок менеджеров'])
    for col, width in zip('EFGH', (22, 18, 16, 16)):
        we.column_dimensions[col].width = max(we.column_dimensions[col].width or 0, width)
    row += 1
    for title, clients in (('Демо: клиенты запуска', r['clients_run']), ('«Ядро»: деньги застряли у покупателей', r['core']),
                           ('Весь сегмент: выручка 0,8–2 млрд ₽', r['segment'])):
        n = row
        cells = [title, f'={clients}', f'=IF({r["clients_run"]}=0,0,{e["y_ead"]}*B{n}/{r["clients_run"]})',
                 f'=IF({r["clients_run"]}=0,0,{e["y_income"]}*B{n}/{r["clients_run"]})',
                 f'={r["fixed_cost"]}+{r["per_client"]}*B{n}', f'=D{n}-E{n}',
                 f'=IF(F{n}<=0,"не окупается",{r["implementation"]}*12/F{n})',
                 f'=IF({r["clients_run"]}=0,0,{e["fte"]}*B{n}/{r["clients_run"]})']
        for col, value in enumerate(cells, 1):
            cell = we.cell(row=n, column=col, value=value)
            cell.font = GREEN if col == 2 else BLACK
            cell.number_format = {2: NUM, 7: NUM1, 8: NUM1}.get(col, RUB)
        row += 1
    we.cell(row=row, column=1, value='Окупаемость = внедрение / (эффект в год / 12). Эффект = доход банка − сопровождение. '
                                      'Поэтапный запуск — лист «Денежный поток».').font = Font(name=FONT, italic=True)
    we.freeze_panes = 'A2'
    core_row = scale_head + 2

    # ------------------------------------------------------------------ Денежный поток
    wc = wb.create_sheet('Денежный поток')
    style_header(wc, 1, ['Показатель, ₽', 'Год 1', 'Год 2', 'Год 3', 'Итого 3 года'], [44, 18, 18, 18, 18])
    pc = f"'Экономика'!{e['per_client']}"
    rows = [
        ('Клиентов', [f'={r["y1_clients"]}', f'=ROUND({r["core"]}*{r["y2_share"]},0)', f'=ROUND({r["core"]}*{r["y3_share"]},0)'], NUM),
        ('Месяцев работы', [f'={r["y1_months"]}', '=12', '=12'], NUM),
        ('Доход банка', [f'={pc}*B2*B3/12', f'={pc}*C2*C3/12', f'={pc}*D2*D3/12'], RUB),
        ('Внедрение', [f'=-{r["implementation"]}', '=0', '=0'], RUB),
        ('Сопровождение', [f'=-({r["fixed_cost"]}+{r["per_client"]}*B2)*B3/12', f'=-({r["fixed_cost"]}+{r["per_client"]}*C2)',
                           f'=-({r["fixed_cost"]}+{r["per_client"]}*D2)'], RUB),
        ('Денежный поток', ['=SUM(B4:B6)', '=SUM(C4:C6)', '=SUM(D4:D6)'], RUB),
        ('Накопленный поток', ['=B7', '=B8+C7', '=C8+D7'], RUB),
    ]
    for i, (title, formulas, fmt) in enumerate(rows, 2):
        wc.cell(row=i, column=1, value=title).font = BOLD if title in ('Денежный поток', 'Накопленный поток') else BLACK
        for col, formula in enumerate(formulas, 2):
            cell = wc.cell(row=i, column=col, value=formula)
            cell.font = GREEN if '!' in formula and title in ('Клиентов', 'Месяцев работы', 'Внедрение') else BLACK
            cell.number_format = fmt
        if title not in ('Клиентов', 'Месяцев работы', 'Накопленный поток'):
            total = wc.cell(row=i, column=5, value=f'=SUM(B{i}:D{i})')
            total.number_format = fmt
            total.font = BLACK
    wc.cell(row=10, column=1, value='Год окупаемости').font = BOLD
    cell = wc.cell(row=10, column=2, value='=IF(B8>=0,"год 1",IF(C8>=0,"год 2",IF(D8>=0,"год 3","позже 3 лет")))')
    cell.font = BOLD
    wc.cell(row=11, column=1, value='Год 1: первые месяцы — внедрение, затем пилот; доход пропорционален месяцам работы. '
                                    'Доход на клиента — лист «Экономика».').font = Font(name=FONT, italic=True)

    # ------------------------------------------------------------------ Сверка
    wv = wb.create_sheet('Сверка')
    style_header(wv, 1, ['Показатель при ставках запуска', 'Excel', 'Система (API)', 'Расхождение'], [52, 20, 20, 16])
    base = api['base']
    checks = [
        ('Предложений', f'=COUNT({rng("A")})', base['offers'], NUM),
        ('Портфель: сумма финансирования, ₽', f'=SUM({rng("A")})', base['portfolioEad'], RUB2),
        ('Портфель: доход банка, ₽ (по формуле, ставка запуска)', f'=SUM({rng("P")})', base['portfolioIncome'], RUB2),
        ('Портфель: доход банка, ₽ (сохранён в запуске)', f'=SUM({rng("I")})', base['portfolioIncome'], RUB2),
        ('Ожидаемых сделок (Σ TR)', f'=SUM({rng("F")})', base['expectedDeals'], '0.0000'),
        ('Ожидаемый доход банка, ₽', f'=SUMPRODUCT({rng("F")},{rng("P")})', base['income'], RUB2),
        ('Средний срок, дн.', f'=ROUND(SUMPRODUCT({rng("A")},{rng("C")})/SUM({rng("A")}),1)', base['avgTermDays'], NUM1),
    ]
    for i, (title, formula, value, fmt) in enumerate(checks, 2):
        wv.cell(row=i, column=1, value=title).font = BLACK
        for col, v, font in ((2, formula, BLACK), (3, value, BLUE), (4, f'=B{i}-C{i}', BLACK)):
            cell = wv.cell(row=i, column=col, value=v)
            cell.font = font
            cell.number_format = fmt
    wv.cell(row=len(checks) + 3, column=1,
            value=f'Система — ответ GET /runs/{api["runId"]}/economics при ставках запуска (выгрузка EconomicEffectManualTest). '
                  'Расхождение в копейках возможно из-за округления двоичных чисел Excel.').font = Font(name=FONT, italic=True)

    for sheet in wb.worksheets:
        for line in sheet.iter_rows():
            for cell in line:
                if cell.font is None or cell.font.name != FONT:
                    cell.font = Font(name=FONT, bold=cell.font.bold if cell.font else False,
                                     color=cell.font.color if cell.font else None)
    wb.move_sheet('Экономика', offset=-1)
    wb.move_sheet('Денежный поток', offset=-1)
    # формулы без сохранённых значений: Excel пересчитает всё при открытии
    wb.calculation.fullCalcOnLoad = True
    target.parent.mkdir(parents=True, exist_ok=True)
    wb.save(target)
    print(f'{target}: {len(offers)} предложений, ядро — строка {core_row} листа «Экономика»')


if __name__ == '__main__':
    build(Path(sys.argv[1]), Path(sys.argv[2]))
