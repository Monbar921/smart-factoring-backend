"""ZIP-архивы для импорта данных ручной загрузки через UI («Импорт данных ручной загрузки»).

    python -I docs/claude/tools/build_manual_upload_zips.py [каталог, по умолчанию manual-upload]

fixture-v1.zip  - контрольные случаи К-1…К-18 (app/src/test/resources/manual-upload/fixture-v1, с events.csv);
perf-volume.zip - объём демо: 100 клиентов, 300 покупателей, 1 500 открытых УПД (gen_perf_dataset.py).
Внутри архива - папка выгрузки с manifest.json.
"""

import pathlib
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "manual-upload")
out.mkdir(parents=True, exist_ok=True)


def pack(src: pathlib.Path, name: str) -> None:
    folder = name.removesuffix(".zip")
    with zipfile.ZipFile(out / name, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(src.iterdir()):
            if file.suffix in (".csv", ".json"):
                archive.write(file, f"{folder}/{file.name}")
    print(out / name, (out / name).stat().st_size, "байт")


pack(ROOT / "app/src/test/resources/manual-upload/fixture-v1", "fixture-v1.zip")
with tempfile.TemporaryDirectory() as tmp:
    subprocess.run([sys.executable, "-I", str(ROOT / "docs/claude/tools/gen_perf_dataset.py"), tmp], check=True)
    pack(pathlib.Path(tmp), "perf-volume.zip")
