# TSE official results data (reference)

> Checked 2026-10-05 from the research container with curl (VERIFIED). **Not the product** (DECISIONS D-001): the safety net SN1 is the TSE results **web page** opened in the in-game browser. Kept here because it explains what that page loads and how fresh it is.

- Web app: `https://resultados.tse.jus.br/oficial/app/index.html` (Angular app; it verifies JWS signatures with keys under `assets/assinatura-jws/`; it has a "telão" mode in its code).
- Election list: `https://resultados.tse.jus.br/oficial/comum/config/ele-c.json`. For cycle `ele2026`: election `6257` = federal 1st round (President), `cdt2` `6258` = 2nd round; `6259`/`6260` = state elections.
- Unified results per office (President = cargo `0001`): `https://resultados.tse.jus.br/oficial/ele2026/<cd>/dados/br/br-c0001-e00<cd>-u.json` (cd zero-padded to 6 digits). On 2026-10-05 the 1st-round file returned HTTP 200, 2,461 bytes gzipped, `cache-control: max-age≈55-60`. The 2nd-round file appears on the day.
- Other files: `.../dados/br/br-e00<cd>-ab.json` (tracking), `.../config/mun-e00<cd>-cm.json` (municipalities). The 2022-style `dados-simplificados/...-r.json` paths returned 404 in 2026.
- Fields seen: `s.pst` (% of sections totalled), `carg[].agr[].par[].cand[]` with `n` (number), `nm` (name), `vap` (votes), `pvap` (%), `e` (elected flag), `st`, `seq` (order); `dg`/`hg` (generation date/time).
- Divulgation starts at 17:00 BRT (TRE-PE news, Aug 2026).

Practical notes for SN1: the site is a multi-host SPA, so a site allowlist must apply to main-frame navigation by domain (not to every subresource host), and the site is heavily loaded on results night. Rehearse SN1 against the live site before 10-25.
