#!/bin/sh
# Compila el programa de la segunda pantalla y lo deja donde Tauri lo empaqueta.
set -e
cd "$(dirname "$0")"
mkdir -p ../src-tauri/binaries
swiftc -O -swift-version 5 -import-objc-header CGVirtualDisplay.h -target arm64-apple-macos13.0 \
  main.swift -o ../src-tauri/binaries/screenmt-pantalla-aarch64-apple-darwin
