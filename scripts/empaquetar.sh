#!/bin/sh
# Firma ScreenMT.app para que macOS la reconozca por su nombre y no por la huella de cada compilación:
# así los permisos (Grabación de pantalla, Accesibilidad) se dan una vez y sobreviven a las actualizaciones.
# Sin cuenta de desarrollador de Apple la firma es «ad hoc»; el requisito explícito es lo que la hace estable.
# Después arma el .dmg.
set -e
cd "$(dirname "$0")/.."
APP=src-tauri/target/release/bundle/macos/ScreenMT.app
ID=io.github.juanp30.screenmt
VERSION=$(node -p "require('./package.json').version")

codesign --force --sign - --identifier "$ID.pantalla" \
  --requirements "=designated => identifier \"$ID.pantalla\"" "$APP/Contents/MacOS/screenmt-pantalla"
codesign --force --sign - --identifier "$ID" \
  --requirements "=designated => identifier \"$ID\"" "$APP"
codesign --verify --deep --strict "$APP"

DMG=src-tauri/target/release/bundle/dmg/ScreenMT_${VERSION}_aarch64.dmg
mkdir -p "$(dirname "$DMG")"
TMP=$(mktemp -d)
cp -R "$APP" "$TMP/"
ln -s /Applications "$TMP/Aplicaciones"
rm -f "$DMG"
hdiutil create -quiet -volname "ScreenMT" -srcfolder "$TMP" -format UDZO "$DMG"
rm -rf "$TMP"
echo "Listo: $APP y $DMG"
