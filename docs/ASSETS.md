# Origem das imagens

- Logo da plataforma e do pack: /srv/games/minecraft/server-icon.png, do FeatherFlux.
- Item: assets/minecraft/textures/item/music_disc_13.png do cliente Mojang26.2,
  obtido pelo version_manifest_v2.json oficial (piston-meta.mojang.com).
  SHA1 do JAR verificado contra o manifesto antes de extrair.
- Cópia local: src/main/resources/disc-template.png.
- DiscColors.java só recolore pixels cromáticos do miolo; preserva alpha, formato,
  sulcos e pixels cinzentos do disco original. A logo não é mais usada no item.
- As12variantes são geradas pela mesma função tanto no ZIP quanto na prévia web.
