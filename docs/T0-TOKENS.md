# T0.1 - Catalogo de tokens

Gerado por `scripts/tokenizar.py`. Todo `@color/x` neste catalogo tem o
mesmo valor ARGB do hex que substituiu, entao a arvore compilada nao muda.

- 64 arquivos XML alterados
- 4 icones legados mantidos com hex literal (PNG gerado em build): `drawable/avatar_masculino.xml`, `drawable/ic_launcher.xml`, `drawable/ic_splash.xml`, `drawable/virgin_avatar.xml`
- 111 cores distintas encontradas
- 105 tokens novos (o resto ja existia em `values/colors.xml`)


## Como verificar

```bash
python3 scripts/verificar-tokenizacao.py            # prova no fonte: cor e estrutura
./gradlew assembleDebug                              # build
./scripts/verificar-visual.sh gate \
    app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/visual-base
```

O gate compara a arvore compilada com a baseline do APK original. O
normalizador resolve `@0x7f...` para o valor da cor e para `tipo/nome`,
porque ids de recurso mudam a cada token novo criado.

| hex original | token | origem |
| --- | --- | --- |
| `#C91F36` | `@color/accent_crimson` | novo |
| `#7000FFFF` | `@color/accent_cyan_a70` | novo |
| `#50E342F5` | `@color/accent_fuchsia_a50` | novo |
| `#D9F6FF` | `@color/accent_ice` | novo |
| `#E6FFF0` | `@color/accent_mint` | novo |
| `#B3FF3D93` | `@color/accent_pink_aB3` | novo |
| `#8FD8FF` | `@color/accent_sky` | novo |
| `#A78BFA` | `@color/aurora_violet` | reaproveitado |
| `#FF000000` | `@color/black` | reaproveitado |
| `#00000000` | `@color/black_a00` | novo |
| `#B3000000` | `@color/black_aB3` | novo |
| `#1B2E66` | `@color/blue_deep` | novo |
| `#0E1B4A` | `@color/blue_night` | novo |
| `#6FA81C` | `@color/chartreuse` | novo |
| `#1B1030` | `@color/ink_base` | novo |
| `#150529` | `@color/ink_black` | novo |
| `#FF6A7A` | `@color/neon_coral` | novo |
| `#FFFF6A7A` | `@color/neon_coral` | reaproveitado |
| `#00FF6A7A` | `@color/neon_coral_a00` | novo |
| `#22FF6A7A` | `@color/neon_coral_a22` | novo |
| `#40FF6A7A` | `@color/neon_coral_a40` | novo |
| `#59FF6A7A` | `@color/neon_coral_a59` | novo |
| `#66FF6A7A` | `@color/neon_coral_a66` | novo |
| `#8CFF6A7A` | `@color/neon_coral_a8C` | novo |
| `#99FF6A7A` | `@color/neon_coral_a99` | novo |
| `#B3FF6A7A` | `@color/neon_coral_aB3` | novo |
| `#B6FF2E` | `@color/neon_green` | novo |
| `#FFB6FF2E` | `@color/neon_green` | reaproveitado |
| `#00B6FF2E` | `@color/neon_green_a00` | novo |
| `#08B6FF2E` | `@color/neon_green_a08` | novo |
| `#0EB6FF2E` | `@color/neon_green_a0E` | novo |
| `#12B6FF2E` | `@color/neon_green_a12` | novo |
| `#14B6FF2E` | `@color/neon_green_a14` | novo |
| `#16B6FF2E` | `@color/neon_green_a16` | novo |
| `#1CB6FF2E` | `@color/neon_green_a1C` | novo |
| `#1FB6FF2E` | `@color/neon_green_a1F` | novo |
| `#22B6FF2E` | `@color/neon_green_a22` | novo |
| `#26B6FF2E` | `@color/neon_green_a26` | novo |
| `#28B6FF2E` | `@color/neon_green_a28` | novo |
| `#2AB6FF2E` | `@color/neon_green_a2A` | novo |
| `#2EB6FF2E` | `@color/neon_green_a2E` | novo |
| `#3DB6FF2E` | `@color/neon_green_a3D` | novo |
| `#40B6FF2E` | `@color/neon_green_a40` | novo |
| `#46B6FF2E` | `@color/neon_green_a46` | novo |
| `#4DB6FF2E` | `@color/neon_green_a4D` | novo |
| `#59B6FF2E` | `@color/neon_green_a59` | novo |
| `#62B6FF2E` | `@color/neon_green_a62` | novo |
| `#66B6FF2E` | `@color/neon_green_a66` | novo |
| `#73B6FF2E` | `@color/neon_green_a73` | novo |
| `#80B6FF2E` | `@color/neon_green_a80` | novo |
| `#8AB6FF2E` | `@color/neon_green_a8A` | novo |
| `#99B6FF2E` | `@color/neon_green_a99` | novo |
| `#AAB6FF2E` | `@color/neon_green_aAA` | novo |
| `#B3B6FF2E` | `@color/neon_green_aB3` | novo |
| `#CCB6FF2E` | `@color/neon_green_aCC` | novo |
| `#A8E62A` | `@color/neon_lime` | novo |
| `#FFA8E62A` | `@color/neon_lime` | reaproveitado |
| `#00A8E62A` | `@color/neon_lime_a00` | novo |
| `#0FA8E62A` | `@color/neon_lime_a0F` | novo |
| `#1EA8E62A` | `@color/neon_lime_a1E` | novo |
| `#22A8E62A` | `@color/neon_lime_a22` | novo |
| `#26A8E62A` | `@color/neon_lime_a26` | novo |
| `#30A8E62A` | `@color/neon_lime_a30` | novo |
| `#33A8E62A` | `@color/neon_lime_a33` | novo |
| `#55A8E62A` | `@color/neon_lime_a55` | novo |
| `#59A8E62A` | `@color/neon_lime_a59` | novo |
| `#8AA8E62A` | `@color/neon_lime_a8A` | novo |
| `#FF2E4D` | `@color/neon_red` | novo |
| `#00FF2E4D` | `@color/neon_red_a00` | novo |
| `#10FF2E4D` | `@color/neon_red_a10` | novo |
| `#1FFF2E4D` | `@color/neon_red_a1F` | novo |
| `#20FF2E4D` | `@color/neon_red_a20` | novo |
| `#24FF2E4D` | `@color/neon_red_a24` | novo |
| `#26FF2E4D` | `@color/neon_red_a26` | novo |
| `#2EFF2E4D` | `@color/neon_red_a2E` | novo |
| `#2FFF2E4D` | `@color/neon_red_a2F` | novo |
| `#30FF2E4D` | `@color/neon_red_a30` | novo |
| `#33FF2E4D` | `@color/neon_red_a33` | novo |
| `#59FF2E4D` | `@color/neon_red_a59` | novo |
| `#66FF2E4D` | `@color/neon_red_a66` | novo |
| `#AAFF2E4D` | `@color/neon_red_aAA` | novo |
| `#B3FF2E4D` | `@color/neon_red_aB3` | novo |
| `#CCFF2E4D` | `@color/neon_red_aCC` | novo |
| `#11291B` | `@color/radar_art` | novo |
| `#06100B` | `@color/radar_bg` | novo |
| `#0A2112` | `@color/radar_deep` | novo |
| `#04140A` | `@color/radar_home` | novo |
| `#15331E` | `@color/radar_line_soft` | novo |
| `#F008150E` | `@color/radar_panel_aF0` | novo |
| `#0C1C13` | `@color/radar_raised` | novo |
| `#660C1C13` | `@color/radar_raised_a66` | novo |
| `#F00C1C13` | `@color/radar_raised_aF0` | novo |
| `#04100A` | `@color/radar_sunken` | novo |
| `#20123F` | `@color/violet_card` | novo |
| `#141A0B34` | `@color/violet_deep_a14` | novo |
| `#661A0B34` | `@color/violet_deep_a66` | novo |
| `#8C1A0B34` | `@color/violet_deep_a8C` | novo |
| `#D91B0B34` | `@color/violet_deep_aD9` | novo |
| `#2D4A2B70` | `@color/violet_line_a2D` | novo |
| `#FFFFFF` | `@color/white` | reaproveitado |
| `#FFFFFFFF` | `@color/white` | reaproveitado |
| `#00FFFFFF` | `@color/white_a00` | novo |
| `#1FFFFFFF` | `@color/white_a1F` | novo |
| `#26FFFFFF` | `@color/white_a26` | novo |
| `#33FFFFFF` | `@color/white_a33` | novo |
| `#40FFFFFF` | `@color/white_a40` | novo |
| `#66FFFFFF` | `@color/white_a66` | novo |
| `#80FFFFFF` | `@color/white_a80` | novo |
| `#99FFFFFF` | `@color/white_a99` | novo |
| `#CCFFFFFF` | `@color/white_aCC` | novo |
| `#EAF6FF` | `@color/white_cool` | novo |
