#!/usr/bin/env python3
"""Completa los nombres comunes que las etiquetas de BirdNET dejan en inglés, con Wikidata (CC0).

Las etiquetas oficiales traen sin traducir parte de las especies (el italiano, casi todas). Para
cada idioma busca las filas cuyo nombre sigue igual que en inglés y pide a Wikidata el «nombre común
del taxón» (P1843). Sólo se usa lo que Wikidata tiene: lo que no aparece se queda en inglés.

Escribe app/src/main/assets/nombres/<idioma>.txt con líneas «Científico_Nombre», que la app aplica
encima de las etiquetas. Hacen falta las etiquetas bajadas con Tools/descargar-modelos.sh.
"""
import io, json, sys, time, urllib.parse, urllib.request, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "app/src/main/assets/models"
OUT = ROOT / "app/src/main/assets/nombres"
LANGS = {"es": "labels_es.txt", "de": "labels_de.txt", "fr": "labels_fr.txt", "it": "labels_it.txt", "pt": "labels_pt_PT.txt"}
UA = "BirdDroid-completar-nombres/1.0 (https://github.com/baltamir1978/BirdDroid)"
BATCH = 250

# BirdNET usa algún género que Wikidata ya tiene bajo otro nombre.
GENERA = {"Ciccaba": "Strix", "Dryophytes": "Hyla", "Hyliola": "Pseudacris", "Lithobates": "Rana", "Anaxyrus": "Bufo",
          "Psophocichla": "Turdus", "Otocichla": "Turdus", "Accipiter": "Microspizias", "Phylloscartes": "Pogonotriccus",
          "Streptopelia": "Spilopelia", "Tamias": "Eutamias"}

# Nombres bien asentados que Wikidata no recoge como nombre común.
KNOWN = {"es": {"Apis mellifera": "Abeja melífera", "Sciurus carolinensis": "Ardilla gris", "Tamias striatus": "Ardilla listada",
                "Tamiasciurus hudsonicus": "Ardilla roja americana"}}

def aiy_species():
    """Las especies del modelo de fotos, que van en un zip al final del .tflite."""
    data = (MODELS / "aiy_birds_v1.tflite").read_bytes()
    with zipfile.ZipFile(io.BytesIO(data[data.index(b"PK\x03\x04"):])) as z:
        names = z.read("probability-labels-en.txt").decode().splitlines()
    return {" ".join(n.replace("_", " ").split()[:2]) for n in names if len(n.replace("_", " ").split()) >= 2}

def rows(name):
    return [l.rstrip("\n").split("_", 1) for l in (MODELS / name).read_text(encoding="utf-8").splitlines() if l.strip()]

def query(names, lang):
    values = " ".join('"%s"' % n for n in names)
    q = """SELECT ?sci ?name ?rank WHERE { VALUES ?sci { %s } ?t wdt:P225 ?sci; p:P1843 ?st.
           ?st ps:P1843 ?name; wikibase:rank ?rank. FILTER(LANG(?name) = "%s") FILTER(?rank != wikibase:DeprecatedRank) }""" % (values, lang)
    data = urllib.parse.urlencode({"query": q, "format": "json"}).encode()
    req = urllib.request.Request("https://query.wikidata.org/sparql", data=data, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                return json.load(r)["results"]["bindings"]
        except Exception as e:
            print("  reintento:", e, file=sys.stderr); time.sleep(10 * (attempt + 1))
    raise SystemExit("Wikidata no responde")

def main():
    en = rows("labels.txt")
    known = {e[0] for e in en}
    OUT.mkdir(parents=True, exist_ok=True)
    for lang, file in LANGS.items():
        loc = rows(file)
        missing = [e[0] for e, l in zip(en, loc) if e[1] == l[1] and " " in e[0] and e[0][0].isupper() and e[0].split()[1].islower()]
        missing += sorted(aiy_species() - known)  # las fotos que BirdNET no tiene
        synonyms = {g: s for s in missing for g in [GENERA.get(s.split()[0], "") + " " + s.split()[1]] if not g.startswith(" ")}
        asked = missing + sorted(synonyms)
        found = {}
        for i in range(0, len(asked), BATCH):
            for b in query(asked[i:i + BATCH], lang):
                sci, name = b["sci"]["value"], b["name"]["value"].split(";")[0].strip()  # a veces trae varios
                sci = synonyms.get(sci, sci)
                preferred = b["rank"]["value"].endswith("PreferredRank")
                if name and name.lower() != sci.lower():
                    found.setdefault(sci, []).append((not preferred, name))
            time.sleep(1)
        for sci, name in KNOWN.get(lang, {}).items():
            found.setdefault(sci, [(False, name)])
        # A igual rango, siempre el mismo (el resultado no depende del orden de la consulta).
        lines = ["%s_%s" % (s, sorted(v)[0][1]) for s, v in sorted(found.items())]
        (OUT / f"{lang}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{lang}: {len(missing)} sin traducir, {len(lines)} completados")

if __name__ == "__main__":
    main()
