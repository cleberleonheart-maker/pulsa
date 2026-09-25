# Baselines de regressão visual

`tree.txt` é a árvore XML compilada (via `aapt2 dump xmltree`) dos 146 recursos
de drawable/layout/color do app, com as referências de recurso já resolvidas
para valor (`@color/x` vira `#aarrggbb`, qualquer outra vira `@tipo/nome`).
`files.list` é a lista de recursos, na ordem em que foram gerados o `tree.txt`.

| baseline | quando | serve para |
| --- | --- | --- |
| `t01-original/` | antes do T0.1 | provar que a tokenização não mudou nada |
| `t02-aurora/` | depois do T0.2 | referência de revisão para T0.3 e F1 |

Uso:

```bash
./scripts/verificar-visual.sh gate app/build/outputs/apk/debug/app-debug.apk scripts/baseline/t02-aurora
./scripts/verificar-visual.sh dump app/build/outputs/apk/debug/app-debug.apk scripts/baseline/t03-novo
```

O gate **falha** se a árvore compilada mudar — inclusive quando a mudança é
intencional. Nesse caso rode o `dump` para o novo diretório, confira o diff com
`scripts/resumir-dif.py` e só então aceite a nova baseline.
