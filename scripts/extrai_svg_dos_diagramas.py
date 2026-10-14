"""Extrai o SVG do HTML do archify para um `.svg` autossuficiente.

# Por que isto existe

O archify entrega **HTML interativo**, e o GitHub nao renderiza HTML do
repositorio dentro do README. Renderiza SVG. Este script produz o retrato
estatico do diagrama, para o README ter figura; o HTML continua sendo o
artefato bom, com exploracao, tema e narracao guiada.

# As tres coisas que precisam ser resolvidas

1. **O CSS mora na pagina, nao no SVG.** O SVG usa `class=`, e as regras estao
   no `<style>` do documento. Elas vem junto, embutidas — mas so a parte que as
   classes presentes alcancam: a pagina tem ~177 KB de estilo, quase tudo de
   controles do visualizador.

2. **Atributo booleano sem valor quebra o XML.** `data-detail-anchor` (sem
   `="..."`) e valido em HTML e invalido em XML, e o GitHub renderiza SVG com
   parser XML. Sem normalizar, a imagem simplesmente nao aparece — sem erro
   visivel, so um espaco em branco.

3. **O SVG nao tem fundo.** Quem pinta o fundo e o `<body>` da pagina. Sem um
   retangulo proprio, o diagrama sai transparente e fica ilegivel sobre metade
   dos temas possiveis.

# Uso

    python scripts/extrai_svg_dos_diagramas.py

Gera `diagrams/*.svg` a partir de `diagrams/*.html`. Os HTML sao gerados pelo
archify — ver `diagrams/README.md`.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

RAIZ = pathlib.Path(__file__).resolve().parents[1]
DIAGRAMAS = RAIZ / "diagrams"

# Seletores que valem dentro de um SVG mesmo sem classe.
ELEMENTOS_SVG = {"svg", "path", "text", "rect", "circle", "line", "g", "tspan",
                 "polygon", "polyline", "ellipse", "marker", "defs", "*", ":root"}

TAG = re.compile(r"<[A-Za-z][^>]*>")

# Um atributo: nome, opcionalmente seguido de ="valor". Percorrer a tag por
# ATRIBUTO, e nao por espaco, e o que evita corromper o conteudo entre aspas —
# a primeira versao deste script transformava o `L` de `d="M 40 0 L 0 0"` num
# atributo, e o XML quebrava num lugar que nao tinha nada a ver com o problema.
ATRIBUTO = re.compile(r'([A-Za-z_:][A-Za-z0-9_:.-]*)(\s*=\s*"[^"]*")?')


def _podar_css(css, classes):
    """So as regras que as classes presentes no SVG realmente alcancam."""
    mantidas = []
    for m in re.finditer(r"([^{}]+)\{([^{}]*)\}", css, re.S):
        seletor, corpo = m.group(1).strip(), m.group(2).strip()
        if seletor.startswith("@") or not corpo:
            # @media e @keyframes dependem de estado que uma imagem nao tem.
            continue

        usadas = set(re.findall(r"\.([A-Za-z0-9_-]+)", seletor))
        primeiro = re.match(r"^[a-z*:.-]+", seletor)
        alvo = primeiro.group(0) if primeiro else ""

        if (usadas & classes) or seletor in ELEMENTOS_SVG or alvo in ELEMENTOS_SVG or "--" in corpo:
            mantidas.append(seletor + "{" + corpo + "}")
    return "\n".join(mantidas)


# Propriedades que posicionam a PAGINA, nao o desenho. O archify 3 passou a por
# `padding` e `min-height` no <body>; levadas para o :root do SVG, elas empurram
# o desenho para baixo dentro do proprio viewBox e cortam a ultima linha — que e
# justamente a legenda. Ninguem percebe olhando o HTML, so a imagem no README.
LAYOUT_DE_PAGINA = re.compile(
    r"(?<![-\w])(?:padding|margin|min-height|max-height|height|min-width|max-width|width|"
    r"display|overflow)(?:-[a-z]+)*\s*:[^;{}]*;?")


def _sem_layout_de_pagina(css):
    """Nas regras que viraram :root, fica o que pinta (cor, fonte, variavel) e sai o layout."""
    def limpar(regra):
        seletor, corpo = regra.group(1), regra.group(2)
        if ":root" not in seletor:
            return regra.group(0)
        return seletor + "{" + LAYOUT_DE_PAGINA.sub("", corpo) + "}"
    return re.sub(r"([^{}]+)\{([^{}]*)\}", limpar, css)


def _normalizar_tag(tag):
    texto = tag.group(0)
    fecha = "/>" if texto.endswith("/>") else ">"
    corpo = texto[1:-len(fecha)]

    nome = re.match(r"[A-Za-z][A-Za-z0-9_:.-]*", corpo)
    partes = [nome.group(0)]
    pos = nome.end()

    while pos < len(corpo):
        m = ATRIBUTO.search(corpo, pos)
        if not m:
            break
        partes.append(m.group(1) + (m.group(2) or '=""'))
        pos = m.end()

    return "<" + " ".join(partes) + fecha


def extrair(nome):
    html = (DIAGRAMAS / (nome + ".html")).read_text(encoding="utf-8")

    achado = re.search(r"<svg\b.*?</svg>", html, re.S)
    if not achado:
        sys.exit(nome + ": nenhum <svg> encontrado no HTML")
    svg = achado.group(0)

    css_todo = "\n".join(
        m.group(1) for m in re.finditer(r"<style[^>]*>(.*?)</style>", html, re.S))

    classes = set()
    for atributo in re.findall(r'class="([^"]+)"', svg):
        classes.update(atributo.split())

    css = _podar_css(css_todo, classes)
    # As variaveis de cor moram em :root/body do documento. Numa imagem SVG nao
    # existe <body>, entao :root passa a ser o proprio <svg> — e e la que elas
    # precisam estar declaradas.
    css = re.sub(r"(^|[,\s])body(?=[\s,{])", r"\g<1>:root", css)
    css = _sem_layout_de_pagina(css)

    svg = TAG.sub(_normalizar_tag, svg)
    if not svg.startswith("<svg xmlns"):
        svg = svg.replace("<svg", '<svg xmlns="http://www.w3.org/2000/svg"', 1)

    caixa = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg)
    fundo = ('<rect x="0" y="0" width="%s" height="%s" fill="var(--bg)"/>'
             % (caixa.group(1), caixa.group(2)))

    dentro = svg.index(">") + 1
    completo = (svg[:dentro]
                + "\n<style><![CDATA[\n" + css + "\n]]></style>\n"
                + fundo + "\n"
                + svg[dentro:])

    # XML invalido nao renderiza em lugar nenhum. Falhar aqui e muito melhor do
    # que descobrir no README ja publicado, onde o sintoma e um espaco vazio.
    ET.fromstring(completo)

    (DIAGRAMAS / (nome + ".svg")).write_text(completo, encoding="utf-8")
    print("%-12s %4d KB  %2d classes" % (nome + ".svg", len(completo) // 1024, len(classes)))


if __name__ == "__main__":
    for arquivo in sorted(DIAGRAMAS.glob("*.html")):
        extrair(arquivo.stem)
