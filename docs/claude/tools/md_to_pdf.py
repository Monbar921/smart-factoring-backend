"""Markdown из docs/ → HTML для печати → PDF через Microsoft Word (Windows, COM).

    python md_to_pdf.py docs/security.md docs/Smart_Factoring_Security.pdf "Smart Factoring — данные, безопасность и комплаенс"

Поддерживается то, что есть в документах проекта: заголовки, абзацы, списки, таблицы, блоки кода (схемы),
`код`, **жирный**, ссылки (в PDF — текстом). Без Word сохраняется только HTML рядом с PDF.
"""
import html
import re
import subprocess
import sys
from pathlib import Path

CSS = """
@page { size: A4; margin: 1.6cm 1.5cm; }
body { font-family: Arial, sans-serif; font-size: 10pt; line-height: 1.35; color: #1a1a1a; }
h1 { font-size: 18pt; color: #0b3d6e; border-bottom: 2px solid #0b3d6e; padding-bottom: 4pt; }
h2 { font-size: 13.5pt; color: #0b3d6e; margin-top: 16pt; }
h3 { font-size: 11.5pt; color: #0b3d6e; }
table { border-collapse: collapse; width: 100%; margin: 6pt 0; font-size: 8.5pt; }
th { background: #0b3d6e; color: #fff; text-align: left; padding: 3pt 5pt; }
td { border: 1px solid #c8d3df; padding: 3pt 5pt; vertical-align: top; }
tr:nth-child(even) td { background: #f3f6f9; }
code { font-family: Consolas, monospace; font-size: 8.5pt; background: #eef2f6; }
pre { font-family: Consolas, monospace; font-size: 7.2pt; line-height: 1.15; background: #f3f6f9;
      border: 1px solid #c8d3df; padding: 6pt; white-space: pre; }
.footer { margin-top: 18pt; font-size: 8pt; color: #666; }
"""


def inline(text: str) -> str:
    text = html.escape(text, quote=False)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'<b>\1</b>', text)
    return re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)


def cells(line: str) -> list[str]:
    # \| внутри ячейки - литерал
    parts = re.split(r'(?<!\\)\|', line.strip().strip('|'))
    return [p.strip().replace('\\|', '|') for p in parts]


def convert(markdown: str) -> str:
    out, lines, i = [], markdown.splitlines(), 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('```'):
            block = []
            i += 1
            while i < len(lines) and not lines[i].startswith('```'):
                block.append(lines[i])
                i += 1
            out.append('<pre>' + html.escape('\n'.join(block)) + '</pre>')
            i += 1
            continue
        heading = re.match(r'^(#{1,3}) (.*)$', line)
        if heading:
            level = len(heading.group(1))
            out.append(f'<h{level}>{inline(heading.group(2))}</h{level}>')
            i += 1
            continue
        if line.startswith('|') and i + 1 < len(lines) and re.match(r'^\|[\s|:-]+\|?$', lines[i + 1]):
            head = cells(line)
            rows = []
            i += 2
            while i < len(lines) and lines[i].startswith('|'):
                rows.append(cells(lines[i]))
                i += 1
            out.append('<table><tr>' + ''.join(f'<th>{inline(c)}</th>' for c in head) + '</tr>'
                       + ''.join('<tr>' + ''.join(f'<td>{inline(c)}</td>' for c in row) + '</tr>' for row in rows)
                       + '</table>')
            continue
        item = re.match(r'^(\s*)(-|\d+\.) (.*)$', line)
        if item:
            tag = 'ol' if item.group(2)[0].isdigit() else 'ul'
            items = []
            while i < len(lines):
                m = re.match(r'^(\s*)(-|\d+\.) (.*)$', lines[i])
                if m and not m.group(1):
                    items.append(m.group(3))
                elif lines[i].startswith('  ') and lines[i].strip() and items:
                    items[-1] += ' ' + lines[i].strip()  # продолжение пункта или вложенный пункт - одной строкой
                else:
                    break
                i += 1
            out.append(f'<{tag}>' + ''.join(f'<li>{inline(t)}</li>' for t in items) + f'</{tag}>')
            continue
        if not line.strip():
            i += 1
            continue
        paragraph = [line.strip()]
        i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r'^(#|\||```|\s*(-|\d+\.) )', lines[i]):
            paragraph.append(lines[i].strip())
            i += 1
        out.append('<p>' + inline(' '.join(paragraph)) + '</p>')
    return '\n'.join(out)


def main(source: Path, target: Path, title: str) -> None:
    body = convert(source.read_text(encoding='utf-8'))
    page = (f'<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>{html.escape(title)}</title>'
            f'<style>{CSS}</style></head><body>{body}'
            f'<p class="footer">Команда Big Data Fish · хакатон ВТБ «Финтех без границ» · источник: '
            f'{html.escape(source.as_posix())} в smart-factoring-backend</p></body></html>')
    html_path = target.with_suffix('.html')
    html_path.write_text(page, encoding='utf-8')
    # Word: открыть HTML и сохранить как PDF (wdFormatPDF = 17)
    script = (f"$w = New-Object -ComObject Word.Application; $w.Visible = $false; "
              f"try {{ $d = $w.Documents.Open('{html_path.resolve()}'); $d.SaveAs2('{target.resolve()}', 17); $d.Close($false) }} "
              f"finally {{ $w.Quit() }}")
    result = subprocess.run(['powershell', '-NoProfile', '-Command', script], capture_output=True, text=True)
    if result.returncode != 0 or not target.exists():
        print('Word не сохранил PDF, остался HTML:', html_path, result.stderr.strip()[:300])
        sys.exit(1)
    html_path.unlink()
    print(target)


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3] if len(sys.argv) > 3 else Path(sys.argv[1]).stem)
