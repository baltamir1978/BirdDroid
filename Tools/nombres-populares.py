#!/usr/bin/env python3
"""Las especies presentes en España y sus otros nombres, para la pestaña Buscar.

Qué especies: las del «Anexo:Aves de España» de la Wikipedia y las que el modelo de lugar y semana
de BirdNET (meta_weights.bin) da como posibles en algún punto de España en alguna semana del año.
El anexo trae aves que BirdNET no conoce (quebrantahuesos, avutarda…), y Buscar también las enseña.

Qué nombres: los del anexo, los alias y nombres comunes en español de Wikidata (CC0) y las
redirecciones de la Wikipedia en español, que es donde están «Aguzanieves» o «Pajarita de las nieves».

Escribe app/src/main/assets/nombres/es-espana.txt con líneas «Científico|Nombre|Nombre…» (a veces
sin nombres: la línea también dice que la especie está en España). Si BirdNET no tiene la especie,
el primer nombre es el suyo. Hacen falta numpy y los modelos bajados con Tools/descargar-modelos.sh.
"""
import json, re, sys, time, unicodedata, urllib.parse, urllib.request
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "app/src/main/assets/models"
OUT = ROOT / "app/src/main/assets/nombres/es-espana.txt"
UA = "BirdDroid-nombres-populares/1.0 (https://github.com/baltamir1978/BirdDroid)"

# Península de norte a sur y de oeste a este, montañas (Picos, Pirineos, Gredos, Sierra Nevada),
# Baleares, Canarias, Ceuta y Melilla.
POINTS = [(43.3, -8.4), (43.4, -5.8), (43.3, -2.0), (42.0, 2.8), (41.6, -4.7), (40.4, -3.7), (41.7, 0.6), (39.5, -0.4),
          (38.9, -6.3), (38.0, -1.1), (37.2, -6.2), (36.7, -4.4), (36.8, -2.4), (43.2, -4.8), (42.7, 0.5), (42.6, -1.0), (40.3, -5.3), (37.1, -3.4), (37.0, -6.4), (39.6, 2.9), (28.3, -16.5), (28.9, -13.7),
          (35.9, -5.3), (35.3, -2.9)]
# Bastante por debajo del 0,03 de BirdNET-Analyzer: aquí sólo decide qué sale antes en la lista, y
# con 0,03 se quedaban fuera aves escasas pero muy españolas, sobre todo de alta montaña.
THRESHOLD = 0.005

def checklist():
    """Científico → nombre común del «Anexo:Aves de España» de la Wikipedia (CC BY-SA)."""
    url = "https://es.wikipedia.org/w/api.php?action=parse&page=" + urllib.parse.quote("Anexo:Aves de España") + "&prop=wikitext&format=json&redirects=1"
    text = fetch(urllib.request.Request(url, headers={"User-Agent": UA}))["parse"]["wikitext"]["*"]
    found = {}
    for sci, name in re.findall(r"^\|\s*''\[\[(?:[^\]|]*\|)?([A-Z][a-z]+ [a-z-]+)\]\]''\s*\|\|\s*([^|]*(?:\[\[[^\]]*\]\][^|]*)?)\|\|", text, re.M):
        name = re.sub(r"<[^>]+>", "", re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", name)).strip()
        if name: found.setdefault(sci, name)
    return found

def spanish_species(names):
    raw = (MODELS / "meta_weights.bin").read_bytes()
    assert raw[:4] == b"BMET"
    f = np.frombuffer(raw[8:], dtype="<f4")
    offset = [48]
    def take(n):
        a = f[offset[0]:offset[0] + n]; offset[0] += n; return a
    w1 = take(256 * 144).reshape(256, 144); b1 = take(256)
    w2 = take(512 * 256).reshape(512, 256); b2 = take(512)
    w3 = take(1024 * 512).reshape(1024, 512); b3 = take(1024)
    w4 = take(6522 * 1024).reshape(6522, 1024); b4 = take(6522)
    best = np.zeros(6522)
    for lat, lon in POINTS:
        for week in range(1, 49):
            n = [(lat + 90) / 180, (lon + 180) / 360, week / 48]
            e = np.concatenate([np.sqrt(2) * np.sin(2 * np.pi * x + np.arange(48) * np.pi / 48) for x in n]).astype(np.float32)
            h = np.maximum(w1 @ e + b1, 0); h = np.maximum(w2 @ h + b2, 0); h = np.maximum(w3 @ h + b3, 0)
            best = np.maximum(best, 1 / (1 + np.exp(-(w4 @ h + b4))))
    return [s for s, p in zip(names, best) if p >= THRESHOLD and is_species(s)]

def is_species(s):
    w = s.split()
    return len(w) == 2 and w[0][0].isupper() and w[1].islower()

def fetch(req):
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                return json.load(r)
        except Exception as e:
            print("  reintento:", e, file=sys.stderr); time.sleep(10 * (attempt + 1))
    raise SystemExit("El servicio no responde")

def wikidata(species):
    found = {}
    for i in range(0, len(species), 200):
        values = " ".join('"%s"' % s for s in species[i:i + 200])
        q = """SELECT ?sci ?name WHERE { VALUES ?sci { %s } ?t wdt:P225 ?sci.
               { ?t skos:altLabel ?name } UNION { ?t rdfs:label ?name } UNION { ?t wdt:P1843 ?name }
               FILTER(LANG(?name) = "es") }""" % values
        data = urllib.parse.urlencode({"query": q, "format": "json"}).encode()
        result = fetch(urllib.request.Request("https://query.wikidata.org/sparql", data=data,
                                              headers={"User-Agent": UA, "Accept": "application/sparql-results+json"}))
        for b in result["results"]["bindings"]:
            found.setdefault(b["sci"]["value"], []).append(b["name"]["value"])
        time.sleep(1)
    return found

def redirects(species):
    found = {}
    for i in range(0, len(species), 50):
        batch = species[i:i + 50]
        params = {"action": "query", "titles": "|".join(batch), "prop": "redirects", "rdlimit": "max", "rdnamespace": 0, "redirects": 1, "format": "json"}
        while True:
            url = "https://es.wikipedia.org/w/api.php?" + urllib.parse.urlencode(params)
            d = fetch(urllib.request.Request(url, headers={"User-Agent": UA}))
            # Si el título pedido ya redirige (otro género), la página se llama de otra forma.
            target = {r["to"]: r["from"] for r in d["query"].get("redirects", [])}
            for p in d["query"]["pages"].values():
                sci = target.get(p.get("title"), p.get("title"))
                found.setdefault(sci, []).extend(r["title"] for r in p.get("redirects", []))
                if p.get("title") != sci: found[sci].append(p["title"])
            if "continue" not in d: break
            params.update(d["continue"])
        time.sleep(0.3)
    return found

def norm(s):
    return " ".join("".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn").replace("-", " ").split())

def main():
    rows = [l.rstrip("\n").split("_", 1) for l in (MODELS / "labels.txt").read_text(encoding="utf-8").splitlines() if l.strip()]
    spanish = {s: n for s, n in (l.rstrip("\n").split("_", 1) for l in (MODELS / "labels_es.txt").read_text(encoding="utf-8").splitlines() if l.strip())}
    names = [r[0] for r in rows]
    genera = {s.split()[0] for s in names}
    by_epithet = {}
    for sci in names:
        if is_species(sci): by_epithet.setdefault(sci.split()[1], []).append(sci)
    def in_birdnet(sci, name):
        """El anexo es de 2015 y hay especies que hoy se llaman de otra forma («Larus audouinii» es
        «Ichthyaetus audouinii»): se reconocen por el epíteto y alguna palabra del nombre común."""
        if sci in spanish: return sci
        genus, epithet = sci.split()
        same_genus = [s for s in names if is_species(s) and s.split()[0] == genus and s.split()[1][:5] == epithet[:5]]  # hybridus/hybrida
        if len(same_genus) == 1: return same_genus[0]
        words = set(norm(name).split()) - {"de", "del", "la", "el", "comun"}
        same = [s for s in by_epithet.get(epithet, []) if words & set(norm(spanish.get(s, "")).split())]
        # Si hay varias, la que coincide en la primera palabra: «Curruca Cabecinegra», no otra «Cabecinegra».
        first = [s for s in same if norm(spanish.get(s, "")).split()[0] == norm(name).split()[0]]
        return (first or same)[0] if len(first or same) == 1 else None

    listed = {}          # especie → nombres del anexo
    only_here = set()    # las que BirdNET no tiene
    for sci, name in checklist().items():
        target = in_birdnet(sci, name)
        if target is None: only_here.add(sci)
        listed.setdefault(target or sci, []).append(name)
    species = sorted(set(spanish_species(names)) | set(listed))
    print(len(species), "especies en España,", len(only_here), "que BirdNET no tiene")
    wd = wikidata(species)
    wp = redirects(species)
    lines = []
    for s in species:
        own = listed.get(s, []) if s in only_here else []
        seen = {norm(s), norm(spanish.get(s, ""))} | {norm(n) for n in own}
        aliases = []
        for a in listed.get(s, []) + sorted(set(wd.get(s, []) + wp.get(s, []))):
            a = a.strip()
            # Fuera los sinónimos científicos (empiezan por un género), desambiguaciones y frases.
            if a.split()[0] in genera or len(a.split()[0].rstrip(".")) < 2 or "(" in a or "|" in a or len(a) > 40 or re.search(r"\d", a): continue
            if norm(a) in seen: continue
            seen.add(norm(a)); aliases.append(a)
        lines.append("|".join([s] + own + aliases))
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(sum("|" in l for l in lines), "con nombres,", sum(l.count("|") for l in lines), "nombres →", OUT.relative_to(ROOT))
    print("BirdNET no tiene:", ", ".join(sorted(only_here)))

if __name__ == "__main__":
    main()
