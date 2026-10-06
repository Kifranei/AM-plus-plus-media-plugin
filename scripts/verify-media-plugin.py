"""Check the actual DEX ZIP and reject module/SDK implementation copies."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("host_dex", ROOT / "scripts/verify-host-profile.py")
    dex = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(dex)
    definitions = {}
    with zipfile.ZipFile(args.archive) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP paths"
        assert {"plugin.json", "code.jar"} <= set(names)
        assert all(name in {"plugin.json", "code.jar"} or name.startswith("assets/") for name in names)
        manifest = json.loads(archive.read("plugin.json"))
        assert manifest["id"] == "dev.kifranei.ampp.media"
        assert manifest["versionName"] == "1.1.0"
        assert manifest["versionCode"] == 10
        assert manifest["formatVersion"] == manifest["apiVersion"] == 1
        assert manifest["minAndroidApi"] == 28
        for asset in ("applemusic-1606.json", "ios-media-output.path", "ios-output-headphones.path",
                      "ios-output-speaker.png", "ios-output-airpods.png", "licenses/AppleMusicOutputIcons.txt", "licenses/LyricProvider.txt",
                      "licenses/AndroidLiquidGlass.txt", "licenses/AMpp-GPL-3.0.txt"):
            assert archive.getinfo("assets/" + asset).file_size > 0, asset
        with zipfile.ZipFile(io.BytesIO(archive.read("code.jar"))) as code:
            assert "classes.dex" in code.namelist()
            for name in code.namelist():
                assert re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name), name
                data = code.read(name)
                assert data[:8] in (b"dex\n038\0", b"dex\n039\0")
                assert struct.unpack_from("<I", data, 32)[0] == len(data)
                assert hashlib.sha1(data[32:]).digest() == data[12:32]
                assert zlib.adler32(data[12:]) == struct.unpack_from("<I", data, 8)[0]
                classes = dex.dex_classes(data)
                assert not definitions.keys() & classes.keys(), "Duplicate DEX definitions"
                definitions.update(classes)
        entry = "L" + manifest["entryClass"].replace(".", "/") + ";"
        assert definitions[entry]["super"] == "Ldev/amenhancer/plugin/api/AmppPlugin;"
        assert "<init>()V" in definitions[entry]["methods"]
        assert "onLoad(Ldev/amenhancer/plugin/api/PluginContext;)V" in definitions[entry]["methods"]
        forbidden = ("Ldev/amenhancer/", "Lcom/apple/android/music/", "Lio/github/libxposed/",
                     "Lcom/kyant/backdrop/", "Landroidx/compose/")
        assert not any(name.startswith(forbidden) for name in definitions), "Private module, host or SDK code bundled"
        required = ("LyriconIntegration", "IosMediaControls", "CardSharingIntegration", "CardSharingFeature", "AudioQualityIntegration",
                    "NativeShareImage", "NativeShareImageComposer", "SharedCardAction", "SharedCardKind", "PluginGlassHostView", "PlayerPageChromeIntegration", "PlayerMoreMenuIntegration",
                    "NativePlayerMoreMenu", "IosPlayerMoreMenuView", "PlayerMenuLayout", "MenuSurfaceCleanup",
                    "PlayerDialogsIntegration", "PlayerVolumeIntegration", "IosVolumeSliderView", "PlayerVolumeGeometry", "PlayerVolumeControls",
                    "PlayerVolumeSpacing", "PlayerVolumeMotion", "PlayerVolumePosition", "PlayerVolumePlacement",
                    "PlayerVolumeRegion", "PlayerVolumeSpace", "PlayerVolumeMinimum",
                    "PlayerVolumeMeasurement", "PlayerVolumeSpaceTarget", "PlayerVolumeLayoutState", "PlayerVolumeHoldColumn",
                    "PlayerVolumePanePreparation", "PlayerVolumeDrawSettlement", "TabletPlayerPresentationMode",
                    "IosVolumeIcons", "TabletPlayerProgressStyle",
                    "PlayerArtworkRecovery", "PlayerBackgroundRecovery", "TabletPlayerActionsIntegration", "TabletPlayerActionsGeometry", "TabletPlayerMetadataStyle", "PlayerViewBootstrap",
                    "TabletTransportStyle", "TabletLyricsPanePresentation", "TabletLyricsPaneState", "TabletLyricsPaneGeometry",
                    "TabletPlayerControlSizing", "TabletArtworkSpacing", "TabletArtworkSpacingGeometry",
                    "TabletPlayerContentWidth", "TabletPlayerContentWidthGeometry", "TabletPaneTransitionSurface", "TabletPaneTransitionGate",
                    "TabletTranslationGlyph",
                    "TabletContentWidthSettlement",
                    "ContentDownloadsIntegration", "ContentDownloadArtistAnchor", "ContentDownloadCapture", "ContentExportStorage", "MotionArtworkDownload", "MotionArtworkRemux", "PlayerContentDownloadsIntegration", "TabletPlaybackModesAccess")
        assert all("Ldev/kifranei/ampp/media/" + name + ";" in definitions for name in required)
        for name in ("BluetoothOutputName", "AudioOutputDeviceLabel", "AudioOutputLabelGeometry", "AudioOutputButtonVisibility", "IosDeviceOutputDrawable", "OutputDeviceIcon", "BluetoothOutputState",
                     "SystemAudioOutput", "AudioOutputRouting", "AudioOutputRoute", "AudioOutputKind", "AudioOutputState"):
            assert "Ldev/kifranei/ampp/media/" + name + ";" in definitions
        for asset, dimensions in (('ios-output-speaker.png', (46, 60)), ('ios-output-airpods.png', (62, 60))):
            png = archive.read('assets/' + asset)
            assert png[:8] == b'\x89PNG\r\n\x1a\n' and png[12:16] == b'IHDR'
            assert struct.unpack('>II', png[16:24]) == dimensions and png[25] == 6, f'Bad RGBA icon: {asset}'
        for asset, digest in (
            ('flamingo-volume-low.xml', 'bcc60e3a289bc33b885fedecb2d5fe2012cb2f11217bdb20cc420398eab86030'),
            ('flamingo-volume-high.xml', '7dc53beacaf4ff9edcf5341353bd428627fd0a61d32cf7370d225066e79eae9d'),
        ):
            assert hashlib.sha256(archive.read('assets/' + asset)).hexdigest() == digest, f'Changed Flamingo vector: {asset}'
        fields = definitions['Ldev/kifranei/ampp/media/MediaSettings;']['fields']
        for name in ('lyricon', 'iosControls', 'sharing', 'playerGlass', 'playerHandle', 'systemCorners', 'volumeBar', 'contentDownloads'):
            assert fields.get(name) == 'Z', f'Missing boolean setting: {name}'
        assert not {'lyricsSharing', 'qualityGlass', 'moreGlass', 'confirmationGlass', 'sleepGlass'} & fields.keys()
        assert 'Ldev/kifranei/ampp/media/MediaSettingsText;' in definitions
    print(f"PASS: SDK v1 media ZIP; {len(definitions)} DEX definitions; eight settings; no private module/SDK copies")


if __name__ == "__main__":
    main()
