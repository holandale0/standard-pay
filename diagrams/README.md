# Diagramas

Quatro diagramas do StandardPay, gerados com [archify](https://github.com/tt-a1i/archify).

| Diagrama | Tipo | Responde |
|---|---|---|
| [arquitetura](arquitetura.html) | `architecture` | Quem fala com quem: lojista, pagador, componentes e banco |
| [checkout](checkout.html) | `sequence` | Uma integração completa, da cobrança ao webhook |
| [pagamento](pagamento.html) | `lifecycle` | Os estados de uma cobrança, e o que não muda o estado |
| [webhook](webhook.html) | `lifecycle` | A vida de uma entrega: fila, reenvios, desistência |

Cada um existe em dois formatos:

- **`.html`** — o artefato bom. Interativo: visões guiadas com explicação, tema
  claro/escuro, exportação e, em cada componente, **links para o trecho do código
  que o implementa**. **Abra este.** O GitHub não o renderiza: baixe o arquivo, ou
  clone o repositório e abra no navegador.
- **`.svg`** — o retrato estático, que é o que aparece no [README](../README.md). O
  GitHub renderiza SVG, e é assim que o projeto tem figura na página inicial.

## Ligados ao código

Os diagramas não são desenhos soltos. Cada componente, participante e estado cita
o arquivo e as linhas que o implementam, num commit fixo (`meta.repository`). O
archify confere essas citações contra os bytes daquele commit: se uma linha citada
não existir, a geração falha.

Ao mudar o código que um diagrama descreve, atualize as citações e a `revision`
para o commit novo.

## Regerar

Os `.json` são a fonte; o resto é derivado.

```bash
# 1. o archify (é um skill de agente; aqui usamos só a CLI)
git clone --depth 1 https://github.com/tt-a1i/archify.git /tmp/archify

# 2. gere cada diagrama, a partir da raiz do repositório
#    valida no perfil showcase, entrega o HTML e confere num navegador real
node /tmp/archify/archify/bin/archify.mjs finalize architecture \
  diagrams/arquitetura.architecture.json diagrams/arquitetura.html \
  --repo-root . --quality showcase --out-dir /tmp/archify-evidencia/arquitetura
#    (o mesmo para sequence/checkout e lifecycle/pagamento e lifecycle/webhook)

# 3. extraia os SVG estáticos para o README
python scripts/extrai_svg_dos_diagramas.py
```

O `--out-dir` manda os recibos de verificação para fora do repositório. Os que
caírem em `diagrams/` são ignorados pelo `.gitignore`.

O perfil é **`showcase`**, o mais exigente: reprova rótulo em cima de rota, rota
atravessando nó e moldura que engole componente de fora. Dois dos quatro não
passaram de primeira — o despachante, posto embaixo do banco, esticava a moldura
do StandardPay até englobar o PostgreSQL, o que afirmaria uma coisa falsa sobre
onde o banco roda.

## Idioma

Os diagramas usam `meta.locale: "pt-BR"` e, em `meta.translations`, o catálogo
de português que o archify traz em `examples/locales/pt.json` — é o que deixa a
legenda e os botões do visualizador em português. As poucas mensagens que o
catálogo não cobre aparecem em inglês.

## O script de SVG

O `extrai_svg_dos_diagramas.py` veio do
[3layers-flightmonitor](https://github.com/holandale0/3layers-flightmonitor),
com uma correção: o archify 3 passou a pôr `padding` e `min-height` no `<body>`
da página, e o script, que leva as regras do `body` para o `:root` do SVG, levava
esse espaçamento junto. O desenho descia dentro do próprio `viewBox` e a última
linha — a legenda — saía cortada. Agora, nas regras que viram `:root`, fica o que
pinta (cor, fonte, variáveis) e sai o que posiciona a página.
