#!/usr/bin/env python3
"""Verify every Component.translatable key in commands/ exists in en_us+zh_cn
with placeholder count == argument count. Dynamic keys (string + ...) are
checked as prefixes. Exit 1 on any mismatch."""
import json, re, sys
from pathlib import Path
root = Path(__file__).resolve().parent.parent
langs = {}
for name in ("en_us", "zh_cn"):
    langs[name] = json.loads((root/f"src/main/resources/assets/teamprojecte/lang/{name}.json").read_text(encoding="utf-8"))

def placeholders(s):
    return len(re.findall(r"%[sd]", s))

def split_top(s):
    parts, depth, cur, in_str = [], 0, "", False
    i = 0
    while i < len(s):
        c = s[i]
        if in_str:
            cur += c
            if c == '"' and s[i-1] != '\\':
                in_str = False
        elif c == '"':
            in_str = True; cur += c
        elif c in '([{':
            depth += 1; cur += c
        elif c in ')]}':
            depth -= 1; cur += c
        elif c == ',' and depth == 0:
            parts.append(cur.strip()); cur = ""
        else:
            cur += c
        i += 1
    if cur.strip():
        parts.append(cur.strip())
    return parts

errors = []
checked = 0
for java in sorted((root/"src/main/java").rglob("*.java")):
    text = java.read_text(encoding="utf-8")
    for m in re.finditer(r"translatable\s*\(", text):
        start = m.end()
        # balanced scan
        depth, i, in_str = 1, start, False
        while i < len(text) and depth:
            c = text[i]
            if in_str:
                if c == '"' and text[i-1] != '\\':
                    in_str = False
            elif c == '"':
                in_str = True
            elif c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            i += 1
        args = split_top(text[start:i-1])
        if not args:
            continue
        first = args[0]
        km = re.match(r'^"([^"]+)"\s*$', first)
        dyn = re.match(r'^"([^"]+)"\s*\+', first)
        line = text[:m.start()].count("\n") + 1
        loc = f"{java.relative_to(root)}:{line}"
        argc = len(args) - 1
        if km:
            key = km.group(1)
            for ln, lang in langs.items():
                if key not in lang:
                    errors.append(f"{loc} MISSING {ln}: {key}")
                elif placeholders(lang[key]) != argc:
                    errors.append(f"{loc} PLACEHOLDER {ln}: {key} lang={placeholders(lang[key])} args={argc}")
            checked += 1
        elif dyn:
            prefix = dyn.group(1)
            for ln, lang in langs.items():
                if not any(k.startswith(prefix) for k in lang):
                    errors.append(f"{loc} DYNAMIC-PREFIX MISSING {ln}: {prefix}...")
            checked += 1
        else:
            errors.append(f"{loc} UNPARSEABLE first arg: {first[:60]}")

# also: lang keys must agree on placeholder counts between locales
for k in sorted(set(langs["en_us"]) | set(langs["zh_cn"])):
    if k in langs["en_us"] and k in langs["zh_cn"]:
        if placeholders(langs["en_us"][k]) != placeholders(langs["zh_cn"][k]):
            errors.append(f"LOCALE-DIFF {k}: en={placeholders(langs['en_us'][k])} zh={placeholders(langs['zh_cn'][k])}")
    else:
        errors.append(f"LOCALE-MISSING {k}")

print(f"checked {checked} translatable call sites")
if errors:
    print("\n".join(errors)); sys.exit(1)
print("OK: all keys present in en_us+zh_cn, placeholder counts match")
