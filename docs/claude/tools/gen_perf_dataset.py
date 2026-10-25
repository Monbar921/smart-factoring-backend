"""Синтетический набор объёма демо (ДД-4) для проверки НФР-1.1: формат docs/claude/module-0/demo-dataset-format.md.

python -I gen_perf_dataset.py <папка> [клиентов=100] [покупателей=300] [открытых УПД=1500]

Не замена генератору коллеги: равномерные случайные данные, только чтобы проверить время прогона.
"""

import json
import os
import random
import sys
from datetime import date, timedelta

out = sys.argv[1]
clients_n = int(sys.argv[2]) if len(sys.argv) > 2 else 100
buyers_n = int(sys.argv[3]) if len(sys.argv) > 3 else 300
open_n = int(sys.argv[4]) if len(sys.argv) > 4 else 1500
rnd = random.Random(42)
data_date = date(2026, 10, 24)
os.makedirs(out, exist_ok=True)


def write(name, header, rows):
    with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as f:
        f.write(";".join(header) + "\n")
        for row in rows:
            f.write(";".join("" if v is None else str(v) for v in row) + "\n")


industries = ["CONSTRUCTION", "PROFESSIONAL", "MANUFACTURING", "TRADE"]
clients = [f"77{i:08d}" for i in range(1, clients_n + 1)]
buyers = [f"78{i:08d}" for i in range(1, buyers_n + 1)]
write("managers.csv", ["code", "full_name"], [["M1", "Демо-менеджер"]])
write("clients.csv", ["inn", "name", "industry", "abs_liquidity", "is_core", "manager_code", "edo_consent", "factoring_client"],
      [[inn, f"Клиент {i}", rnd.choice(industries), round(rnd.uniform(0.02, 0.6), 3), "false", "M1", "true", "false"]
       for i, inn in enumerate(clients, 1)])
write("buyers.csv", ["inn", "name", "okved_section", "registration_date", "data_date"],
      [[inn, f"Покупатель {i}", rnd.choice("CFGHM"), "2012-01-01", "2026-10-20"] for i, inn in enumerate(buyers, 1)])
fin = []
for inn in buyers:
    for year in (2024, 2025):
        revenue = rnd.uniform(2e8, 1e10)
        fin.append([inn, year, "false", revenue * 0.7, revenue * 0.4, revenue * 0.15, revenue * rnd.uniform(0.005, 0.08),
                    revenue * rnd.uniform(-0.05, 0.4), revenue * 0.1, revenue * 0.3, revenue * 0.08, revenue * 0.12, revenue,
                    revenue * 0.85, revenue * 0.08, revenue * 0.01, revenue * rnd.uniform(-0.05, 0.08)])
write("buyer_financials.csv", ["inn", "year", "simplified", "assets_total", "current_assets", "receivables", "cash", "equity",
                               "long_term_borrowings", "short_term_liabilities", "short_term_borrowings", "payables", "revenue",
                               "cost_of_sales", "sales_profit", "interest_payable", "net_profit"], fin)
pairs = {}
contracts, upds, payments = [], [], []
for client in clients:
    for buyer in rnd.sample(buyers, 4):
        number = f"Д-{len(pairs) + 1}"
        deferral = rnd.choice([30, 45, 60, 90])
        pairs[(client, buyer)] = (number, deferral)
        contracts.append([client, buyer, number, "2026-01-01", deferral])
pair_list = list(pairs)
for i in range(open_n):
    client, buyer = rnd.choice(pair_list)
    number, deferral = pairs[(client, buyer)]
    upd_date = data_date - timedelta(days=rnd.randint(0, deferral + 20))
    upds.append([client, buyer, number, f"O-{i}", upd_date, round(rnd.uniform(3e5, 1e7), 2)])
for i in range(open_n):  # оплаченная история
    client, buyer = rnd.choice(pair_list)
    number, deferral = pairs[(client, buyer)]
    upd_date = data_date - timedelta(days=rnd.randint(120, 300))
    amount = round(rnd.uniform(3e5, 5e6), 2)
    upds.append([client, buyer, number, f"H-{i}", upd_date, amount])
    paid = min(upd_date + timedelta(days=deferral + rnd.randint(-5, 25)), data_date - timedelta(days=1))
    payments.append([f"P-{i}", client, buyer, amount, paid, f"Оплата по УПД № H-{i}"])
payments.append(["P-last", clients[0], buyers[0], 1000, data_date, "оплата"])
write("contracts.csv", ["client_inn", "buyer_inn", "number", "contract_date", "deferral_days"], contracts)
write("upds.csv", ["client_inn", "buyer_inn", "contract_number", "number", "upd_date", "amount"], upds)
write("payments.csv", ["source_id", "client_inn", "payer_inn", "amount", "payment_date", "purpose"], payments)
last = max([u[4] for u in upds] + [p[4] for p in payments])
with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump({"format_version": "1", "generator": "gen_perf_dataset.py", "seed": 42, "data_date": str(last),
               "snapshot_date": "2026-10-20"}, f)
print(out, len(upds), "upds", len(payments), "payments")
