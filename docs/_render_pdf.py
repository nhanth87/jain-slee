#!/usr/bin/env python3
"""Convert the 3 micro-jainslee-compact docs from Markdown to PDF.

WeasyPrint renders Markdown via Python-Markdown. Mermaid blocks are converted
to a small placeholder SVG so the PDF is still self-contained (no external
network call to mermaid.ink).
"""
import sys
from pathlib import Path
from markdown import markdown
from weasyprint import HTML, CSS

DOCS = Path(__file__).resolve().parent
TARGETS = [
    ("micro-jainslee-compact-vs-mobicents.md",     "micro-jainslee-compact-vs-mobicents.pdf",     "micro-jainslee vs Mobicents SLEE — Compact comparison",                                       "EN"),
    ("micro-jainslee-compact-vs-mobicents.vi.md", "micro-jainslee-compact-vs-mobicents.vi.pdf", "micro-jainslee so với Mobicents SLEE — So sánh compact và walkthrough line-by-line",          "VI"),
    ("micro-jainslee-compact-vs-mobicents.am.md", "micro-jainslee-compact-vs-mobicents.am.pdf", "micro-jainslee ከ Mobicents SLEE ጋር — ንጽጽር እና line-by-line walkthrough",                     "AM"),
]

# CSS with: A4, Mermaid boxes rendered with a coloured border, code-block style
CSS_TEXT = """
@page { size: A4; margin: 18mm 16mm 20mm 16mm; @bottom-right { content: counter(page) " / " counter(pages); font-size: 9pt; color: #888; } }
body { font-family: 'DejaVu Sans', 'Noto Sans', 'Liberation Sans', sans-serif; font-size: 10pt; line-height: 1.45; color: #1a1a1a; }
h1 { color: #0d6efd; font-size: 22pt; border-bottom: 2px solid #0d6efd; padding-bottom: 4pt; margin-top: 18pt; }
h2 { color: #198754; font-size: 16pt; border-bottom: 1px solid #198754; padding-bottom: 2pt; margin-top: 14pt; }
h3 { color: #b8860b; font-size: 13pt; margin-top: 10pt; }
h4 { color: #444; font-size: 11pt; margin-top: 8pt; }
blockquote { border-left: 4px solid #0d6efd; background: #f0f6ff; padding: 6pt 10pt; margin: 8pt 0; font-style: italic; }
code { font-family: 'DejaVu Sans Mono', 'Liberation Mono', monospace; font-size: 9pt; background: #f5f5f5; padding: 1pt 3pt; border-radius: 2pt; }
pre { background: #f5f5f5; padding: 8pt; border-radius: 4pt; overflow-x: auto; border: 1px solid #ddd; font-size: 8.5pt; line-height: 1.3; }
pre code { background: none; padding: 0; }
table { border-collapse: collapse; width: 100%; margin: 8pt 0; }
th, td { border: 1px solid #ddd; padding: 4pt 6pt; text-align: left; vertical-align: top; }
th { background: #f0f0f0; font-weight: bold; }
tr:nth-child(even) { background: #fafafa; }
ul, ol { margin: 4pt 0 4pt 18pt; }
li { margin: 2pt 0; }
hr { border: 0; border-top: 1px solid #ddd; margin: 12pt 0; }
.mermaid {
    border: 2px dashed #6c757d;
    background: repeating-linear-gradient(45deg, #f8f9fa, #f8f9fa 8pt, #ffffff 8pt, #ffffff 16pt);
    padding: 12pt;
    margin: 10pt 0;
    font-family: 'DejaVu Sans Mono', monospace;
    white-space: pre;
    font-size: 8pt;
    color: #555;
}
.mermaid::before {
    content: "📊 Mermaid diagram (see HTML or GitHub for rendering):";
    display: block;
    font-family: 'DejaVu Sans', sans-serif;
    font-weight: bold;
    font-style: italic;
    color: #0d6efd;
    margin-bottom: 6pt;
}
"""

def render_mermaid_placeholder(html: str) -> str:
    """Wrap every <pre><code class="language-mermaid"> in a <div class="mermaid">."""
    return html.replace(
        '<pre><code class="language-mermaid">',
        '</code></pre><div class="mermaid">',
    )

def main():
    for src_name, out_name, title, lang in TARGETS:
        src = DOCS / src_name
        if not src.exists():
            print(f"SKIP {src_name}: not found", file=sys.stderr)
            continue
        out = DOCS / out_name
        text = src.read_text(encoding="utf-8")
        html_body = markdown(
            text,
            extensions=["fenced_code", "tables", "toc", "sane_lists", "attr_list"],
        )
        html_body = render_mermaid_placeholder(html_body)
        html_doc = (
            f'<!DOCTYPE html><html lang="{lang.lower()}"><head><meta charset="utf-8">'
            f'<title>{title}</title></head><body>{html_body}</body></html>'
        )
        HTML(string=html_doc, base_url=str(DOCS)).write_pdf(
            target=str(out),
            stylesheets=[CSS(string=CSS_TEXT)],
        )
        print(f"OK  {out_name}  ({out.stat().st_size/1024:.1f} KB)")

if __name__ == "__main__":
    main()
