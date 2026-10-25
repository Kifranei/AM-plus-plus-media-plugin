"""Read-only verification of this plugin's Apple Music profile against a supplied package.

Every check below is an evidence claim taken from the adaptation record: the DEX class name, the
complete method signature (owner, static/instance descriptor and return type) and, where the
adaptation pinned one, the field name. No APK code is executed and no file is written.

Usage:
    python scripts/verify-host-profile.py apple-music-1613.apks --glass   # the profile is chosen from the APK's own version
"""
import argparse
import io
import json
import struct
import sys
import zipfile
import importlib.util
from pathlib import Path

_manifest_spec = importlib.util.spec_from_file_location('android_manifest',Path(__file__).with_name('android-package-manifest.py'))
_manifest_module = importlib.util.module_from_spec(_manifest_spec)
_manifest_spec.loader.exec_module(_manifest_module)


def _uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("Invalid ULEB128")


def dex_classes(data):
    """Return {class descriptor: {"super": ..., "methods": set, "fields": dict}}."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    strings = []
    for i in range(u32(56)):
        _, offset = _uleb(data, u32(u32(60) + 4 * i))
        strings.append(data[offset:data.index(0, offset)].decode("utf8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        base = u32(76) + i * 12
        parameters = u32(base + 8)
        params = "" if not parameters else "".join(
            types[u16(parameters + 4 + j * 2)] for j in range(u32(parameters))
        )
        protos.append("(" + params + ")" + types[u32(base + 4)])
    field_defs = []
    for i in range(u32(80)):
        _, type_idx, name_idx = struct.unpack_from("<HHI", data, u32(84) + i * 8)
        field_defs.append((strings[name_idx], types[type_idx]))
    method_defs = []
    for i in range(u32(88)):
        _, proto_idx, name_idx = struct.unpack_from("<HHI", data, u32(92) + i * 8)
        method_defs.append(strings[name_idx] + protos[proto_idx])
    classes = {}
    for i in range(u32(96)):
        base = u32(100) + i * 32
        class_name = types[u32(base)]
        superclass = u32(base + 8)
        offset = u32(base + 24)
        methods = set()
        fields = {}
        method_access = {}
        field_access = {}
        new_instances = set()
        check_casts = set()
        if offset:
            counts = []
            for _ in range(4):
                count, offset = _uleb(data, offset)
                counts.append(count)
            for section in counts[:2]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    fields[field_defs[index][0]] = field_defs[index][1]
                    field_access[field_defs[index][0]] = flags
            for section in counts[2:]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    code_off, offset = _uleb(data, offset)
                    if code_off:
                        # Behavioural evidence: which types this class instantiates / casts to.
                        size = u32(code_off + 12)
                        for unit_at in range(code_off + 16, code_off + 16 + size * 2 - 3, 2):
                            unit = u16(unit_at)
                            operand = u16(unit_at + 2)
                            if operand < len(types):
                                if unit & 255 == 0x22:
                                    new_instances.add(types[operand])
                                elif unit & 255 == 0x1F:
                                    check_casts.add(types[operand])
                    methods.add(method_defs[index])
                    method_access[method_defs[index]] = flags
        classes[class_name] = {
            "super": types[superclass] if superclass != 0xFFFFFFFF else None,
            "methods": methods,
            "fields": fields,
            "method_access": method_access,
            "field_access": field_access,
            "new_instances": new_instances,
            "check_casts": check_casts,
        }
    return classes


PROFILE_DIRECTORY = Path(__file__).resolve().parents[1] / "media-plugin/src/main/assets"


def load_documents():
    """Every shipped profile, keyed by (versionName, versionCode); assets/profiles.json is the index."""
    index = json.loads((PROFILE_DIRECTORY / "profiles.json").read_text(encoding="utf-8"))
    documents = {}
    for entry in index["profiles"]:
        data = json.loads((PROFILE_DIRECTORY / entry["asset"]).read_text(encoding="utf-8"))
        assert (data["packageName"], data["versionName"], int(data["versionCode"])) == (
            index["packageName"], entry["versionName"], entry["versionCode"]), entry["asset"]
        documents[(data["versionName"], str(data["versionCode"]))] = (data, PROFILE_DIRECTORY / entry["asset"])
    return documents


DOCUMENTS = load_documents()


def load_profiles():
    return {key: data["verification"] for key, (data, _) in DOCUMENTS.items()}


PROFILES = load_profiles()
CATALOG_QUERY_SHAPE = "(Ljava/lang/String;Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"


def find_method(classes, owner, signature):
    current = owner
    while current in classes:
        if signature in classes[current]["methods"]:
            return current
        current = classes[current]["super"]
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path, help="APK, XAPK or APKS (all DEX splits are checked)")
    parser.add_argument("--version-name", default=None)
    parser.add_argument("--version-code", default=None)
    parser.add_argument("--profile", type=Path, default=None, help="plugin host profile (default: the shipped profile for the APK version)")
    parser.add_argument("--glass", action="store_true", help="also verify the phone glass seams")
    args = parser.parse_args()

    with zipfile.ZipFile(args.package) as package:
        names = package.namelist()
        manifest = None
        if "manifest.json" in names:
            manifest = json.loads(package.read("manifest.json"))
        base_name = next(
            (name for name in names if name.endswith(".apk") and "config" not in name),
            None,
        )
        if base_name is None:
            base_name = args.package.name
            apk = zipfile.ZipFile(args.package)
        else:
            apk = zipfile.ZipFile(io.BytesIO(package.read(base_name)))
        package_name, actual_name, actual_code = _manifest_module.manifest_identity(apk.read('AndroidManifest.xml'))
        if package_name != 'com.apple.android.music':
            raise SystemExit('Unexpected manifest package: '+package_name)
        version_name = args.version_name or actual_name
        version_code = args.version_code or str(actual_code)
        if (version_name,str(version_code)) != (actual_name,str(actual_code)):
            raise SystemExit(f'Claimed tuple {version_name}/{version_code} differs from binary manifest {actual_name}/{actual_code}')
        if manifest and ((manifest.get('version_name') and manifest['version_name'] != actual_name) or
                         (manifest.get('version_code') and str(manifest['version_code']) != str(actual_code))):
            raise SystemExit('Split package manifest disagrees with base Android manifest')
        profile = PROFILES.get((version_name or "", str(version_code)))
        document = None
        if args.profile is None and (actual_name, str(actual_code)) in DOCUMENTS:
            args.profile = DOCUMENTS[(actual_name, str(actual_code))][1]
        if args.profile:
            document = json.loads(args.profile.read_text(encoding="utf-8"))
            if (document["packageName"], document["versionName"], int(document["versionCode"])) != (
                package_name, actual_name, actual_code
            ):
                raise SystemExit("Explicit profile differs from binary manifest")
            profile = document["verification"]
        if profile is None:
            raise SystemExit(
                "Unsupported or undocumented version tuple: %s (%s). Known profiles: %s"
                % (version_name, version_code, ", ".join("%s (%s)" % key for key in sorted(PROFILES))),
            )
        if version_code != profile["version_code"]:
            raise SystemExit(
                "Version tuple mismatch: %s (%s) does not match the recorded %s profile"
                % (version_name, version_code, profile["version_code"]),
            )

        ALIASED_TYPES = profile.get("aliases", {})
        GLASS_METHODS = profile.get("glass_methods", {})
        LAYOUTS = profile.get("layouts", [])
        classes = {}
        for name in apk.namelist():
            if name.endswith(".dex"):
                classes.update(dex_classes(apk.read(name)))
        available_layouts = set(apk.namelist())
        for split_name in names:
            if not split_name.endswith(".apk") or split_name == base_name:
                continue
            with zipfile.ZipFile(io.BytesIO(package.read(split_name))) as split:
                available_layouts.update(split.namelist())
                for name in split.namelist():
                    if name.endswith(".dex"):
                        classes.update(dex_classes(split.read(name)))

        failures = []
        checks = 0

        if document is None:
            candidate = PROFILE_DIRECTORY / f"{actual_name}-{actual_code}.json"
            if candidate.is_file():
                document = json.loads(candidate.read_text(encoding="utf-8"))
        if document and document.get('cardSharing'):
            # Symbol existence is not enough (1611 kept a stale 1609 observer `...activities.l` that
            # exists but is an unrelated lambda). The share-progress fragment must itself instantiate
            # the observer class, and the observer must cast its captured owner to that progress
            # class and its LiveData value to the result wrapper.
            card = document['cardSharing']
            def dotted(name): return 'L' + name.replace('.', '/') + ';'
            progress, observer, result = dotted(card['progressClass']), dotted(card['observerClass']), dotted(card['resultClass'])
            checks += 3
            if observer not in classes.get(progress, {}).get('new_instances', set()):
                failures.append(f'cardSharing observer {observer} is not instantiated by progress class {progress}')
            typed_owner = progress in classes.get(observer, {}).get('fields', {}).values()  # 1606: typed field, no cast
            if progress not in classes.get(observer, {}).get('check_casts', set()) and not typed_owner:
                failures.append(f'cardSharing observer {observer} never casts its owner to {progress}')
            if result not in classes.get(observer, {}).get('check_casts', set()):
                failures.append(f'cardSharing observer {observer} never casts its value to {result}')
            # Uniqueness: the same structural anchors the DexKit pilot uses must point at exactly
            # this observer. Multiple matches would mean a silent wrong-class hook risk.
            structural = []
            for ni in classes.get(progress, {}).get('new_instances', set()):
                info = classes.get(ni, {})
                casts = info.get('check_casts', set())
                field_types = set(info.get('fields', {}).values())
                owns = progress in casts or progress in field_types
                has_on_changed = any(sig.startswith('onChanged(') for sig in info.get('methods', set()))
                if result in casts and owns and has_on_changed:
                    structural.append(ni)
            checks += 1
            if structural != [observer]:
                failures.append(
                    f'cardSharing structural observer set {structural} != profile {[observer]}'
                )

        if document:
            def descriptor(name):
                primitive = {'void':'V','int':'I','long':'J','float':'F','boolean':'Z',
                             'double':'D','byte':'B','short':'S','char':'C'}
                return primitive.get(name, name.replace('.', '/') if name.startswith('[')
                                     else 'L' + name.replace('.', '/') + ';')
            for symbol, contract in document['indexed'].get('methodContracts', {}).items():
                checks += 1
                owner = descriptor(contract['owner'])
                signature = contract['name'] + '(' + ''.join(map(descriptor, contract['parameters'])) + ')' + descriptor(contract['returns'])
                flags = classes.get(owner, {}).get('method_access', {}).get(signature)
                prohibited = 0x40 | 0x1000 | (0 if contract.get('invocationOnly') else 0x400)
                if flags is None or bool(flags & 8) != contract['static'] or flags & prohibited:
                    failures.append(f'invalid exact method contract {symbol}: {owner} {signature}')
            for symbol, contract in document['indexed'].get('fieldContracts', {}).items():
                checks += 1
                owner = descriptor(contract['owner'])
                native = classes.get(owner, {})
                if native.get('fields', {}).get(contract['name']) != descriptor(contract['type']) or native.get('field_access', {}).get(contract['name'], 8) & 8:
                    failures.append(f'invalid exact field contract {symbol}: {owner} {contract["name"]}')

        if document:
            # Named-member sections are read with reflection by the integrations, so existence is not
            # enough: 1613 kept the 1611 Lyricon pointer name `g1`, which still exists but is now an int
            # (the SongInfoPtr moved to `f1`). Check the exact descriptors each install() asks for.
            def ldesc(name): return 'L' + name.replace('.', '/') + ';'
            def declared(owner, signature):
                return signature in classes.get(ldesc(owner), {}).get('methods', set())
            def field_type(owner, name):
                return classes.get(ldesc(owner), {}).get('fields', {}).get(name)
            def method_named(owner, name, params):
                return sorted(sig for sig in classes.get(ldesc(owner), {}).get('methods', set())
                              if sig.startswith(name + '(' + params + ')'))
            def require(ok, message):
                nonlocal_checks[0] += 1
                if not ok:
                    failures.append(message)
            nonlocal_checks = [0]
            pointer = 'Lcom/apple/android/music/ttml/javanative/model/SongInfo$SongInfoPtr;'
            native = 'com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoNative'
            lyricon = document.get('lyricon')
            if lyricon:
                model, item = lyricon['viewModelClass'], lyricon['itemClass']
                install = document['indexed']['methodContracts']['lyrics-install-method']
                field = lyricon['installedPointerField']
                actual = field_type(install['owner'], field)
                require(actual == pointer, f'lyricon.installedPointerField {install["owner"]}.{field} is {actual}, '
                        f'expected {pointer} (SongInfoPtr fields: '
                        f'{[n for n, t in classes.get(ldesc(install["owner"]), {}).get("fields", {}).items() if t == pointer]})')
                require(method_named(model, lyricon['buildMethod'], pointer), f'lyricon.buildMethod {model}.{lyricon["buildMethod"]}({pointer}) missing')
                require(method_named(model, lyricon['loadMethod'], ldesc(item)), f'lyricon.loadMethod {model}.{lyricon["loadMethod"]}({item}) missing')
                require(declared(model, '<init>(Landroid/app/Application;)V'), f'lyricon {model}(Application) constructor missing')
                for getter in ('getCurrentSystemLyricsLanguage()', 'getTranslationSelectedLiveResult()', 'getPronunciationSelectedLiveResult()'):
                    require(any(sig.startswith(getter) for sig in classes.get(ldesc(model), {}).get('methods', set())),
                            f'lyricon {model}.{getter} missing')
                languages = [sig[len('getPronunciationLanguages()'):] for sig in classes.get(ldesc(native), {}).get('methods', set())
                             if sig.startswith('getPronunciationLanguages()')]
                require(len(languages) == 1 and method_named(lyricon['localeUtilClass'], 'matchToSystemLyricsScript', languages[0]),
                        f'lyricon.localeUtilClass {lyricon["localeUtilClass"]}.matchToSystemLyricsScript({languages}) missing')
                require(declared(lyricon['observerClass'], 'onChanged(Ljava/lang/Object;)V'),
                        f'lyricon.observerClass {lyricon["observerClass"]} is not an Observer (no onChanged(Object))')
            quality = document.get('audioQualityDialog')
            if quality:
                binding = quality['bindingClass']
                require(method_named(binding, quality['bindingLookupMethod'], 'Landroid/view/View;') ==
                        [quality['bindingLookupMethod'] + '(Landroid/view/View;)' + ldesc(binding)],
                        f'audioQualityDialog.bindingLookupMethod {binding}.{quality["bindingLookupMethod"]}(View) is not the binding lookup')
                require(declared(binding, quality['bindingExecuteMethod'] + '()V'),
                        f'audioQualityDialog.bindingExecuteMethod {binding}.{quality["bindingExecuteMethod"]}()V missing')
                require(ldesc(quality['dialogClass']) in classes, f'audioQualityDialog.dialogClass {quality["dialogClass"]} missing')
            card = document.get('cardSharing')
            if card:
                for key, owner, expected in (('sourceListField', card['sourceClass'], 'Ljava/util/ArrayList;'),
                                             ('progressOptionField', card['progressClass'], ldesc(card['itemClass'])),
                                             ('progressItemField', card['progressClass'], ldesc(card['itemClass']))):
                    actual = field_type(owner, card[key])
                    require(actual == expected, f'cardSharing.{key} {owner}.{card[key]} is {actual}, expected {expected}')
                require(field_type(card['observerClass'], card['observerProgressField']) is not None,
                        f'cardSharing.observerProgressField {card["observerClass"]}.{card["observerProgressField"]} missing')
                require(method_named(card['resultClass'], card['resultValueMethod'], '') ==
                        [card['resultValueMethod'] + '()Ljava/lang/Object;'],
                        f'cardSharing.resultValueMethod {card["resultClass"]}.{card["resultValueMethod"]}()Object missing')
                require(method_named(card['fileProviderClass'], card['fileProviderMethod'],
                                     'Landroid/content/Context;Ljava/io/File;Ljava/lang/String;') ==
                        [card['fileProviderMethod'] + '(Landroid/content/Context;Ljava/io/File;Ljava/lang/String;)Landroid/net/Uri;'],
                        f'cardSharing.fileProviderMethod {card["fileProviderClass"]}.{card["fileProviderMethod"]} is not getUriForFile')
                item = ldesc(card['itemClass'])
                require(declared(card['viewModelClass'], f'setShareItemDetails({item}{item}[Ljava/lang/String;Ljava/lang/String;)V'),
                        f'cardSharing {card["viewModelClass"]}.setShareItemDetails missing')
            for section, keys in (('audioOutput', ('buttonClass',)), ('playerMoreMenu', ('dialogClass',)),
                                  ('playerDialogs', ('confirmationFragmentClass', 'sleepFragmentClass')),
                                  ('contentDownloads', ('artistFragmentClass', 'artistModelClass', 'albumFragmentClass',
                                                        'loaderContinuationClass', 'composeAlbumFragmentClass',
                                                        'composeAlbumModelClass'))):
                for key in keys:
                    name = document.get(section, {}).get(key)
                    if name:
                        require(ldesc(name) in classes, f'{section}.{key} class {name} missing')
            checks += nonlocal_checks[0]

        for owner, signatures in profile["methods"].items():
            if owner not in classes:
                failures.append("missing class %s" % owner)
                continue
            for signature in signatures:
                checks += 1
                where = find_method(classes, owner, signature)
                if where is None:
                    failures.append("missing method %s %s" % (owner, signature))
        for owner, field_names in profile["fields"].items():
            for field_name in field_names:
                checks += 1
                if owner not in classes:
                    failures.append("missing class %s" % owner)
                elif field_name not in classes[owner]["fields"]:
                    failures.append("missing field %s %s" % (owner, field_name))

        for owner, fields in profile.get("fieldTypes", {}).items():
            for name, expected in fields.items():
                checks += 1
                native = classes.get(owner, {})
                if native.get("fields", {}).get(name) != expected or native.get("field_access", {}).get(name, 8) & 8:
                    failures.append("invalid native field descriptor %s %s:%s" % (owner, name, expected))

        for owner, signatures in ALIASED_TYPES.items():
            for signature in signatures:
                checks += 1
                if owner not in classes:
                    failures.append("missing obfuscated type %s" % owner)
                elif signature not in classes[owner]["methods"]:
                    failures.append("missing alias member %s %s" % (owner, signature))

        query = profile.get("catalog_query")
        if query:
            owner = query["owner"]
            checks += 1
            if owner not in classes:
                failures.append("missing catalog query class %s" % owner)
            elif query["verified"] + CATALOG_QUERY_SHAPE not in classes[owner]["methods"]:
                failures.append(
                    "missing catalog query %s %s"
                    % (owner, query["verified"] + CATALOG_QUERY_SHAPE)
                )
            if query["preferred"] != query["verified"]:
                checks += 1
                if owner in classes and (
                    query["preferred"] + CATALOG_QUERY_SHAPE in classes[owner]["methods"]
                ):
                    failures.append(
                        "%s %s satisfies the catalog query shape on %s, so the verified rename %s "
                        "is unreachable" % (
                            owner,
                            query["preferred"],
                            version_name,
                            query["verified"],
                        )
                    )

        interceptor = profile.get("content_http_interceptor")
        if interceptor:
            owner = interceptor["owner"]
            checks += 1
            if owner not in classes:
                failures.append("missing content HTTP interceptor %s" % owner)
            elif interceptor["signature"] not in classes[owner]["methods"]:
                failures.append(
                    "missing content HTTP interceptor method %s %s"
                    % (owner, interceptor["signature"])
                )
            vacated = interceptor.get("vacated")
            if vacated:
                checks += 1
                vacated_owner, vacated_signature = vacated
                if vacated_owner in classes and (
                    vacated_signature in classes[vacated_owner]["methods"]
                ):
                    failures.append(
                        "%s still declares %s on %s, so the pinned interceptor %s is shadowed"
                        % (vacated_owner, vacated_signature, version_name, owner)
                    )

        if args.glass:
            for owner, signatures in GLASS_METHODS.items():
                for signature in signatures:
                    checks += 1
                    if find_method(classes, owner, signature) is None:
                        failures.append("missing glass hook %s %s" % (owner, signature))
            for layout in LAYOUTS:
                checks += 1
                if layout not in available_layouts:
                    failures.append("missing layout %s" % layout)

        print("package: %s" % args.package)
        print("base apk: %s" % base_name)
        print("version tuple: %s (%s)" % (version_name, version_code))
        print("checks: %d, failures: %d" % (checks, len(failures)))
        if failures:
            for failure in failures:
                print("FAIL %s" % failure)
            sys.exit(1)
        print("PASS: Apple Music %s (%s) profile symbols verified" % (version_name, version_code))


if __name__ == "__main__":
    main()
